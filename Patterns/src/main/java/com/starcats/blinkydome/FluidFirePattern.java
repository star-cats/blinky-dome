package com.starcats.blinkydome;

import java.util.Random;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.pattern.LXPattern;
import heronarts.lx.utils.Noise;

/**
 * 2D fluid fire — a real Eulerian fluid simulation, not a noise field dressed up
 * as one. Ported from Scripts/FluidFire.js.
 *
 * Follows Andrew Chan's writeup (andrewkchan.dev/posts/fire.html): a collocated
 * grid carrying velocity, temperature, fuel and soot, stepped with
 *
 *   advect velocity -&gt; inject source -&gt; combust -&gt; vorticity confinement -&gt;
 *   buoyancy and other forces -&gt; pressure projection -&gt; advect scalars
 *
 * Advection is semi-Lagrangian, so it is unconditionally stable: a cell asks
 * where its contents came from one step ago and samples there. Incompressibility
 * comes from a Jacobi solve of the pressure Poisson equation, which is what makes
 * the plume roll and curl instead of just drifting upward — a fire without
 * projection looks like a smoke machine.
 *
 * Vorticity confinement puts back the small eddies that the grid throws away
 * every time it advects. It is the single knob that decides whether this reads as
 * fire or as hot fog.
 *
 * The box is closed: velocity is clamped at the walls, every field lookup is
 * clamped to the grid, and nothing wraps. The top is open by default so the plume
 * can leave — close it with Lid and the ceiling starts rolling smoke back down.
 *
 * Color is blackbody, not a gradient: temperature maps to Kelvin between the Cool
 * and Hot knobs and then to RGB, so a low Hot burns deep orange and a high one
 * runs through white into blue.
 *
 * <h2>The source balls</h2>
 *
 * Fuel comes from three source balls, indexed 0, 1 and 2, moved by a
 * choreographer. Every state places them as a formation keyed on that index, so
 * the three always read as one figure rather than three independent lights.
 *
 * Nothing about a ball is drawn — a ball is only where fuel and momentum enter
 * the fluid, and everything visible is the fire's response to that. Two rules
 * make the motion legible. A ball never jumps: choreography sets a target and a
 * shared PID-ish controller flies the ball there, so a state change is a move
 * rather than a cut. And a ball smears: every point along the segment it
 * travelled in a step gets the full source strength rather than a share of it, so
 * a moving ball lays a solid trail and drags the fluid along its whole heading.
 *
 * Balls pass through each other freely; only PLACER cares where the others are.
 *
 * <h2>Timing</h2>
 *
 * The script ran its choreography off a hard-coded 120 BPM. Here it comes off the
 * {@link PrimaryController}'s grid through a {@link PrimaryController.Follower},
 * the same way {@link StarCatPattern} does, with Phase to slide it. The fluid
 * itself still runs on its own clock at its own Speed — the fire is not a
 * metronome — but every choreographed move is on the beat.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Fluid Fire")
@LXComponent.Description("Eulerian fluid fire fed by three choreographed source balls")
public class FluidFirePattern extends LXPattern {

  // ---------------------------------------------------------------- grid size
  //
  // Simulation resolution, in cells. Cost is linear in cell count and
  // independent of LED count — the grid is the picture and the model just
  // samples it, so a denser rig costs nothing extra. The grid always covers the
  // model's full normalized extent: a non-square rig makes non-square cells,
  // which the simulation does not mind.
  private static final int GRID_W = 40;
  private static final int GRID_H = 46;

  /**
   * Simulation rate, held independent of the engine's frame rate so the fire
   * looks the same at 30 fps as at 120. Substeps are capped rather than letting a
   * stalled frame try to catch up all at once.
   */
  private static final double SIM_HZ = 60;
  private static final int MAX_SUBSTEPS = 4;

  /**
   * Temperature that fully saturated fuel burns at. Above 1 so a fuel-rich cell
   * pins the top of the color ramp and the core of the flame goes white.
   */
  private static final double BURN_TEMP = 1.4;

  /**
   * How much hotter than a fully burning cell a source is allowed to drive one.
   * Combustion tops out at BURN_TEMP, and a source clamped to the same ceiling
   * could never be more than as hot as the fire it lit -- turning Source up past
   * the point where it saturated bought nothing. Twice that gives the knob its
   * top half back: the extra heat is past the top of the color ramp so the core
   * does not change color, it lifts harder and stays lit longer on the way up.
   */
  private static final double SOURCE_TEMP = BURN_TEMP * 2;

  /** Spatial frequency of the turbulence stream function, in cells. */
  private static final double TURB_SCALE = .09;

  /** How much soot dims the gas behind it, where that gas is not luminous. */
  private static final double SOOT_OCCLUSION = .85;

  /**
   * Velocity retained per second of simulated time. Real fluid has no such term —
   * this is here as a terminal velocity, so that a maxed Jet and Buoyancy give a
   * fast fire rather than one that accelerates until every step advects the whole
   * grid off the top edge.
   */
  private static final double DRAG = .4;

  private static final int LUT_SIZE = 256;

  // ------------------------------------------------------------- choreography

  private static final int BALL_COUNT = 3;

  /**
   * A ball's speed limit, in frame-widths per second. Not a look control — it
   * bounds the length of the segment one step can smear over, which bounds the
   * work the swept stamp does.
   */
  private static final double MAX_BALL_SPEED = 4;

  /**
   * How fast a ball's radius multiplier chases the value choreography asks for.
   * Slow enough that FIRELINE's shrink to nothing reads as a fade rather than as
   * the ball being switched off.
   */
  private static final double RADIUS_CHASE = 2.5;

  private static final int STATE_ORBIT = 0;
  private static final int STATE_COLUMNS = 1;
  private static final int STATE_PINGPONG = 2;
  private static final int STATE_PLACER = 3;
  private static final int STATE_FIRELINE = 4;
  private static final String[] STATE_NAMES =
    { "ORBIT", "COLUMNS", "PINGPONG", "PLACER", "FIRELINE" };

  /**
   * Which states the Choreo trigger may pick from. A state left out still works
   * and can still be entered; it is simply not in the rotation. PLACER is out
   * temporarily — put it back by restoring it here, nothing else changes.
   */
  private static final int[] CHOREO_ROTATION =
    { STATE_ORBIT, STATE_COLUMNS, STATE_PINGPONG, STATE_FIRELINE };

  /**
   * ORBIT: beats per revolution, the two slow LFOs that deform the circle, and
   * how many cues it takes to turn the orbit around. Reversing on every cue makes
   * the orbit twitch; counting to eight turns the reversal into something that
   * arrives on the bar you were building toward.
   */
  private static final double ORBIT_BEATS = 5;
  private static final int ORBIT_CUES_PER_FLIP = 8;
  private static final double ORBIT_SQUASH_BEATS = 23;
  private static final double ORBIT_SQUASH_DEPTH = .55;
  private static final double ORBIT_AXIS_BEATS = 37;

  /**
   * COLUMNS: how far from the middle the two ends sit, how many beats a setpoint
   * takes to creep between them when nothing is cueing it, and how close a ball
   * has to get to count as having arrived. There is no middle setpoint and no
   * clock — a ball is always going to the top or to the bottom, and it only turns
   * around once it has actually got there.
   */
  private static final double COLUMN_TRAVEL = .42;
  private static final double COLUMN_CREEP_BEATS = 5.33;
  private static final double COLUMN_ARRIVE = .04;

  /**
   * PINGPONG: travel speed range in frame-widths per second, how far off a clean
   * bounce a reflection may be, and the inset of the walls it bounces off. The
   * imperfection is the point — three balls reflecting perfectly stay on the same
   * three paths forever.
   */
  private static final double PING_SPEED_MIN = .48;
  private static final double PING_SPEED_MAX = 1.536;
  private static final double PING_ANGLE_JITTER = .3;
  private static final double PING_SPEED_JITTER = .14;
  private static final double PING_MARGIN = .06;

  /**
   * PLACER: minimum center-to-center distance between two targets, as a multiple
   * of the Radius knob, and how hard to try before settling for the best of a bad
   * set. A wide Radius makes the constraint unsatisfiable for three balls in a
   * unit box, and a pattern that hangs is worse than one that crowds.
   */
  private static final double PLACER_SEPARATION = 1.3;
  private static final int PLACER_ATTEMPTS = 64;

  /**
   * PLACER sway: a parked ball breathes around its spot on two detuned sinusoids,
   * one per axis. The frequencies are deliberately not a ratio of small integers,
   * so x and y never close the same figure twice and the wander reads as alive
   * rather than as an orbit. Each ball is detuned again off its index so the three
   * do not sway as one.
   */
  private static final double PLACER_SWAY = .09;
  private static final double PLACER_SWAY_HZ_X = .85;
  private static final double PLACER_SWAY_HZ_Y = 1.15;
  private static final double PLACER_SWAY_DETUNE = .037;

  /**
   * How long a ball may sit on its spot before it goes looking for another, and
   * how close to that spot counts as sitting on it. The tolerance follows the sway
   * rather than being set independently — it has to clear it, or a ball that has
   * plainly arrived would never be found stationary.
   */
  private static final double PLACER_SETTLE_TIME = 2.;
  private static final double PLACER_SETTLED = PLACER_SWAY * 1.6;

  /**
   * FIRELINE: the launch, the fall, and the strip left behind. The strip burns
   * harder than a ball does and throws its fuel upward — it is the wall of flame
   * the balls died to light, not a row of pilot lights.
   */
  private static final double FIRE_LAUNCH_VX = .45;
  private static final double FIRE_LAUNCH_VY_MIN = .35;
  private static final double FIRE_LAUNCH_VY_MAX = .8;
  private static final double FIRE_GRAVITY = 1.5;
  private static final double FIRE_GROUND = .02;
  private static final double FIRELINE_FADE = 1.;
  private static final double FIRELINE_DENSITY = 6;
  private static final double FIRELINE_LIFT = 960;
  private static final double FIRELINE_SWIRL = 60;

  /** How much of the frame the strip occupies, bottom up. */
  private static final double FIRELINE_HEIGHT = .18;

  /** How fast the strip's noise fields move, and how finely they are sampled. */
  private static final double FIRELINE_NOISE_SWEEP = 4.5;
  private static final double FIRELINE_NOISE_SCALE = 4;

  /**
   * How hard the strip's own noise swings its output. This rides on top of
   * Flicker rather than under it: Flicker is a knob the whole pattern shares and
   * can be turned off, and the strip has to churn regardless.
   */
  private static final double FIRELINE_GUST = .8;

  /**
   * The same gust as it applies to the lift, over 1 on purpose so the multiplier
   * goes negative between tongues.
   *
   * This is the constant that decides whether the strip churns or just sits there
   * glowing, and the reason is the pressure solve. The fluid is incompressible and
   * the strip sits on a solid floor, so a push that is the same all the way across
   * asks for fluid to leave the bottom with nothing coming in beneath it. The
   * projection step is entitled to refuse that, and does: the uniform part of the
   * lift is very nearly cancelled every step, however large it is. What survives
   * is the part that varies across the strip, because fluid rising in one column
   * can be fed by fluid sinking in the next. Pushing down between the tongues is
   * therefore not a flourish — it is what buys the upward push its right to exist.
   */
  private static final double FIRELINE_GUST_LIFT = 1.6;

  // -------------------------------------------------------------------- source

  public final CompoundParameter srcLevel =
    new CompoundParameter("Source", 1, 0, 1)
    .setDescription("How hard fuel is injected at each source ball");

  public final CompoundParameter srcRadius =
    new CompoundParameter("Radius", .21933594313275528, 0, 1)
    .setDescription("Source radius shared by all three balls");

  public final CompoundParameter srcX =
    new CompoundParameter("Center X", .5026562500651925, 0, 1)
    .setDescription("Formation center, horizontal");

  public final CompoundParameter srcY =
    new CompoundParameter("Center Y", .5, 0, 1)
    .setDescription("Formation center, vertical");

  public final CompoundParameter spread =
    new CompoundParameter("Spread", .7318359429991688, 0, 1)
    .setDescription("Formation size -- orbit radius, column separation");

  public final CompoundParameter jet =
    new CompoundParameter("Jet", .5620312509126961, 0, 1)
    .setDescription("Upward velocity injected at each ball");

  public final CompoundParameter flicker =
    new CompoundParameter("Flicker", .7418749998323619, 0, 1)
    .setDescription("How much the source strength wavers over time");

  public final CompoundParameter advect =
    new CompoundParameter("Advect", .7695703179488191, 0, 1)
    .setDescription("How hard the balls and the fire line drive the fluid; 200:1 range");

  // --------------------------------------------------------------- ball motion
  //
  // One controller, shared by all three balls. Chase is the spring pulling a ball
  // to its target, Damping is what stops it overshooting, and Trim is the integral
  // term -- it kills the steady-state droop of a ball pushed by its own fire, and
  // it is the one that will wind up and wobble if leaned on.

  public final CompoundParameter pidP =
    new CompoundParameter("Chase", .4, 0, 1)
    .setDescription("How hard a ball is pulled toward its target");

  public final CompoundParameter pidD =
    new CompoundParameter("Damping", .5, 0, 1)
    .setDescription("How hard that pull is resisted; low overshoots");

  public final CompoundParameter pidI =
    new CompoundParameter("Trim", 0, 0, 1)
    .setDescription("Integral correction for persistent error");

  public final TriggerParameter choreo =
    new TriggerParameter("Choreo", this::onChoreoTrigger)
    .setDescription("Transition to a random other choreography state");

  public final TriggerParameter cue =
    new TriggerParameter("Cue", this::onCueTrigger)
    .setDescription("Accent the current state; means something different in each");

  public final CompoundParameter phase =
    new CompoundParameter("Phase", 0, -.5, .5)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Slide the choreography earlier or later against the beat grid, in beats");

  public final BoundedParameter fallbackBpm =
    new BoundedParameter("Free BPM", 120, 40, 200)
    .setDescription("Tempo to choreograph at when there is no controller, or before it has found one");

  public final CompoundParameter sync =
    new CompoundParameter("Sync", 1, .1, 10)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("How long the choreography takes to drift back onto the beat grid; it never snaps");

  // --------------------------------------------------------------------- fluid

  public final CompoundParameter buoyancy =
    new CompoundParameter("Buoyancy", 1, 0, 1)
    .setDescription("How hard heat lifts the fluid");

  public final CompoundParameter cooling =
    new CompoundParameter("Cooling", .3628906246012775, 0, 1)
    .setDescription("Radiative cooling rate; sets the flame's height");

  public final CompoundParameter burn =
    new CompoundParameter("Burn", .5, 0, 1)
    .setDescription("How fast fuel is consumed once it is lit");

  public final CompoundParameter vorticity =
    new CompoundParameter("Vorticity", 1, 0, 1)
    .setDescription("Curl put back into the flame; 0 is a smooth plume");

  public final CompoundParameter wind =
    new CompoundParameter("Wind", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Sideways push; 0.5 is still");

  public final CompoundParameter turbulence =
    new CompoundParameter("Turb", 1, 0, 1)
    .setDescription("Divergence-free noise stirred into the velocity");

  public final CompoundParameter smoke =
    new CompoundParameter("Smoke", .35, 0, 1)
    .setDescription("Soot given off by burning fuel");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .5, 0, 1)
    .setDescription("Simulation time scale");

  // -------------------------------------------------------------------- render

  public final CompoundParameter coolK =
    new CompoundParameter("Cool", .3, 0, 1)
    .setDescription("Color temperature of the coolest visible gas");

  public final CompoundParameter hotK =
    new CompoundParameter("Hot", .45, 0, 1)
    .setDescription("Color temperature of the flame core; high burns white to blue");

  public final CompoundParameter falloff =
    new CompoundParameter("Falloff", .5702734446938849, 0, 1)
    .setDescription("Contrast of the temperature-to-brightness curve");

  public final CompoundParameter smokeGlow =
    new CompoundParameter("Glow", .19390624327934347, 0, 1)
    .setDescription("How brightly soot renders on its own");

  public final CompoundParameter level =
    new CompoundParameter("Level", .9, 0, 1)
    .setDescription("Overall brightness");

  public final DiscreteParameter solver =
    new DiscreteParameter("Solver", 14, 1, 41)
    .setDescription("Pressure solver iterations; more is rounder and slower");

  public final BooleanParameter lid =
    new BooleanParameter("Lid", false)
    .setDescription("Close the top of the box instead of letting the plume out");

  // ----------------------------------------------------------------- the fields

  private final int W = GRID_W;
  private final int H = GRID_H;
  private final int W1 = GRID_W - 1;
  private final int H1 = GRID_H - 1;
  private final int cells = GRID_W * GRID_H;

  private double[] velU, velV, velU2, velV2;
  private double[] heat, heat2, fuel, fuel2, soot, soot2;
  private double[] pressure, pressure2, divergence, curl, psi;

  private final double[] lutR = new double[LUT_SIZE];
  private final double[] lutG = new double[LUT_SIZE];
  private final double[] lutB = new double[LUT_SIZE];

  private double simAccumulator = 0;
  private double simClock = 0;
  private double glowScale = 0;

  // ------------------------------------------------------------ the source balls
  //
  // Three of them, and the index is part of the choreography rather than a loop
  // counter: ball 0 leads the column, ball 0 is the first third of the orbit.

  private static final class Ball {
    final int index;
    double x = .5, y = .5;
    /** Where it was last substep, which is what the swept stamp smears from. */
    double px = .5, py = .5;
    double vx = 0, vy = 0;
    /** Controller target. */
    double tx = .5, ty = .5;
    /** Integral accumulators. */
    double ix = 0, iy = 0;
    /** PLACER's parked spot, and how long it has been sitting on it. */
    double hx = .5, hy = .5;
    double stillTime = 0;
    /** COLUMNS' destination end: +1 is the top, -1 the bottom. */
    double colDest = 1;
    double radiusMul = 1;
    double radiusTarget = 1;
    /** FIRELINE flies the ball directly instead of through the controller. */
    boolean ballistic = false;
    boolean landed = false;
    /** PINGPONG's bouncing point, which the ball chases. */
    double bx = .5, by = .5;
    double dirX = 1, dirY = 0;
    double speed = 0;

    Ball(int index) {
      this.index = index;
    }
  }

  private final Ball[] balls = new Ball[BALL_COUNT];
  private int choreoState = STATE_ORBIT;

  /**
   * Triggers fire from whatever thread rang them, so they are counted here and
   * spent in run(), where the choreography lives. Counting rather than flagging
   * means two cues inside one frame both land.
   */
  private int pendingChoreo = 0;
  private int pendingCue = 0;

  /**
   * ORBIT's angle is accumulated rather than derived from the beat clock, because
   * the cue reverses it: a derived angle would snap to the mirrored position the
   * instant the sign flipped.
   */
  private double orbitPhase = 0;
  private double orbitDir = 1;
  private int orbitCueCount = 0;

  /** FIRELINE's strip. Always here, nonzero only in that state or fading out of one. */
  private double fireLineLevel = 0;
  private double fireLineTarget = 0;

  private final PrimaryController.Follower clock = new PrimaryController.Follower();
  private final Random random = new Random();

  /** Beat position the choreography has consumed so far, and the live one. */
  private double choreoBeats = 0;
  private double lastBeats = Double.NaN;

  public FluidFirePattern(LX lx) {
    super(lx);
    addParameter("srcLevel", this.srcLevel);
    addParameter("srcRadius", this.srcRadius);
    addParameter("srcX", this.srcX);
    addParameter("srcY", this.srcY);
    addParameter("spread", this.spread);
    addParameter("jet", this.jet);
    addParameter("flicker", this.flicker);
    addParameter("advect", this.advect);
    addParameter("pidP", this.pidP);
    addParameter("pidD", this.pidD);
    addParameter("pidI", this.pidI);
    addParameter("choreo", this.choreo);
    addParameter("cue", this.cue);
    addParameter("phase", this.phase);
    addParameter("fallbackBpm", this.fallbackBpm);
    addParameter("sync", this.sync);
    addParameter("buoyancy", this.buoyancy);
    addParameter("cooling", this.cooling);
    addParameter("burn", this.burn);
    addParameter("vorticity", this.vorticity);
    addParameter("wind", this.wind);
    addParameter("turbulence", this.turbulence);
    addParameter("smoke", this.smoke);
    addParameter("speed", this.speed);
    addParameter("coolK", this.coolK);
    addParameter("hotK", this.hotK);
    addParameter("falloff", this.falloff);
    addParameter("smokeGlow", this.smokeGlow);
    addParameter("level", this.level);
    addParameter("solver", this.solver);
    addParameter("lid", this.lid);

    this.velU = new double[this.cells];
    this.velV = new double[this.cells];
    this.velU2 = new double[this.cells];
    this.velV2 = new double[this.cells];
    this.heat = new double[this.cells];
    this.heat2 = new double[this.cells];
    this.fuel = new double[this.cells];
    this.fuel2 = new double[this.cells];
    this.soot = new double[this.cells];
    this.soot2 = new double[this.cells];
    this.pressure = new double[this.cells];
    this.pressure2 = new double[this.cells];
    this.divergence = new double[this.cells];
    this.curl = new double[this.cells];
    this.psi = new double[this.cells];

    for (int i = 0; i < BALL_COUNT; ++i) {
      this.balls[i] = new Ball(i);
    }
    enterState(STATE_ORBIT);
  }

  private void onChoreoTrigger() {
    ++this.pendingChoreo;
  }

  private void onCueTrigger() {
    ++this.pendingCue;
  }

  @Override
  protected void run(double deltaMs) {
    this.clock.loop(deltaMs, this.fallbackBpm.getValue(), this.sync.getValue());

    // Spent here rather than inside the substep loop: a state change is one
    // event, and firing it in each substep would re-roll the state several times.
    while (this.pendingChoreo > 0) {
      --this.pendingChoreo;
      enterState(randomOtherState(this.choreoState));
      LX.log("FluidFire: choreography -> " + STATE_NAMES[this.choreoState]);
    }

    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, .25) : 0;
    double step = 1 / SIM_HZ;
    this.simAccumulator += dt * lerp(.15, 2.5, this.speed.getValue());

    double budget = MAX_SUBSTEPS * step;
    if (this.simAccumulator > budget) {
      // Do not try to catch up after a stall. The fire runs briefly in slow
      // motion, which nobody sees; the alternative is a frame that takes ten
      // times as long as the one that caused it.
      this.simAccumulator = budget;
    }

    int steps = (int) Math.floor(this.simAccumulator / step);
    if (steps > 0) {
      // The beat advance for this frame, shared out over the substeps so the
      // choreography lands exactly on the grid at the end of the frame while
      // still moving smoothly within it. Drift correction from the Follower is
      // already folded into this difference.
      double beatsNow = this.clock.getBeats() + this.phase.getValue();
      if (Double.isNaN(this.lastBeats)) {
        this.lastBeats = beatsNow;
        this.choreoBeats = beatsNow;
      }
      double beatAdvance = beatsNow - this.lastBeats;
      this.lastBeats = beatsNow;
      double beatStep = beatAdvance / steps;

      for (int i = 0; i < steps; ++i) {
        this.simAccumulator -= step;
        this.simClock += step;
        this.choreoBeats += beatStep;
        simulate(step, beatStep);
      }
    }

    buildColorTable();
    this.glowScale = this.smokeGlow.getValue() * this.smokeGlow.getValue()
      * this.level.getValue() * 255;
    draw();
  }

  private void simulate(double dt, double dBeats) {
    // Choreography moves first so the swept stamp smears over exactly the motion
    // that belongs to this substep.
    updateChoreography(dt, dBeats);
    advectVelocity(dt);
    injectSources(dt);
    combust(dt);
    applyVorticity(dt);
    applyForces(dt);
    project();
    advectScalars(dt);
  }

  // ------------------------------------------------------------- state machine

  private int randomOtherState(int current) {
    // Uniform over the rotation minus whatever is playing now. Built as a list
    // rather than indexed around a hole, because the rotation is no longer every
    // state and the current one may not even be in it.
    int[] choices = new int[CHOREO_ROTATION.length];
    int count = 0;
    for (int i = 0; i < CHOREO_ROTATION.length; ++i) {
      if (CHOREO_ROTATION[i] != current) {
        choices[count++] = CHOREO_ROTATION[i];
      }
    }
    if (count == 0) {
      // Every rotation entry is the current state; staying put is the only
      // honest answer.
      return current;
    }
    return choices[this.random.nextInt(count)];
  }

  private void enterState(int next) {
    exitState(this.choreoState);
    this.choreoState = next;

    if (next == STATE_ORBIT) {
      // A fresh count per visit, so the first cue after arriving is always the
      // first of eight rather than however many were left over last time.
      this.orbitCueCount = 0;
    } else if (next == STATE_COLUMNS) {
      // Ball 0 heads for the top and the other two for the bottom, each setpoint
      // starting from the far end so all three have the same full traverse ahead
      // of them. Equal distances at equal speed is what keeps the two groups in
      // antiphase for as long as the state runs.
      for (int i = 0; i < BALL_COUNT; ++i) {
        Ball ball = this.balls[i];
        ball.colDest = (i == 0) ? 1 : -1;
        ball.ty = .5 - COLUMN_TRAVEL * ball.colDest;
      }
    } else if (next == STATE_PINGPONG) {
      // Each ball leaves on its own heading, from where it already is -- the
      // formation reads as one that scatters rather than one that restarts.
      for (int i = 0; i < BALL_COUNT; ++i) {
        Ball ball = this.balls[i];
        double angle = this.random.nextDouble() * Math.PI * 2;
        ball.dirX = Math.cos(angle);
        ball.dirY = Math.sin(angle);
        ball.speed = randomRange(PING_SPEED_MIN, PING_SPEED_MAX);
        ball.bx = clamp(ball.x, PING_MARGIN, 1 - PING_MARGIN);
        ball.by = clamp(ball.y, PING_MARGIN, 1 - PING_MARGIN);
      }
    } else if (next == STATE_PLACER) {
      placeBalls();
    } else if (next == STATE_FIRELINE) {
      for (int i = 0; i < BALL_COUNT; ++i) {
        Ball ball = this.balls[i];
        ball.ballistic = true;
        ball.landed = false;
        ball.vx = randomRange(-FIRE_LAUNCH_VX, FIRE_LAUNCH_VX);
        ball.vy = randomRange(FIRE_LAUNCH_VY_MIN, FIRE_LAUNCH_VY_MAX);
      }
    }
  }

  private void exitState(int previous) {
    if (previous == STATE_FIRELINE) {
      // Leaving takes the balls off ballistic control and hands them back to the
      // controller at full size; the strip fades on its own from here.
      this.fireLineTarget = 0;
      for (int i = 0; i < BALL_COUNT; ++i) {
        Ball ball = this.balls[i];
        ball.ballistic = false;
        ball.landed = false;
        ball.radiusTarget = 1;
        ball.ix = 0;
        ball.iy = 0;
      }
    }
  }

  /** One choreography step: spend the cue, place the targets, fly the balls. */
  private void updateChoreography(double dt, double dBeats) {
    // The count, not a flag: ORBIT counts cues to eight before acting on them,
    // and collapsing two that arrived in one frame into a single "yes" would drop
    // one. States that only care whether a cue happened test it for nonzero.
    int cues = this.pendingCue;
    this.pendingCue = 0;

    if (this.choreoState == STATE_ORBIT) {
      updateOrbit(dBeats, cues);
    } else if (this.choreoState == STATE_COLUMNS) {
      updateColumns(dBeats, cues);
    } else if (this.choreoState == STATE_PINGPONG) {
      updatePingpong(dt);
    } else if (this.choreoState == STATE_PLACER) {
      updatePlacer(dt, cues);
    } else {
      updateFireline(dt);
    }

    driveBalls(dt);

    // Linear, so the second this is specified in is actually a second.
    double fadeStep = dt / FIRELINE_FADE;
    if (this.fireLineLevel < this.fireLineTarget) {
      this.fireLineLevel = Math.min(this.fireLineTarget, this.fireLineLevel + fadeStep);
    } else if (this.fireLineLevel > this.fireLineTarget) {
      this.fireLineLevel = Math.max(this.fireLineTarget, this.fireLineLevel - fadeStep);
    }
  }

  /**
   * ORBIT -- the three spaced a third of a turn apart on a circle around the
   * formation center, under two slow LFOs: one squashing the vertical axis, one
   * rotating the axis the squash happens on.
   *
   * Cues are counted rather than acted on: every eighth reverses the orbit, and
   * the seven between do nothing visible.
   */
  private void updateOrbit(double dBeats, int cues) {
    if (cues > 0) {
      this.orbitCueCount += cues;
      if (this.orbitCueCount >= ORBIT_CUES_PER_FLIP) {
        this.orbitCueCount -= ORBIT_CUES_PER_FLIP;
        this.orbitDir = -this.orbitDir;
      }
    }
    this.orbitPhase += dBeats / ORBIT_BEATS * Math.PI * 2 * this.orbitDir;

    double b = this.choreoBeats;
    double squash = 1 - ORBIT_SQUASH_DEPTH * .5
      * (1 - Math.cos(b * Math.PI * 2 / ORBIT_SQUASH_BEATS));
    double axis = b * Math.PI * 2 / ORBIT_AXIS_BEATS;
    double cosAxis = Math.cos(axis);
    double sinAxis = Math.sin(axis);
    double radius = lerp(.08, .45, this.spread.getValue());
    double cx = this.srcX.getValue();
    double cy = this.srcY.getValue();

    for (int i = 0; i < BALL_COUNT; ++i) {
      Ball ball = this.balls[i];
      double angle = this.orbitPhase + i * Math.PI * 2 / BALL_COUNT;
      double ex = Math.cos(angle) * radius;
      double ey = Math.sin(angle) * radius * squash;
      ball.tx = cx + ex * cosAxis - ey * sinAxis;
      ball.ty = cy + ex * sinAxis + ey * cosAxis;
      ball.radiusTarget = 1;
    }
  }

  /**
   * COLUMNS -- ball 0 rides the center column, balls 1 and 2 ride columns either
   * side of it, and the two groups head for opposite ends.
   *
   * A ball is only ever going to the top or to the bottom; there is no middle
   * setpoint and nothing on a timer. Left alone, the setpoint creeps toward that
   * end over COLUMN_CREEP_BEATS and the ball follows it up the column. A cue
   * commits: the setpoint goes the whole way at once and the controller flies the
   * ball there, which is the difference between the column drifting and the column
   * being thrown.
   *
   * The destination only flips once the ball itself has arrived -- not once the
   * setpoint has. After a cue the setpoint is there instantly while the ball still
   * has the length of the frame to cross, and flipping then would turn it around
   * before it ever made the trip.
   */
  private void updateColumns(double dBeats, int cues) {
    double offset = lerp(.08, .42, this.spread.getValue());
    double creep = (2 * COLUMN_TRAVEL) / COLUMN_CREEP_BEATS * dBeats;
    double cx = this.srcX.getValue();

    for (int i = 0; i < BALL_COUNT; ++i) {
      Ball ball = this.balls[i];
      double destY = .5 + COLUMN_TRAVEL * ball.colDest;

      ball.tx = cx + ((i == 0) ? 0 : ((i == 1) ? -offset : offset));

      if (cues > 0) {
        ball.ty = destY;
      } else if (ball.ty < destY) {
        ball.ty = Math.min(destY, ball.ty + creep);
      } else if (ball.ty > destY) {
        ball.ty = Math.max(destY, ball.ty - creep);
      }

      // Both conditions matter. The setpoint test keeps a ball that entered the
      // state already sitting at its destination from turning around on the first
      // frame -- its setpoint starts at the far end, so it has not arrived at
      // anything yet, however close it happens to be standing.
      if (Math.abs(ball.ty - destY) < 1e-9 && Math.abs(ball.y - destY) <= COLUMN_ARRIVE) {
        ball.colDest = -ball.colDest;
      }

      ball.radiusTarget = 1;
    }
  }

  /**
   * PINGPONG -- each ball chases a point that flies straight and bounces off the
   * walls, with the bounce deliberately imperfect so the three drift apart instead
   * of running the same loop forever. Cue does nothing here.
   */
  private void updatePingpong(double dt) {
    double lo = PING_MARGIN;
    double hi = 1 - PING_MARGIN;

    for (int i = 0; i < BALL_COUNT; ++i) {
      Ball ball = this.balls[i];
      ball.bx += ball.dirX * ball.speed * dt;
      ball.by += ball.dirY * ball.speed * dt;

      boolean bounced = false;
      if (ball.bx < lo) {
        ball.bx = lo;
        ball.dirX = -ball.dirX;
        bounced = true;
      } else if (ball.bx > hi) {
        ball.bx = hi;
        ball.dirX = -ball.dirX;
        bounced = true;
      }
      if (ball.by < lo) {
        ball.by = lo;
        ball.dirY = -ball.dirY;
        bounced = true;
      } else if (ball.by > hi) {
        ball.by = hi;
        ball.dirY = -ball.dirY;
        bounced = true;
      }

      if (bounced) {
        double angle = Math.atan2(ball.dirY, ball.dirX)
          + randomRange(-PING_ANGLE_JITTER, PING_ANGLE_JITTER);
        ball.dirX = Math.cos(angle);
        ball.dirY = Math.sin(angle);
        ball.speed = clamp(
          ball.speed * randomRange(1 - PING_SPEED_JITTER, 1 + PING_SPEED_JITTER),
          PING_SPEED_MIN, PING_SPEED_MAX);
      }

      ball.tx = ball.bx;
      ball.ty = ball.by;
      ball.radiusTarget = 1;
    }
  }

  /**
   * PLACER -- never quite still, and never settled for long.
   *
   * The sway is two sinusoids per ball, x and y on different frequencies so the
   * ball traces an open Lissajous figure instead of a circle, each ball detuned
   * off its index so the three drift out of step. It is applied to the target
   * rather than the position, so it arrives through the controller.
   *
   * A ball that has held its spot for PLACER_SETTLE_TIME goes and finds another,
   * on its own clock rather than the formation's. "Held its spot" is measured
   * against the ball's home rather than its speed, because the sway means its
   * speed never reaches zero.
   */
  private void updatePlacer(double dt, int cues) {
    if (cues > 0) {
      placeBalls();
    }

    double tau = Math.PI * 2;
    for (int i = 0; i < BALL_COUNT; ++i) {
      Ball ball = this.balls[i];
      double detune = 1 + i * PLACER_SWAY_DETUNE;
      ball.tx = ball.hx + PLACER_SWAY
        * Math.sin(tau * PLACER_SWAY_HZ_X * detune * this.simClock + i * 2.1);
      ball.ty = ball.hy + PLACER_SWAY
        * Math.sin(tau * PLACER_SWAY_HZ_Y * detune * this.simClock + i * 3.7);
      ball.radiusTarget = 1;

      double dx = ball.x - ball.hx;
      double dy = ball.y - ball.hy;
      if (dx * dx + dy * dy <= PLACER_SETTLED * PLACER_SETTLED) {
        ball.stillTime += dt;
        if (ball.stillTime >= PLACER_SETTLE_TIME) {
          placeOne(ball);
        }
      } else {
        // Still travelling -- the clock only runs once it has arrived.
        ball.stillTime = 0;
      }
    }
  }

  /**
   * Scatter all three to fresh spots.
   *
   * Rejection-sampled so no two sit closer than PLACER_SEPARATION times the
   * Radius knob. That constraint has no solution once Radius is wide, so the
   * search is capped and keeps the roomiest set it saw rather than looping.
   */
  private void placeBalls() {
    double minDistance = PLACER_SEPARATION * baseRadius();
    double margin = clamp(baseRadius() * .5, .02, .3);
    double lo = margin;
    double hi = 1 - margin;

    double[] bestX = new double[BALL_COUNT];
    double[] bestY = new double[BALL_COUNT];
    double[] xs = new double[BALL_COUNT];
    double[] ys = new double[BALL_COUNT];
    double bestScore = -1;

    for (int attempt = 0; attempt < PLACER_ATTEMPTS; ++attempt) {
      for (int i = 0; i < BALL_COUNT; ++i) {
        xs[i] = randomRange(lo, hi);
        ys[i] = randomRange(lo, hi);
      }

      // Score a candidate set by its tightest pair, so the fallback is the most
      // spread out set the search happened to see.
      double closest = Double.MAX_VALUE;
      for (int a = 0; a < BALL_COUNT; ++a) {
        for (int b = a + 1; b < BALL_COUNT; ++b) {
          double dx = xs[a] - xs[b];
          double dy = ys[a] - ys[b];
          closest = Math.min(closest, Math.sqrt(dx * dx + dy * dy));
        }
      }
      if (closest > bestScore) {
        bestScore = closest;
        System.arraycopy(xs, 0, bestX, 0, BALL_COUNT);
        System.arraycopy(ys, 0, bestY, 0, BALL_COUNT);
      }
      if (closest >= minDistance) {
        break;
      }
    }

    for (int k = 0; k < BALL_COUNT; ++k) {
      this.balls[k].hx = bestX[k];
      this.balls[k].hy = bestY[k];
      this.balls[k].stillTime = 0;
    }
  }

  /**
   * Move one ball to a fresh spot, leaving the others where they are.
   *
   * Two different distances have to hold. Against the other balls it is the usual
   * separation, and against its own current spot it is at least a sway and a half
   * -- otherwise a narrow Radius lets the search hand back a spot the ball is
   * already standing in, and since standing there is what triggered the move, it
   * would sit and re-roll every PLACER_SETTLE_TIME without going anywhere.
   *
   * Candidates are scored on how well they satisfy both as a fraction, so the
   * fallback after a failed search is the most balanced near-miss rather than one
   * generous about the others and useless about itself.
   */
  private void placeOne(Ball ball) {
    double minOther = PLACER_SEPARATION * baseRadius();
    double minSelf = Math.max(minOther, PLACER_SETTLED * 1.5);
    double margin = clamp(baseRadius() * .5, .02, .3);
    double lo = margin;
    double hi = 1 - margin;

    double bestX = ball.hx;
    double bestY = ball.hy;
    double bestScore = -1;

    for (int attempt = 0; attempt < PLACER_ATTEMPTS; ++attempt) {
      double x = randomRange(lo, hi);
      double y = randomRange(lo, hi);

      double selfDx = x - ball.hx;
      double selfDy = y - ball.hy;
      double selfDistance = Math.sqrt(selfDx * selfDx + selfDy * selfDy);

      double otherDistance = Double.MAX_VALUE;
      for (int i = 0; i < BALL_COUNT; ++i) {
        if (this.balls[i] == ball) {
          continue;
        }
        double dx = x - this.balls[i].hx;
        double dy = y - this.balls[i].hy;
        otherDistance = Math.min(otherDistance, Math.sqrt(dx * dx + dy * dy));
      }

      double score = Math.min(selfDistance / minSelf, otherDistance / minOther);
      if (score > bestScore) {
        bestScore = score;
        bestX = x;
        bestY = y;
      }
      if (score >= 1) {
        break;
      }
    }

    ball.hx = bestX;
    ball.hy = bestY;
    ball.stillTime = 0;
  }

  /**
   * FIRELINE -- the balls are thrown, fall on a parabola, and land.
   *
   * The one state that flies the balls directly instead of through the
   * controller: this is a ballistic arc, and a chased target would round the apex
   * off into a lob. The first ball down lights the strip along the bottom, which
   * fades up over a second; landed balls shrink away, leaving the strip burning
   * on its own.
   */
  private void updateFireline(double dt) {
    for (int i = 0; i < BALL_COUNT; ++i) {
      Ball ball = this.balls[i];
      if (ball.landed) {
        ball.radiusTarget = 0;
        continue;
      }

      ball.vy -= FIRE_GRAVITY * dt;
      ball.x += ball.vx * dt;
      ball.y += ball.vy * dt;

      // Reflect off the sides so a hard throw lands inside the frame instead of
      // sailing out of it and dropping fuel where nothing can see it.
      if (ball.x < 0) {
        ball.x = -ball.x;
        ball.vx = -ball.vx;
      } else if (ball.x > 1) {
        ball.x = 2 - ball.x;
        ball.vx = -ball.vx;
      }

      if (ball.y <= FIRE_GROUND) {
        ball.y = FIRE_GROUND;
        ball.vx = 0;
        ball.vy = 0;
        ball.landed = true;
        ball.radiusTarget = 0;
        this.fireLineTarget = 1;
      }

      ball.tx = ball.x;
      ball.ty = ball.y;
    }
  }

  /**
   * Fly every ball to its target.
   *
   * A spring to the target, damping against its own velocity, and an integral
   * term for the droop a ball sitting in its own updraft otherwise keeps. The
   * integral is clamped: a target a ball cannot reach -- one parked outside the
   * frame by a wide Spread -- would otherwise wind it up without limit and fire
   * the ball across the frame when the state changed.
   */
  private void driveBalls(double dt) {
    double kp = lerp(2, 120, this.pidP.getValue());
    double kd = lerp(.5, 20, this.pidD.getValue());
    double ki = lerp(0, 40, this.pidI.getValue());

    for (int i = 0; i < BALL_COUNT; ++i) {
      Ball ball = this.balls[i];

      if (!ball.ballistic) {
        double ex = ball.tx - ball.x;
        double ey = ball.ty - ball.y;

        ball.ix = clamp(ball.ix + ex * dt, -.5, .5);
        ball.iy = clamp(ball.iy + ey * dt, -.5, .5);

        ball.vx += (kp * ex + ki * ball.ix - kd * ball.vx) * dt;
        ball.vy += (kp * ey + ki * ball.iy - kd * ball.vy) * dt;

        double speed = Math.sqrt(ball.vx * ball.vx + ball.vy * ball.vy);
        if (speed > MAX_BALL_SPEED) {
          double scale = MAX_BALL_SPEED / speed;
          ball.vx *= scale;
          ball.vy *= scale;
        }

        ball.x += ball.vx * dt;
        ball.y += ball.vy * dt;
      }

      ball.radiusMul += (ball.radiusTarget - ball.radiusMul) * clamp(dt * RADIUS_CHASE, 0, 1);

      // A ball whose state went bad would keep emitting at a poisoned position
      // forever, so it gets parked at the formation center rather than lost.
      if (!Double.isFinite(ball.x) || !Double.isFinite(ball.y)
        || !Double.isFinite(ball.vx) || !Double.isFinite(ball.vy)) {
        ball.x = clamp(this.srcX.getValue(), 0, 1);
        ball.y = clamp(this.srcY.getValue(), 0, 1);
        ball.vx = 0;
        ball.vy = 0;
        ball.ix = 0;
        ball.iy = 0;
        ball.px = ball.x;
        ball.py = ball.y;
      }
    }
  }

  /** The shared source radius in normalized units, before a ball's multiplier. */
  private double baseRadius() {
    return Math.max(lerp(.02, .6, this.srcRadius.getValue()), 1. / Math.min(this.W1, this.H1));
  }

  /**
   * How hard the sources drive the fluid, as a multiplier on their own speed.
   *
   * Decades rather than a straight line, because this is a knob whose useful
   * settings are not evenly spaced: the difference between 0.1 and 0.3 is the
   * difference between a hint of a wake and a visible one, while the difference
   * between 10 and 15 is nothing at all.
   *
   * A value of 1 means a source drives the fluid near it to roughly its own
   * speed, which makes the knob readable: 3 is three times as fast as the thing
   * that made it, and that is what a wake looks like.
   */
  private double advectStrength() {
    return Math.pow(10, lerp(-1, 1.3, this.advect.getValue()));
  }

  // ------------------------------------------------------------------ sampling
  //
  // Every lookup clamps to the grid. Nothing wraps: a plume that reaches an edge
  // smears along it rather than reappearing on the far side.

  /** Bilinear sample of a field at fractional cell coordinates. */
  private double sampleField(double[] field, double x, double y) {
    if (x < 0) {
      x = 0;
    } else if (x > this.W1) {
      x = this.W1;
    }
    if (y < 0) {
      y = 0;
    } else if (y > this.H1) {
      y = this.H1;
    }
    int x0 = (int) x;
    int y0 = (int) y;
    int x1 = (x0 < this.W1) ? x0 + 1 : x0;
    int y1 = (y0 < this.H1) ? y0 + 1 : y0;
    double tx = x - x0;
    double ty = y - y0;
    int row0 = y0 * this.W;
    int row1 = y1 * this.W;
    double a = field[row0 + x0];
    double b = field[row0 + x1];
    double c = field[row1 + x0];
    double d = field[row1 + x1];
    double lower = a + (b - a) * tx;
    return lower + ((c + (d - c) * tx) - lower) * ty;
  }

  // ------------------------------------------------------------------ the step

  /**
   * Advect the velocity field through itself.
   *
   * Semi-Lagrangian: trace backwards from each cell along the current velocity
   * and take what is there. Unconditionally stable, so a violent Jet or a long
   * frame cannot blow the simulation up -- it only smears it.
   */
  private void advectVelocity(double dt) {
    double retention = Math.pow(DRAG, dt);
    for (int y = 0; y < this.H; ++y) {
      for (int x = 0; x < this.W; ++x) {
        int i = y * this.W + x;
        double px = x - this.velU[i] * dt;
        double py = y - this.velV[i] * dt;
        this.velU2[i] = sampleField(this.velU, px, py) * retention;
        this.velV2[i] = sampleField(this.velV, px, py) * retention;
      }
    }
    double[] swapU = this.velU;
    this.velU = this.velU2;
    this.velU2 = swapU;
    double[] swapV = this.velV;
    this.velV = this.velV2;
    this.velV2 = swapV;
  }

  private void advectScalar(double[] src, double[] dst, double dt, double retention) {
    for (int y = 0; y < this.H; ++y) {
      for (int x = 0; x < this.W; ++x) {
        int i = y * this.W + x;
        dst[i] = sampleField(src, x - this.velU[i] * dt, y - this.velV[i] * dt) * retention;
      }
    }
  }

  private void advectScalars(double dt) {
    advectScalar(this.heat, this.heat2, dt, 1);
    advectScalar(this.fuel, this.fuel2, dt, 1);
    advectScalar(this.soot, this.soot2, dt, Math.pow(.72, dt));
    double[] swapH = this.heat;
    this.heat = this.heat2;
    this.heat2 = swapH;
    double[] swapF = this.fuel;
    this.fuel = this.fuel2;
    this.fuel2 = swapF;
    double[] swapS = this.soot;
    this.soot = this.soot2;
    this.soot2 = swapS;
  }

  /**
   * Lay one soft-edged swept disc (a capsule) of fuel into the grid.
   *
   * Distance is measured to the complete segment, not to a row of sample stamps.
   * Every cell touched anywhere during the move therefore receives source and
   * current at full strength, with no speed-, radius-, or frame-dependent gaps.
   */
  private void stampSweptDisc(double x0, double y0, double x1, double y1,
      double radius, double rate,
      double push, double kickU, double kickV) {
    int xMin = Math.max(0, (int) Math.floor((Math.min(x0, x1) - radius) * this.W1));
    int xMax = Math.min(this.W1, (int) Math.ceil((Math.max(x0, x1) + radius) * this.W1));
    int yMin = Math.max(0, (int) Math.floor((Math.min(y0, y1) - radius) * this.H1));
    int yMax = Math.min(this.H1, (int) Math.ceil((Math.max(y0, y1) + radius) * this.H1));
    double segmentX = x1 - x0;
    double segmentY = y1 - y0;
    double segmentLengthSq = segmentX * segmentX + segmentY * segmentY;

    for (int y = yMin; y <= yMax; ++y) {
      double py = (double) y / this.H1;
      for (int x = xMin; x <= xMax; ++x) {
        double px = (double) x / this.W1;
        double t = (segmentLengthSq > 0)
          ? clamp(((px - x0) * segmentX + (py - y0) * segmentY) / segmentLengthSq, 0, 1)
          : 0;
        double nx = px - (x0 + segmentX * t);
        double ny = py - (y0 + segmentY * t);
        double d = Math.sqrt(nx * nx + ny * ny) / radius;
        if (d >= 1) {
          continue;
        }
        // Squared cosine-ish falloff: soft enough that the source disc never
        // prints its own outline into the flame.
        double falloff = 1 - d * d;
        falloff *= falloff;
        int i = y * this.W + x;
        double f = this.fuel[i] + rate * falloff;
        this.fuel[i] = (f > 1) ? 1 : f;
        double h = this.heat[i] + rate * falloff * .5;
        this.heat[i] = (h > SOURCE_TEMP) ? SOURCE_TEMP : h;
        this.velV[i] += push * falloff;

        // A kick along the ball's dragged motion, added rather than blended toward.
        //
        // Blending toward the ball's own velocity was the obvious way to write
        // this and it has a ceiling built into it: the fluid can be pulled up to
        // the ball's speed and never past it, so a slowly swaying ball could not
        // stir the fire however hard it was asked to. Adding has no such ceiling
        // -- what bounds it is DRAG, which gives the fluid a terminal velocity of
        // roughly the acceleration it is being fed.
        this.velU[i] += kickU * falloff;
        this.velV[i] += kickV * falloff;
      }
    }
  }

  /**
   * Feed the three source balls across every point of the path just travelled.
   *
   * The swept disc applies one full-strength source operation to the entire
   * capsule. Speed buys length rather than costing brightness, and using actual
   * displacement for the current means reflected or constrained motion pushes in
   * the direction the source really travelled.
   */
  private void injectSources(double dt) {
    double srcAmount = this.srcLevel.getValue();
    double strength = srcAmount * srcAmount * 9;
    double base = baseRadius();
    double drive = advectStrength();
    double flick = this.flicker.getValue();
    double jetAmount = this.jet.getValue();

    for (int b = 0; b < BALL_COUNT; ++b) {
      Ball ball = this.balls[b];
      double radius = base * ball.radiusMul;

      // Below a cell across there is nothing left to stamp; a shrinking FIRELINE
      // ball reaches this and stops emitting rather than dithering single cells.
      if (strength <= 0 || radius < .5 / Math.min(this.W1, this.H1)) {
        ball.px = ball.x;
        ball.py = ball.y;
        continue;
      }

      // Each ball wavers on its own phase -- one shared waver would pulse all
      // three together and read as the whole pattern flickering.
      double waver = 1 + flick * Noise.stb_perlin_noise3(
        (float) (this.simClock * 1.7), (float) (ball.index * 13.7), 0, 0, 0, 0) * 1.6;
      if (waver < 0) {
        waver = 0;
      }

      double dx = ball.x - ball.px;
      double dy = ball.y - ball.py;
      double rate = strength * waver * dt;
      double push = jetAmount * jetAmount * 45 * waver * dt;

      // Convert actual displacement into grid cells/sec and then into this
      // step's impulse. This deliberately follows the dragged path, including
      // reflections and constraints, rather than trusting stale planned velocity.
      double motionU = (dt > 0) ? dx / dt : 0;
      double motionV = (dt > 0) ? dy / dt : 0;
      double kickU = motionU * this.W1 * drive * dt;
      double kickV = motionV * this.H1 * drive * dt;

      stampSweptDisc(
        ball.px, ball.py,
        ball.x, ball.y,
        radius,
        rate,
        push,
        kickU,
        kickV
      );

      ball.px = ball.x;
      ball.py = ball.y;
    }

    injectFireLine(dt);
  }

  /**
   * FIRELINE's strip: the whole bottom edge as one source.
   *
   * Present in every state and silent in all but one -- its level is what fades,
   * so the strip lights over a second when the balls land and dies over a second
   * when the choreography moves on, rather than switching with the state.
   */
  private void injectFireLine(double dt) {
    if (this.fireLineLevel <= 0) {
      return;
    }

    double srcAmount = this.srcLevel.getValue();
    double strength = srcAmount * srcAmount * 9 * FIRELINE_DENSITY * this.fireLineLevel;
    if (strength <= 0) {
      return;
    }

    // The strip drives the fluid off the same knob the balls do, so turning up
    // Advect turns up everything the sources do to the fire rather than only the
    // part of it that happens to be moving.
    double drive = advectStrength();
    int rows = (int) clamp(Math.round(this.H * FIRELINE_HEIGHT), 1, this.H);
    double lift = FIRELINE_LIFT * drive * this.fireLineLevel * dt;
    double swirl = FIRELINE_SWIRL * drive * this.fireLineLevel * dt;
    double sweep = this.simClock * FIRELINE_NOISE_SWEEP;
    double flick = this.flicker.getValue();

    for (int x = 0; x < this.W; ++x) {
      // The column's coordinate in the noise fields. All three slices read from
      // it, so tightening the sampling tightens the whole strip together instead
      // of putting the gusts and the flicker on different scales.
      double u = x * FIRELINE_NOISE_SCALE;

      // Flicker varies along the strip as well as over time, so the line breaks
      // into tongues instead of pulsing as one bar.
      double waver = 1 + flick * Noise.stb_perlin_noise3(
        (float) (u * .35), (float) (sweep * 2.2), 0, 0, 0, 0) * 1.3;
      if (waver < 0) {
        waver = 0;
      }

      // The strip's own gusting, on noise slices of its own. One gust drives both
      // the fuel and the lift under it, so a patch that is burning hard is also
      // the patch throwing itself upward -- which is what a tongue of flame is,
      // rather than a bright spot and a draught that share an address.
      double gustNoise = Noise.stb_perlin_noise3(
        (float) (u * .7), (float) (sweep * 2.6), 3.1f, 0, 0, 0);

      // Fuel keeps a floor at zero, because negative fuel is not a thing.
      double gust = 1 + FIRELINE_GUST * gustNoise;
      if (gust < 0) {
        gust = 0;
      }

      // The lift deliberately keeps no such floor: it has to be allowed to pull
      // down between the tongues, or the pressure solve cancels the whole push.
      double gustLift = 1 + FIRELINE_GUST_LIFT * gustNoise;

      // A second, faster noise on a different slice pushes sideways as well as
      // up. Lift alone gives a flat sheet of flame; this is what makes the strip
      // curl into separate tongues that lean and cross as they climb.
      double lateral = Noise.stb_perlin_noise3(
        (float) (u * .55), (float) (sweep * 3.1), 7.3f, 0, 0, 0);

      double rate = strength * waver * gust * dt;

      for (int y = 0; y < rows; ++y) {
        double falloff = 1 - (double) y / rows;
        int i = y * this.W + x;
        double f = this.fuel[i] + rate * falloff;
        this.fuel[i] = (f > 1) ? 1 : f;
        double h = this.heat[i] + rate * falloff * .5;
        this.heat[i] = (h > SOURCE_TEMP) ? SOURCE_TEMP : h;
        this.velV[i] += lift * waver * gustLift * falloff;
        this.velU[i] += swirl * lateral * falloff;
      }
    }
  }

  /**
   * Burn fuel, heat what it burns, cool what has burned.
   *
   * Cooling is Stefan-Boltzmann -- proportional to the fourth power of
   * temperature -- which is why the flame has a hard top edge rather than fading
   * out linearly: hot gas sheds heat violently until it is merely warm, then
   * lingers.
   */
  private void combust(double dt) {
    double burnFactor = Math.pow(lerp(.9, .001, this.burn.getValue()), dt);
    double coolRate = lerp(.1, 6.5, this.cooling.getValue()) * dt;
    double smokeAmount = this.smoke.getValue();
    double sootRate = smokeAmount * smokeAmount * 4;

    for (int i = 0; i < this.cells; ++i) {
      double f = this.fuel[i];
      double h = this.heat[i];

      if (f > .001) {
        double burned = f * (1 - burnFactor);
        this.fuel[i] = f - burned;
        double lit = f * BURN_TEMP;
        if (lit > h) {
          h = lit;
        }
        double s = this.soot[i] + burned * sootRate;
        this.soot[i] = (s > 1.5) ? 1.5 : s;
      } else if (f != 0) {
        this.fuel[i] = 0;
      }

      if (h > .0005) {
        double h2 = h * h;
        h -= coolRate * h2 * h2;
        this.heat[i] = (h > 0) ? h : 0;
      } else if (h != 0) {
        this.heat[i] = 0;
      }
    }
  }

  /**
   * Vorticity confinement.
   *
   * Advection is diffusive: every step rounds off the small eddies, and a fire
   * without them is a plume of hot fog. This measures where curl is concentrated,
   * points a unit vector up that gradient, and pushes along it -- feeding the
   * eddies the grid is eating.
   */
  private void applyVorticity(double dt) {
    double vort = this.vorticity.getValue();
    double epsilon = vort * vort * 22;
    if (epsilon <= 0) {
      return;
    }

    for (int y = 0; y < this.H; ++y) {
      for (int x = 0; x < this.W; ++x) {
        int i = y * this.W + x;
        double l = (x > 0) ? this.velV[i - 1] : this.velV[i];
        double r = (x < this.W1) ? this.velV[i + 1] : this.velV[i];
        double b = (y > 0) ? this.velU[i - this.W] : this.velU[i];
        double t = (y < this.H1) ? this.velU[i + this.W] : this.velU[i];
        this.curl[i] = .5 * ((r - l) - (t - b));
      }
    }

    // Interior only: the gradient of |curl| needs a neighbor on both sides, and a
    // confinement force pointed into a wall does nothing useful anyway.
    for (int yy = 1; yy < this.H1; ++yy) {
      for (int xx = 1; xx < this.W1; ++xx) {
        int j = yy * this.W + xx;
        double gx = .5 * (Math.abs(this.curl[j + 1]) - Math.abs(this.curl[j - 1]));
        double gy = .5 * (Math.abs(this.curl[j + this.W]) - Math.abs(this.curl[j - this.W]));
        double mag = Math.sqrt(gx * gx + gy * gy);
        if (mag < 1e-5) {
          continue;
        }
        double w = this.curl[j] * epsilon * dt / mag;
        this.velU[j] += gy * w;
        this.velV[j] -= gx * w;
      }
    }
  }

  /** Buoyancy, wind and a divergence-free noise stir. */
  private void applyForces(double dt) {
    double buoy = this.buoyancy.getValue();
    double lift = buoy * buoy * 70 * dt;
    double windForce = (this.wind.getValue() - .5) * 2 * 18 * dt;
    double turb = this.turbulence.getValue();
    double stir = turb * turb * 30 * dt;

    if (stir > 0) {
      // Stir with the curl of a scalar noise field rather than with noise
      // directly: the curl of anything is divergence-free, so this adds swirl
      // that the pressure solve does not immediately undo. Sampled once per cell
      // into psi, then differenced, instead of four noise calls per cell.
      float z = (float) (this.simClock * .55);
      for (int y = 0; y < this.H; ++y) {
        for (int x = 0; x < this.W; ++x) {
          this.psi[y * this.W + x] = Noise.stb_perlin_noise3(
            (float) (x * TURB_SCALE), (float) (y * TURB_SCALE), z, 0, 0, 0);
        }
      }
    }

    for (int yy = 0; yy < this.H; ++yy) {
      for (int xx = 0; xx < this.W; ++xx) {
        int i = yy * this.W + xx;
        this.velV[i] += lift * this.heat[i];
        this.velU[i] += windForce;

        if (stir > 0) {
          double l = (xx > 0) ? this.psi[i - 1] : this.psi[i];
          double r = (xx < this.W1) ? this.psi[i + 1] : this.psi[i];
          double b = (yy > 0) ? this.psi[i - this.W] : this.psi[i];
          double t = (yy < this.H1) ? this.psi[i + this.W] : this.psi[i];
          this.velU[i] += stir * .5 * (t - b);
          this.velV[i] -= stir * .5 * (r - l);
        }
      }
    }
  }

  /**
   * Pressure projection: make the velocity field divergence-free.
   *
   * Solid walls take a Neumann condition -- the ghost cell copies its neighbor,
   * so pressure has no gradient across the wall and no flow crosses it. An open
   * top takes a Dirichlet condition instead: the ghost is the negation of its
   * neighbor, which puts zero pressure on the boundary face and lets the plume
   * leave. Pressure is warm-started from the previous step, which is worth several
   * Jacobi iterations for free.
   */
  private void project() {
    boolean openTop = !this.lid.isOn();

    for (int y = 0; y < this.H; ++y) {
      for (int x = 0; x < this.W; ++x) {
        int i = y * this.W + x;
        double l = (x > 0) ? this.velU[i - 1] : -this.velU[i];
        double r = (x < this.W1) ? this.velU[i + 1] : -this.velU[i];
        double b = (y > 0) ? this.velV[i - this.W] : -this.velV[i];
        double t = (y < this.H1) ? this.velV[i + this.W]
          : (openTop ? this.velV[i] : -this.velV[i]);
        this.divergence[i] = .5 * ((r - l) + (t - b));
        this.pressure[i] *= .9;
      }
    }

    int iterations = this.solver.getValuei();
    for (int k = 0; k < iterations; ++k) {
      for (int yy = 0; yy < this.H; ++yy) {
        for (int xx = 0; xx < this.W; ++xx) {
          int j = yy * this.W + xx;
          double pc = this.pressure[j];
          double pl = (xx > 0) ? this.pressure[j - 1] : pc;
          double pr = (xx < this.W1) ? this.pressure[j + 1] : pc;
          double pb = (yy > 0) ? this.pressure[j - this.W] : pc;
          double pt = (yy < this.H1) ? this.pressure[j + this.W] : (openTop ? -pc : pc);
          this.pressure2[j] = (pl + pr + pb + pt - this.divergence[j]) * .25;
        }
      }
      double[] swap = this.pressure;
      this.pressure = this.pressure2;
      this.pressure2 = swap;
    }

    for (int y3 = 0; y3 < this.H; ++y3) {
      for (int x3 = 0; x3 < this.W; ++x3) {
        int m = y3 * this.W + x3;
        double pm = this.pressure[m];
        double gl = (x3 > 0) ? this.pressure[m - 1] : pm;
        double gr = (x3 < this.W1) ? this.pressure[m + 1] : pm;
        double gb = (y3 > 0) ? this.pressure[m - this.W] : pm;
        double gt = (y3 < this.H1) ? this.pressure[m + this.W] : (openTop ? -pm : pm);
        this.velU[m] -= .5 * (gr - gl);
        this.velV[m] -= .5 * (gt - gb);
      }
    }

    // The walls themselves: no flow through them, whatever the solve left behind.
    for (int y4 = 0; y4 < this.H; ++y4) {
      this.velU[y4 * this.W] = 0;
      this.velU[y4 * this.W + this.W1] = 0;
    }
    for (int x4 = 0; x4 < this.W; ++x4) {
      this.velV[x4] = 0;
      if (!openTop) {
        this.velV[this.H1 * this.W + x4] = 0;
      }
    }
  }

  // ---------------------------------------------------------- blackbody LUT
  //
  // Temperature to color, by way of Kelvin. The chromaticity is the usual
  // piecewise fit to the blackbody locus -- cheap, and accurate enough over
  // 1000-12000K that nobody has ever won an argument about it -- and brightness
  // is folded into the same table, so shading an LED is a table lookup and
  // nothing else. Rebuilt every frame because it is 256 entries and a knob that
  // lies about its own value for one frame is a worse bug than this is a cost.

  private void buildColorTable() {
    // Geometric rather than linear in Kelvin, because color temperature is: the
    // ember-to-candle end of the range lives in a few hundred degrees while the
    // white-to-blue end takes thousands. Spread linearly, the whole knob above a
    // quarter turn is daylight and every setting looks the same.
    double kMin = 500 * Math.pow(8, this.coolK.getValue());
    double kMax = 1000 * Math.pow(12, this.hotK.getValue());
    if (kMax < kMin) {
      kMax = kMin;
    }
    // Low Falloff pushes brightness into the cool gas and fattens the flame; high
    // Falloff keeps only the core lit. The physical answer is the fourth power of
    // temperature, which on an 8-bit rig leaves the whole plume at black -- the
    // default sits near linear instead.
    double gamma = lerp(.35, 3.2, this.falloff.getValue());
    double scale = this.level.getValue() * 255;

    for (int i = 0; i < LUT_SIZE; ++i) {
      double t = (double) i / (LUT_SIZE - 1);
      double kelvin = lerp(kMin, kMax, t) / 100;
      double r, g, b;

      if (kelvin <= 66) {
        r = 255;
        g = 99.4708025861 * Math.log(kelvin) - 161.1195681661;
        b = (kelvin <= 19) ? 0 : 138.5177312231 * Math.log(kelvin - 10) - 305.0447927307;
      } else {
        r = 329.698727446 * Math.pow(kelvin - 60, -.1332047592);
        g = 288.1221695283 * Math.pow(kelvin - 60, -.0755148492);
        b = 255;
      }

      double brightness = Math.pow(t, gamma) * scale / 255;
      this.lutR[i] = channel(r) * brightness;
      this.lutG[i] = channel(g) * brightness;
      this.lutB[i] = channel(b) * brightness;
    }
  }

  private static double channel(double value) {
    return (value < 0) ? 0 : (value > 255) ? 255 : value;
  }

  // ----------------------------------------------------------------- rendering

  private void draw() {
    for (LXPoint p : this.model.points) {
      double gx = p.xn * this.W1;
      double gy = p.yn * this.H1;

      double t = sampleField(this.heat, gx, gy) / BURN_TEMP;
      if (t > 1) {
        t = 1;
      } else if (!(t > 0)) {
        t = 0;
      }
      int index = (int) (t * (LUT_SIZE - 1));

      double s = sampleField(this.soot, gx, gy);
      if (s > 1) {
        s = 1;
      } else if (!(s > 0)) {
        s = 0;
      }

      // Soot is thickest exactly where the fire is brightest, so occluding by
      // density alone puts a hole in the flame. Weighting by how cold the gas is
      // instead lets the plume darken and catch its own light above the flame,
      // while the burning core stays the brightest thing on the model.
      double alpha = s * (1 - t);
      double occlusion = 1 - alpha * SOOT_OCCLUSION;
      double glow = alpha * this.glowScale;

      double r = this.lutR[index] * occlusion + glow;
      double g = this.lutG[index] * occlusion + glow;
      double b = this.lutB[index] * occlusion + glow;

      this.colors[p.index] = LXColor.rgb(
        (r > 255) ? 255 : (int) r,
        (g > 255) ? 255 : (int) g,
        (b > 255) ? 255 : (int) b);
    }
  }

  private double randomRange(double lo, double hi) {
    return lo + this.random.nextDouble() * (hi - lo);
  }

  private static double clamp(double v, double min, double max) {
    return (v < min) ? min : (v > max) ? max : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
