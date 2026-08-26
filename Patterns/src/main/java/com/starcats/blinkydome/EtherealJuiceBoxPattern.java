package com.starcats.blinkydome;

import java.util.Random;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Ethereal Juice Box — a 40x40 closed-box fluid carrying short-lived fluorescent
 * particles. Ported from Scripts/EtherealJuiceBox.js.
 *
 * Two solid agents live in the box and stamp white dye. Each transfers the
 * rigid-body velocity of its translated and rotated form into every cell it
 * sweeps, and each has an alternate spinning form:
 *
 * <pre>
 *   Disc  K1/K2   T1 blows outward.  T2 becomes five orbiting dots.
 *   Sink  K3/K4   T3 sucks inward.   T4 becomes three orbiting arcs.
 * </pre>
 *
 * The sink is drawn as a spinning X; its alternate form is the three arcs.
 *
 * Two more controls fire between opposing nodes on the box edge, each pair
 * placed by its own angle knob:
 *
 * <pre>
 *   Sweep K5      T5 drives a pair of converging or diverging pressure fronts.
 *   Bolt  K6      T6 lays a jagged lightning bolt clear across the box.
 * </pre>
 *
 * The six buttons are the gestural row. Two of them are one-shots on the press
 * edge, two do work for as long as they are held, and two are both:
 *
 * <pre>
 *   B1  press: a downward slam off the top edge.  held: a steady noisy downwash.
 *   B2  press: a swirl slam, reversing every press. held: a slow curl the same way.
 *   B3  press: the whole box implodes, laying source and particles at the walls.
 *   B4  press: every cell shoved in its own random direction, once.
 *   B5  held:  the Kaleidoscope Postprocess effect, at a symmetry rolled on press.
 *   B6  held:  viscosity and decay smeared toward loose.
 * </pre>
 *
 * B5 is the one control here that does nothing to the fluid: it is read by
 * {@link ClickyBinding}, which owns the effect sitting on this pattern. T7 is
 * likewise inert in the simulation — it is the console's master switch, and the
 * binding fades the WF-CTRL and WF-CLEAR channels with it.
 *
 * The K/T pairing is the layout of the physical clicky console: pot N is knob KN
 * and pulling that same pot out is TN. The {@link ClickyConsole} modulator drives
 * all of it over the network, but nothing here depends on the hardware being
 * present.
 *
 * <h2>On the port</h2>
 *
 * Behavior is intended to be identical to the script's, control for control and
 * constant for constant, so the two can be swapped without retuning a show. Three
 * things are done differently because Java allows it and Javascript did not:
 *
 * <ul>
 * <li><b>Parameter paths are unchanged.</b> {@link ClickyBinding} finds its
 * target by the shape of its controls, so <code>k1</code>..<code>k6</code>,
 * <code>t1</code>..<code>t7</code>, <code>b1</code>..<code>b6</code> and every
 * settings knob keep the names the script gave them. The console binds to this
 * pattern with no change on that side. Only one pattern carrying this control set
 * should be loaded at a time — the binding takes the first it finds in mixer
 * order, so leaving the script version in the project as well is ambiguous.
 * <li><b>Particles are a struct of arrays</b> rather than a list of objects, and
 * the pool is allocated once at full size. Nothing in the render loop allocates.
 * <li><b>Fields are <code>double[]</code></b>, where the script stored
 * <code>float[]</code> and computed in double. Storing at the precision the
 * arithmetic already ran at removes a narrowing conversion per cell per pass.
 * </ul>
 *
 * The remaining shape is a deliberately literal translation — same order of
 * operations, same constants, same one-substep impulse magnitudes — so a
 * behavioral difference between the two is a bug in this file rather than a
 * design decision made along the way.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Ethereal Juice Box")
@LXComponent.Description("Closed-box fluid carrying fluorescent particles, driven by the clicky console")
public class EtherealJuiceBoxPattern extends LXPattern {

  private static final int GRID_SIZE = 40;
  private static final int W = GRID_SIZE;
  private static final int H = GRID_SIZE;
  private static final int W1 = W - 1;
  private static final int H1 = H - 1;
  private static final int CELLS = W * H;

  private static final double SIM_HZ = 60;
  private static final int MAX_SUBSTEPS = 4;
  private static final int DIFFUSION_ITERATIONS = 16;
  private static final int PRESSURE_ITERATIONS = 24;
  private static final double MAX_VORTICITY_CONFINEMENT = 36;
  private static final int MAX_PARTICLES = 2048;
  private static final double TAU = Math.PI * 2;

  // B1. A slam off the top edge on the press, then a downwash over the whole box
  // for as long as it is held. The noise is one-sided -- it varies how hard each
  // cell is pushed down and scatters it sideways, but never turns it upward -- so
  // a held B1 always reads as one direction rather than as agitation.
  private static final int B1_SLAM_BAND = 4;
  private static final double B1_SLAM_IMPULSE = 640;
  private static final double B1_DOWNWASH_FORCE = 10;
  private static final double B1_DOWNWASH_NOISE = .5;
  private static final double B1_DOWNWASH_SPREAD = .6;

  // B2. A swirl slam on the press and a slow curl while held, both about the box
  // center. Inflow is what makes it read as a spiral rather than a turntable; it
  // stays inward whichever way the swirl is turning. The strength peaks partway
  // out and eases to nothing before the walls, so the no-slip boundary is not
  // fighting a hard tangential edge.
  private static final double B2_SLAM_FORCE = 520;
  private static final double B2_CURL_FORCE = 5.2;
  private static final double B2_SPIRAL_INFLOW = .35;
  private static final double B2_SPIRAL_EDGE = .5;

  // B3. The whole box collapses on its own center at once, with a band of source
  // around the walls and a handful of particles riding in on it. A one-shot, so
  // the impulse is a single-substep value and much larger than anything applied
  // every step.
  //
  // The pull ramps in from nothing at the center rather than being uniform, which
  // is what keeps the middle from being a point every cell is crushed into at the
  // same speed. Past the ramp distance it is flat, so the corners -- which are
  // further out than 0.5 -- pull no harder than the wall midpoints do.
  private static final double B3_IMPLOSION_IMPULSE = 900;
  private static final double B3_IMPLOSION_RAMP = .5;
  private static final int B3_EDGE_BAND = 2;
  private static final double B3_SOURCE_LEVEL = .6;
  private static final int B3_PARTICLES = 15;
  private static final double B3_PARTICLE_SPEED = 30;

  // B4. One shatter per press: every interior cell in its own direction, once.
  // A flat amplitude rather than one scaled by the timestep, because this is a
  // single kick and not a noise process being integrated over a duration.
  private static final double B4_JOLT_IMPULSE = 700;

  // B6 loosens the fluid while it is held. The rest targets are the Viscosity
  // and Decay Rate knobs, which default to the values it returns to.
  private static final double B6_TWEEN_SECONDS = .5;
  private static final double B6_HELD_VISCOSITY = .4;
  private static final double B6_HELD_DECAY = .4;

  // Agent forms. All extents are multiples of the shared agent radius, so the
  // Agent Radius knob scales every form coherently.
  private static final int AGENT_DISC = 0;
  private static final int AGENT_SINK = 1;
  private static final int SATELLITE_COUNT = 5;
  private static final double SATELLITE_ORBIT = 1.35;
  private static final double SATELLITE_RADIUS = .32;
  private static final double X_ARM = 1;
  private static final double X_HALF_WIDTH = .22;
  private static final int ARC_COUNT = 3;
  private static final double ARC_RADIUS = 1.25;
  private static final double ARC_HALF_WIDTH = .25;
  private static final double ARC_FILL = .5;

  // T1 and T3 radial fields. A radial field is pure divergence, so both are
  // applied after the pressure projection; see the note in simulate().
  private static final double RADIAL_REACH = 3;
  private static final double RADIAL_STRENGTH = 40;
  private static final double T1_PUSH_MULTIPLIER = .5;
  private static final double T3_PULL_MULTIPLIER = 2;
  private static final int T1_TRANSITION_PARTICLES = 20;
  private static final double T1_TRANSITION_IMPULSE = 1;

  // The two edge node pairs.
  private static final double NODE_RADIUS = .03;
  private static final double NODE_LEVEL = 1;

  // T5 sweep.
  private static final double SWEEP_SECONDS = .2;
  private static final double SWEEP_FRONT_HALF_WIDTH = 1.15;
  private static final double SWEEP_FORCE = 200;

  // T6 bolt.
  private static final int BOLT_SEGMENTS = 5;
  private static final double BOLT_JAGGEDNESS = .22;
  private static final double BOLT_DYE_RADIUS = 1.1;
  private static final double BOLT_INTENSITY = 4;
  private static final int BOLT_PARTICLES = 15;
  private static final double BOLT_PARTICLE_SPEED = 26;
  private static final double BOLT_IMPULSE = 260;
  private static final double BOLT_IMPULSE_RADIUS = 2.2;

  // ------------------------------------------------------------------ controls

  public final CompoundParameter viscosity =
    new CompoundParameter("Viscosity", .9, 0, 1)
    .setDescription("Velocity diffusion; 0 is fluid, 1 is thick and smooth. B6 tweens away from this and back to it");

  public final CompoundParameter turbulence =
    new CompoundParameter("Turbulence", .5, 0, 1)
    .setDescription("Restore fluid curls lost to interpolation; 0 is smooth");

  public final CompoundParameter particleRate =
    new CompoundParameter("Particle Rate", 1, 0, 1)
    .setDescription("Ambient particles emitted per second");

  public final CompoundParameter particleLifespan =
    new CompoundParameter("Particle Lifespan", 0, 0, 1)
    .setDescription("Particle lifetime from 1 to 10 seconds; takes effect on newly emitted particles only");

  public final CompoundParameter decayRate =
    new CompoundParameter("Decay Rate", .85, 0, 1)
    .setDescription("How quickly emitted source material dims to zero. B6 tweens away from this and back to it");

  public final CompoundParameter gammaCorrection =
    new CompoundParameter("Gamma Correction", .25, 0, 1)
    .setDescription("Shape the source-value to output-brightness curve; 50% is neutral");

  public final CompoundParameter particleAmplitude =
    new CompoundParameter("Particle Amplitude", .3, 0, 1)
    .setDescription("Particle source emission multiplier from 0.5x to 10x");

  public final CompoundParameter agentRadius =
    new CompoundParameter("Agent Radius", .5, 0, 1)
    .setDescription("Fixed radius shared by the disc and the sink");

  public final CompoundParameter spinRate =
    new CompoundParameter("Spin Rate", .4, 0, 1)
    .setDescription("How fast the orbiting dots and arcs rotate");

  // The button row. Every one of these is the held state of a console button: on
  // means it is down, off means it is up. Some read the press edge out of that,
  // some do work every step they are on, and B1 and B2 do both.
  //
  // These latch rather than being momentary because a trigger control cannot be
  // held -- it fires its listeners and puts itself straight back to false, so a
  // reader always reads false and "while held" can never work. Nothing is lost on
  // a short tap: the console holds a press for a minimum duration before it will
  // report the release, so a tap between two frames still lands.

  public final BooleanParameter b1 =
    new BooleanParameter("B1", false)
    .setDescription("Press slams the fluid down off the top edge; holding keeps a noisy downwash running");

  public final BooleanParameter b2 =
    new BooleanParameter("B2", false)
    .setDescription("Press slams a swirl in, reversing every press; holding keeps a slow curl the same way");

  public final BooleanParameter b3 =
    new BooleanParameter("B3", false)
    .setDescription("Press collapses the whole box on its center, with a band of source and a few particles at the walls");

  public final BooleanParameter b4 =
    new BooleanParameter("B4", false)
    .setDescription("Press shatters the box, jolting every cell in a random direction, leaving the source untouched");

  public final BooleanParameter b5 =
    new BooleanParameter("B5", false)
    .setDescription("While on, fold the frame through the Kaleidoscope Postprocess effect on this pattern");

  public final BooleanParameter b6 =
    new BooleanParameter("B6", false)
    .setDescription("While on, smear viscosity and decay toward loose; half a second out and half a second back");

  // Latched switches. T5 and T6 act on the flip rather than on the position, so
  // both directions do something and neither has a resting state that is "off".

  public final BooleanParameter t1 =
    new BooleanParameter("T1", false)
    .setDescription("Disc blows the fluid outward; either flip also throws a burst off its surface");

  public final BooleanParameter t2 =
    new BooleanParameter("T2", false)
    .setDescription("Disc becomes five dots orbiting just outside its radius");

  public final BooleanParameter t3 =
    new BooleanParameter("T3", false)
    .setDescription("Sink sucks the fluid inward like a vacuum");

  public final BooleanParameter t4 =
    new BooleanParameter("T4", false)
    .setDescription("Sink becomes three arc segments, whose interior stays clear under suction");

  public final BooleanParameter t5 =
    new BooleanParameter("T5", false)
    .setDescription("Flip out to sweep the box inward, flip in to sweep it back outward");

  public final BooleanParameter t6 =
    new BooleanParameter("T6", false)
    .setDescription("Either flip fires a lightning bolt between the two edge nodes");

  /**
   * The console's one latching switch, and the only control here that is about
   * the installation rather than about the fluid. Nothing in the simulation reads
   * it: {@link ClickyBinding} does, and rides the WF-CTRL and WF-CLEAR faders
   * with it.
   */
  public final BooleanParameter t7 =
    new BooleanParameter("T7", false)
    .setDescription("Master switch. Off fades the waterfall channels out; nothing in the simulation reads it");

  // K1/K2 and K3/K4 place the two agents. K5 and K6 rotate the two edge node
  // pairs over a full turn. Each pair is opposed, so the second half of a knob
  // repeats the first with the two nodes swapped.

  public final CompoundParameter k1 =
    new CompoundParameter("K1", .5, 0, 1).setDescription("Disc X position");

  public final CompoundParameter k2 =
    new CompoundParameter("K2", .5, 0, 1).setDescription("Disc Y position");

  public final CompoundParameter k3 =
    new CompoundParameter("K3", .5, 0, 1).setDescription("Sink X position");

  public final CompoundParameter k4 =
    new CompoundParameter("K4", .5, 0, 1).setDescription("Sink Y position");

  public final CompoundParameter k5 =
    new CompoundParameter("K5", .25, 0, 1).setDescription("Sweep node angle, full turn");

  /** Defaulted a quarter turn from K5 so the two node pairs do not sit on top of each other on load. */
  public final CompoundParameter k6 =
    new CompoundParameter("K6", 0, 0, 1).setDescription("Bolt node angle, full turn");

  // -------------------------------------------------------------------- state

  private double[] velU = new double[CELLS];
  private double[] velV = new double[CELLS];
  private double[] velU2 = new double[CELLS];
  private double[] velV2 = new double[CELLS];
  private final double[] diffusionU = new double[CELLS];
  private final double[] diffusionV = new double[CELLS];
  private double[] dye = new double[CELLS];
  private double[] dye2 = new double[CELLS];
  private double[] pressure = new double[CELLS];
  private double[] pressure2 = new double[CELLS];
  private final double[] divergence = new double[CELLS];
  private final double[] curl = new double[CELLS];

  /**
   * The particle pool, as a struct of arrays.
   *
   * Allocated once at full size and packed: live particles are always
   * <code>[0, particleCount)</code>, and a death is served by moving the last one
   * into the hole. Nothing reads the pool in order, so that reordering costs
   * nothing and it keeps both removal and the emission cap at constant time.
   */
  private final double[] particleX = new double[MAX_PARTICLES];
  private final double[] particleY = new double[MAX_PARTICLES];
  private final double[] particleAge = new double[MAX_PARTICLES];
  private final double[] particleLife = new double[MAX_PARTICLES];
  private final double[] particleLaunchU = new double[MAX_PARTICLES];
  private final double[] particleLaunchV = new double[MAX_PARTICLES];
  private int particleCount = 0;

  private double ambientEmissionAccumulator = 0;
  private double simAccumulator = 0;
  private double renderGamma = 1;

  private final Agent[] agents = { new Agent(AGENT_DISC), new Agent(AGENT_SINK) };
  private boolean agentsInitialized = false;

  // Everything that acts on a flip rather than on a position keeps its previous
  // value here and recovers the edge from the difference. They are baselined on
  // the first simulated step rather than in the constructor, so a project loaded
  // with a switch already flipped does not read as a flip and fire on frame one.
  private boolean togglesBaselined = false;
  private boolean thrustPrevious = false;
  private boolean sweepPrevious = false;
  private boolean boltPrevious = false;
  private boolean b1Previous = false;
  private boolean b2Previous = false;
  private boolean b3Previous = false;
  private boolean b4Previous = false;

  private boolean sweepActive = false;
  private double sweepAxis = 0;
  private boolean sweepOutward = false;
  private double sweepAge = 0;

  private boolean boltPending = false;
  private final double[] boltX = new double[BOLT_SEGMENTS + 1];
  private final double[] boltY = new double[BOLT_SEGMENTS + 1];

  /**
   * Which way B2 is turning. Flipped on every press, so mashing the button beats
   * the box back and forth rather than winding it further the same way.
   */
  private double swirlDirection = 1;

  /**
   * B3's band of source, held over to the stamping phase for the same reason the
   * bolt is: laid down at press time it would be advected away by its own impulse.
   */
  private boolean pendingEdgeSource = false;

  /** 0 at the knob values, 1 at B6's held values. */
  private double slipEnvelope = 0;

  private final Random random = new Random();

  /**
   * Scratch for the handful of routines that would otherwise return a small
   * struct. Every caller copies these into locals on the line after the call, so
   * the next writer cannot clobber a value still in use.
   */
  private double pointX = 0;
  private double pointY = 0;
  private double sampleNX = 0;
  private double sampleNY = 0;

  /** One agent, in one of its two forms. */
  private static final class Agent {
    final int kind;
    double x = .5;
    double y = .5;
    double prevX = .5;
    double prevY = .5;
    double spin = 0;
    double prevSpin = 0;
    boolean alt = false;
    boolean prevAlt = false;

    Agent(int kind) {
      this.kind = kind;
    }
  }

  public EtherealJuiceBoxPattern(LX lx) {
    super(lx);
    addParameter("viscosity", this.viscosity);
    addParameter("turbulence", this.turbulence);
    addParameter("particleRate", this.particleRate);
    addParameter("particleLifespan", this.particleLifespan);
    addParameter("decayRate", this.decayRate);
    addParameter("gammaCorrection", this.gammaCorrection);
    addParameter("particleAmplitude", this.particleAmplitude);
    addParameter("agentRadius", this.agentRadius);
    addParameter("spinRate", this.spinRate);
    addParameter("b1", this.b1);
    addParameter("b2", this.b2);
    addParameter("b3", this.b3);
    addParameter("b4", this.b4);
    addParameter("b5", this.b5);
    addParameter("b6", this.b6);
    addParameter("t1", this.t1);
    addParameter("t2", this.t2);
    addParameter("t3", this.t3);
    addParameter("t4", this.t4);
    addParameter("t5", this.t5);
    addParameter("t6", this.t6);
    addParameter("t7", this.t7);
    addParameter("k1", this.k1);
    addParameter("k2", this.k2);
    addParameter("k3", this.k3);
    addParameter("k4", this.k4);
    addParameter("k5", this.k5);
    addParameter("k6", this.k6);
  }

  // ---------------------------------------------------------------- the agents

  /** The one radius every agent form is built from. */
  private double agentRadiusValue() {
    return .04 + .11 * clamp(this.agentRadius.getValue(), 0, 1);
  }

  private double spinRateRadians() {
    return 3 * clamp(this.spinRate.getValue(), 0, 1);
  }

  /** How far an agent's form can reach from its own center. */
  private double agentExtent(Agent agent, double radius) {
    if (agent.alt) {
      return (agent.kind == AGENT_DISC)
        ? radius * (SATELLITE_ORBIT + SATELLITE_RADIUS)
        : radius * (ARC_RADIUS + ARC_HALF_WIDTH);
    }
    return (agent.kind == AGENT_DISC) ? radius : radius * (X_ARM + X_HALF_WIDTH);
  }

  /**
   * Whether a point lies inside an agent's solid form.
   *
   * The center and spin are passed in rather than read off the agent so the
   * swept-path test can walk the form back along the motion it made this step.
   * Both alternate forms are rotationally symmetric, so the point is folded onto
   * its nearest arm and one arm is tested instead of all of them.
   */
  private boolean agentContainsAt(Agent agent, double px, double py,
      double cx, double cy, double spin, double radius) {
    double dx = px - cx;
    double dy = py - cy;
    double distanceSquared = dx * dx + dy * dy;

    if (!agent.alt) {
      if (agent.kind == AGENT_DISC) {
        return distanceSquared <= radius * radius;
      }
      // An X, which is a plus sign in a frame turned an eighth of a turn. Two
      // trig calls beat folding here, and the crossing point stays solid.
      double turn = spin + Math.PI / 4;
      double turnCos = Math.cos(turn);
      double turnSin = Math.sin(turn);
      double barX = Math.abs(turnCos * dx + turnSin * dy);
      double barY = Math.abs(turnCos * dy - turnSin * dx);
      double barHalf = X_HALF_WIDTH * radius;
      double barArm = X_ARM * radius;
      return (barY <= barHalf && barX <= barArm) || (barX <= barHalf && barY <= barArm);
    }

    double distance = Math.sqrt(distanceSquared);
    double sector = TAU / ((agent.kind == AGENT_DISC) ? SATELLITE_COUNT : ARC_COUNT);
    double offset = Math.atan2(dy, dx) - spin;
    offset -= Math.round(offset / sector) * sector;

    if (agent.kind == AGENT_DISC) {
      double orbit = SATELLITE_ORBIT * radius;
      double dotRadius = SATELLITE_RADIUS * radius;
      // Law of cosines from the folded point to that arm's dot center.
      double gap = distanceSquared + orbit * orbit
        - 2 * distance * orbit * Math.cos(offset);
      return gap <= dotRadius * dotRadius;
    }

    // A band at the arc radius, cut into ARC_COUNT pieces. ARC_FILL is how much
    // of each sector is arc; the remainder is the gap between one arc and the
    // next, which is what keeps the three segments reading as separate.
    if (Math.abs(distance - ARC_RADIUS * radius) > ARC_HALF_WIDTH * radius) {
      return false;
    }
    return Math.abs(offset) <= ARC_FILL * sector * .5;
  }

  /** The heading of the X's first arm. */
  private double spinOffsetX(Agent agent) {
    return agent.spin + Math.PI / 4;
  }

  /**
   * A point on an agent's outer surface with its outward normal, p in [0,1).
   * Left in {@link #pointX}/{@link #pointY} and {@link #sampleNX}/{@link #sampleNY}.
   */
  private void agentSurfaceSample(Agent agent, double p, double radius) {
    p = wrap01(p);

    if (!agent.alt) {
      if (agent.kind == AGENT_DISC) {
        double theta = TAU * p;
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        this.pointX = agent.x + radius * c;
        this.pointY = agent.y + radius * s;
        this.sampleNX = c;
        this.sampleNY = s;
        return;
      }
      // Off the tip of one of the X's four arms.
      double tipAngle = spinOffsetX(agent) + Math.floor(p * 4) * (Math.PI / 2);
      double tipCos = Math.cos(tipAngle);
      double tipSin = Math.sin(tipAngle);
      double reach = X_ARM * radius;
      this.pointX = agent.x + reach * tipCos;
      this.pointY = agent.y + reach * tipSin;
      this.sampleNX = tipCos;
      this.sampleNY = tipSin;
      return;
    }

    if (agent.kind == AGENT_SINK) {
      // Somewhere along the outer edge of one of the three arcs.
      double arcSector = TAU / ARC_COUNT;
      double along = (wrap01(p * ARC_COUNT) - .5) * ARC_FILL * arcSector;
      double arcAngle = agent.spin + Math.floor(p * ARC_COUNT) * arcSector + along;
      double arcCos = Math.cos(arcAngle);
      double arcSin = Math.sin(arcAngle);
      double edge = (ARC_RADIUS + ARC_HALF_WIDTH) * radius;
      this.pointX = agent.x + edge * arcCos;
      this.pointY = agent.y + edge * arcSin;
      this.sampleNX = arcCos;
      this.sampleNY = arcSin;
      return;
    }

    double armAngle = agent.spin + Math.floor(p * SATELLITE_COUNT) * (TAU / SATELLITE_COUNT);
    // The fractional part within the chosen arm doubles as the angle around that
    // arm's dot, so one random number places the sample completely.
    double local = TAU * wrap01(p * SATELLITE_COUNT);
    double localCos = Math.cos(local);
    double localSin = Math.sin(local);
    double orbit = SATELLITE_ORBIT * radius;
    double dotRadius = SATELLITE_RADIUS * radius;
    this.pointX = agent.x + orbit * Math.cos(armAngle) + dotRadius * localCos;
    this.pointY = agent.y + orbit * Math.sin(armAngle) + dotRadius * localSin;
    this.sampleNX = localCos;
    this.sampleNY = localSin;
  }

  private void updateAgents(double dt) {
    double spinDelta = spinRateRadians() * dt;
    for (Agent agent : this.agents) {
      agent.prevX = agent.x;
      agent.prevY = agent.y;
      agent.prevSpin = agent.spin;
      agent.prevAlt = agent.alt;
      agent.spin = wrapAngle(agent.spin + spinDelta);
    }

    this.agents[0].x = clamp(this.k1.getValue(), 0, 1);
    this.agents[0].y = clamp(this.k2.getValue(), 0, 1);
    this.agents[0].alt = this.t2.isOn();
    this.agents[1].x = clamp(this.k3.getValue(), 0, 1);
    this.agents[1].y = clamp(this.k4.getValue(), 0, 1);
    this.agents[1].alt = this.t4.isOn();

    // Initial placement and a discrete form change do not create a spurious
    // full-box sweep. Translation and spin begin on the following step.
    for (Agent agent : this.agents) {
      if (!this.agentsInitialized || (agent.alt != agent.prevAlt)) {
        agent.prevX = agent.x;
        agent.prevY = agent.y;
        agent.prevSpin = agent.spin;
        agent.prevAlt = agent.alt;
      }
    }
    this.agentsInitialized = true;
  }

  /** Transfer rigid translation and rotation into every cell an agent sweeps. */
  private void applyAgentSweep(Agent agent, double dt, double radius) {
    double dx = agent.x - agent.prevX;
    double dy = agent.y - agent.prevY;
    double da = shortestAngleDifference(agent.spin, agent.prevSpin);
    if (Math.abs(dx) + Math.abs(dy) + Math.abs(da) < 1e-9) {
      return;
    }

    double extent = agentExtent(agent, radius);
    double travelCells = Math.sqrt(dx * dx + dy * dy) * Math.max(W1, H1);
    double rotationalCells = Math.abs(da) * extent * Math.max(W1, H1);
    int samples = (int) Math.max(1,
      Math.min(96, Math.ceil(Math.max(travelCells, rotationalCells) * 2)));
    double translationU = dx * W1 / dt;
    double translationV = dy * H1 / dt;
    double angularVelocity = da / dt;
    double coupling = .72;

    // Only the band the form can possibly have touched this step is scanned.
    int minX = (int) Math.max(1, Math.floor((Math.min(agent.x, agent.prevX) - extent) * W1));
    int maxX = (int) Math.min(W1 - 1, Math.ceil((Math.max(agent.x, agent.prevX) + extent) * W1));
    int minY = (int) Math.max(1, Math.floor((Math.min(agent.y, agent.prevY) - extent) * H1));
    int maxY = (int) Math.min(H1 - 1, Math.ceil((Math.max(agent.y, agent.prevY) + extent) * H1));

    for (int y = minY; y <= maxY; ++y) {
      double yn = y / (double) H1;
      for (int x = minX; x <= maxX; ++x) {
        double xn = x / (double) W1;
        boolean hit = false;
        double hitCx = agent.x;
        double hitCy = agent.y;
        for (int sample = 0; sample <= samples; ++sample) {
          double amount = sample / (double) samples;
          double cx = agent.prevX + dx * amount;
          double cy = agent.prevY + dy * amount;
          double spin = agent.prevSpin + da * amount;
          if (agentContainsAt(agent, xn, yn, cx, cy, spin, radius)) {
            hit = true;
            hitCx = cx;
            hitCy = cy;
            break;
          }
        }
        if (!hit) {
          continue;
        }

        double rigidU = translationU - angularVelocity * (yn - hitCy) * W1;
        double rigidV = translationV + angularVelocity * (xn - hitCx) * H1;
        int i = y * W + x;
        this.velU[i] += (rigidU - this.velU[i]) * coupling;
        this.velV[i] += (rigidV - this.velV[i]) * coupling;
      }
    }
  }

  /**
   * An agent is always a solid, maximum-value emitter. The sink's arc form is
   * additionally a container: while suction is running its interior is forced to
   * zero every step, which is the case that would otherwise pack the middle with
   * everything the vacuum has just dragged in. The void is bounded by the inner
   * edge of the arcs, so it reads as the shape containing it rather than as a
   * hole floating on its own.
   */
  private void stampAgent(Agent agent, double radius, boolean suction) {
    double extent = agentExtent(agent, radius);
    int minX = (int) Math.max(0, Math.floor((agent.x - extent) * W1));
    int maxX = (int) Math.min(W1, Math.ceil((agent.x + extent) * W1));
    int minY = (int) Math.max(0, Math.floor((agent.y - extent) * H1));
    int maxY = (int) Math.min(H1, Math.ceil((agent.y + extent) * H1));
    // Only the arc form encloses anything. The X is a solid figure with no
    // interior, so there is nothing to hold open when it is the one drawn.
    boolean hollow = (agent.kind == AGENT_SINK) && agent.alt && suction;
    double hollowRadius = radius * (ARC_RADIUS - ARC_HALF_WIDTH);

    for (int y = minY; y <= maxY; ++y) {
      double yn = y / (double) H1;
      for (int x = minX; x <= maxX; ++x) {
        double xn = x / (double) W1;
        int i = y * W + x;
        if (agentContainsAt(agent, xn, yn, agent.x, agent.y, agent.spin, radius)) {
          this.dye[i] = 1;
        } else if (hollow) {
          double dx = xn - agent.x;
          double dy = yn - agent.y;
          if (dx * dx + dy * dy < hollowRadius * hollowRadius) {
            this.dye[i] = 0;
          }
        }
      }
    }
  }

  /**
   * T1 and T3. Sign +1 blows the fluid away from the agent, -1 draws it in. The
   * falloff is linear rather than inverse-square so there is no singularity
   * sitting inside the solid form.
   */
  private void applyRadialField(Agent agent, double dt, double radius, double sign, double multiplier) {
    double reach = agentExtent(agent, radius) * RADIAL_REACH;
    int minX = (int) Math.max(1, Math.floor((agent.x - reach) * W1));
    int maxX = (int) Math.min(W1 - 1, Math.ceil((agent.x + reach) * W1));
    int minY = (int) Math.max(1, Math.floor((agent.y - reach) * H1));
    int maxY = (int) Math.min(H1 - 1, Math.ceil((agent.y + reach) * H1));
    double scale = sign * RADIAL_STRENGTH * multiplier * dt;

    for (int y = minY; y <= maxY; ++y) {
      double yn = y / (double) H1;
      for (int x = minX; x <= maxX; ++x) {
        double xn = x / (double) W1;
        double dx = xn - agent.x;
        double dy = yn - agent.y;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance >= reach) {
          continue;
        }
        double inverse = 1 / Math.max(distance, 1e-4);
        double falloff = (1 - distance / reach) * scale;
        int i = y * W + x;
        this.velU[i] += dx * inverse * falloff * W1;
        this.velV[i] += dy * inverse * falloff * H1;
      }
    }
  }

  // ------------------------------------------------------------- the edge nodes

  private double sweepAngle() {
    return clamp(this.k5.getValue(), 0, 1) * TAU;
  }

  private double boltAngle() {
    return clamp(this.k6.getValue(), 0, 1) * TAU;
  }

  /** Axial distance from the box center to the inset edge along this heading. */
  private double edgeReach(double angle, double inset) {
    double c = Math.abs(Math.cos(angle));
    double s = Math.abs(Math.sin(angle));
    double limit = .5 - inset;
    double byX = (c > 1e-6) ? limit / c : Double.POSITIVE_INFINITY;
    double byY = (s > 1e-6) ? limit / s : Double.POSITIVE_INFINITY;
    return Math.min(byX, byY);
  }

  /** Left in {@link #pointX}/{@link #pointY}. */
  private void edgeNodePosition(double angle, double inset) {
    double reach = edgeReach(angle, inset);
    this.pointX = .5 + Math.cos(angle) * reach;
    this.pointY = .5 + Math.sin(angle) * reach;
  }

  /** Mark a node pair so it is clear where the next sweep or bolt comes from. */
  private void stampNodePair(double angle, double level) {
    edgeNodePosition(angle, NODE_RADIUS);
    stampNodeDot(this.pointX, this.pointY, level);
    edgeNodePosition(angle + Math.PI, NODE_RADIUS);
    stampNodeDot(this.pointX, this.pointY, level);
  }

  private void stampNodeDot(double px, double py, double level) {
    double gx = px * W1;
    double gy = py * H1;
    double radiusCells = NODE_RADIUS * Math.max(W1, H1);
    int minX = (int) Math.max(0, Math.floor(gx - radiusCells));
    int maxX = (int) Math.min(W1, Math.ceil(gx + radiusCells));
    int minY = (int) Math.max(0, Math.floor(gy - radiusCells));
    int maxY = (int) Math.min(H1, Math.ceil(gy + radiusCells));

    for (int y = minY; y <= maxY; ++y) {
      for (int x = minX; x <= maxX; ++x) {
        double dx = x - gx;
        double dy = y - gy;
        if (dx * dx + dy * dy > radiusCells * radiusCells) {
          continue;
        }
        int i = y * W + x;
        if (level > this.dye[i]) {
          this.dye[i] = level;
        }
      }
    }
  }

  // ----------------------------------------------------------------- T5, sweep

  /**
   * Flipping T5 out converges a pair of fronts on the box center; flipping it in
   * drives them back to the edge. Only one sweep is ever in flight, so reversing
   * mid-travel abandons the old one on the spot rather than queueing behind it.
   */
  private void updateSweep(double dt) {
    boolean out = this.t5.isOn();
    if (out != this.sweepPrevious) {
      this.sweepPrevious = out;
      this.sweepActive = true;
      this.sweepAxis = sweepAngle();
      this.sweepOutward = out;
      this.sweepAge = 0;
    }
    if (!this.sweepActive) {
      return;
    }

    double previousTime = clamp(this.sweepAge / SWEEP_SECONDS, 0, 1);
    this.sweepAge = (this.sweepAge + dt >= SWEEP_SECONDS - 1e-9)
      ? SWEEP_SECONDS
      : this.sweepAge + dt;
    double time = clamp(this.sweepAge / SWEEP_SECONDS, 0, 1);

    // Quadratic, so both fronts leave slowly and arrive hard.
    double previousProgress = previousTime * previousTime;
    double progress = time * time;

    double reach = edgeReach(this.sweepAxis, NODE_RADIUS);
    double previousOffset = reach * (this.sweepOutward ? previousProgress : 1 - previousProgress);
    double currentOffset = reach * (this.sweepOutward ? progress : 1 - progress);
    double push = this.sweepOutward ? 1 : -1;

    applySweptFront(this.sweepAxis, previousOffset, currentOffset, push);
    applySweptFront(this.sweepAxis, -previousOffset, -currentOffset, -push);

    if (this.sweepAge >= SWEEP_SECONDS) {
      this.sweepActive = false;
    }
  }

  /**
   * Impulse every cell crossed by one planar front travelling along the sweep
   * axis. The swept interval between the previous and current frontier positions
   * is used rather than the current position alone, so a fast front cannot step
   * over a row of cells and leave a gap behind it.
   */
  private void applySweptFront(double angle, double previousOffset, double currentOffset, double push) {
    double axisCos = Math.cos(angle);
    double axisSin = Math.sin(angle);
    double low = Math.min(previousOffset, currentOffset);
    double high = Math.max(previousOffset, currentOffset);
    double halfWidth = SWEEP_FRONT_HALF_WIDTH / Math.max(W1, H1);
    double forceU = push * axisCos * SWEEP_FORCE;
    double forceV = push * axisSin * SWEEP_FORCE;

    for (int y = 1; y < H1; ++y) {
      double yn = y / (double) H1 - .5;
      for (int x = 1; x < W1; ++x) {
        double xn = x / (double) W1 - .5;
        double axial = xn * axisCos + yn * axisSin;
        double distance = (axial < low) ? low - axial : ((axial > high) ? axial - high : 0);
        if (distance >= halfWidth) {
          continue;
        }
        double falloff = 1 - distance / halfWidth;
        int i = y * W + x;
        this.velU[i] += forceU * falloff;
        this.velV[i] += forceV * falloff;
      }
    }
  }

  // ------------------------------------------------------------------ T6, bolt

  /**
   * Either flip of T6 fires. The strike is built and its impulses are applied
   * here, after the projection, but its dye is held over to the stamping phase so
   * the bolt is not advected away in the same step that draws it.
   */
  private void updateBolt() {
    boolean on = this.t6.isOn();
    if (on == this.boltPrevious) {
      return;
    }
    this.boltPrevious = on;

    double angle = boltAngle();
    edgeNodePosition(angle, NODE_RADIUS);
    double fromX = this.pointX;
    double fromY = this.pointY;
    edgeNodePosition(angle + Math.PI, NODE_RADIUS);
    double dx = this.pointX - fromX;
    double dy = this.pointY - fromY;
    double length = Math.sqrt(dx * dx + dy * dy);
    if (length == 0) {
      length = 1;
    }
    double normalX = -dy / length;
    double normalY = dx / length;

    // The ends are pinned to the nodes and the deflection peaks at mid-span, so
    // the bolt is always jagged in the middle and always lands on both nodes.
    for (int i = 0; i <= BOLT_SEGMENTS; ++i) {
      double amount = i / (double) BOLT_SEGMENTS;
      double offset = (this.random.nextDouble() * 2 - 1) * BOLT_JAGGEDNESS * Math.sin(Math.PI * amount);
      this.boltX[i] = clamp(fromX + dx * amount + normalX * offset, 0, 1);
      this.boltY[i] = clamp(fromY + dy * amount + normalY * offset, 0, 1);
    }
    this.boltPending = true;

    for (int p = 0; p < BOLT_PARTICLES; ++p) {
      pointAlongBolt((p + this.random.nextDouble()) / BOLT_PARTICLES);
      double px = this.pointX;
      double py = this.pointY;
      double launchAngle = this.random.nextDouble() * TAU;
      double speed = BOLT_PARTICLE_SPEED * (.35 + this.random.nextDouble());
      addParticle(px, py, Math.cos(launchAngle) * speed, Math.sin(launchAngle) * speed);
      applyBoltImpulse(px, py);
    }
  }

  /** Left in {@link #pointX}/{@link #pointY}. */
  private void pointAlongBolt(double amount) {
    double span = clamp(amount, 0, 1) * BOLT_SEGMENTS;
    int index = (int) Math.min(BOLT_SEGMENTS - 1, Math.floor(span));
    double local = span - index;
    this.pointX = this.boltX[index] + (this.boltX[index + 1] - this.boltX[index]) * local;
    this.pointY = this.boltY[index] + (this.boltY[index + 1] - this.boltY[index]) * local;
  }

  private void applyBoltImpulse(double px, double py) {
    double gx = px * W1;
    double gy = py * H1;
    double angle = this.random.nextDouble() * TAU;
    double impulseU = Math.cos(angle) * BOLT_IMPULSE;
    double impulseV = Math.sin(angle) * BOLT_IMPULSE;
    int minX = (int) Math.max(1, Math.floor(gx - BOLT_IMPULSE_RADIUS));
    int maxX = (int) Math.min(W1 - 1, Math.ceil(gx + BOLT_IMPULSE_RADIUS));
    int minY = (int) Math.max(1, Math.floor(gy - BOLT_IMPULSE_RADIUS));
    int maxY = (int) Math.min(H1 - 1, Math.ceil(gy + BOLT_IMPULSE_RADIUS));

    for (int cy = minY; cy <= maxY; ++cy) {
      for (int cx = minX; cx <= maxX; ++cx) {
        double dx = cx - gx;
        double dy = cy - gy;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance >= BOLT_IMPULSE_RADIUS) {
          continue;
        }
        double falloff = 1 - distance / BOLT_IMPULSE_RADIUS;
        int i = cy * W + cx;
        this.velU[i] += impulseU * falloff;
        this.velV[i] += impulseV * falloff;
      }
    }
  }

  private void stampPendingBolt() {
    if (!this.boltPending) {
      return;
    }
    for (int s = 0; s < BOLT_SEGMENTS; ++s) {
      stampBoltSegment(this.boltX[s], this.boltY[s], this.boltX[s + 1], this.boltY[s + 1]);
    }
    this.boltPending = false;
  }

  private void stampBoltSegment(double ax, double ay, double bx, double by) {
    double dx = bx - ax;
    double dy = by - ay;
    double cellLength = Math.sqrt(dx * dx * W1 * W1 + dy * dy * H1 * H1);
    int steps = (int) Math.max(1, Math.ceil(cellLength * 2));
    for (int i = 0; i <= steps; ++i) {
      double amount = i / (double) steps;
      stampBoltPoint(ax + dx * amount, ay + dy * amount);
    }
  }

  private void stampBoltPoint(double px, double py) {
    double gx = px * W1;
    double gy = py * H1;
    int minX = (int) Math.max(0, Math.floor(gx - BOLT_DYE_RADIUS));
    int maxX = (int) Math.min(W1, Math.ceil(gx + BOLT_DYE_RADIUS));
    int minY = (int) Math.max(0, Math.floor(gy - BOLT_DYE_RADIUS));
    int maxY = (int) Math.min(H1, Math.ceil(gy + BOLT_DYE_RADIUS));

    for (int cy = minY; cy <= maxY; ++cy) {
      for (int cx = minX; cx <= maxX; ++cx) {
        double dx = cx - gx;
        double dy = cy - gy;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance >= BOLT_DYE_RADIUS) {
          continue;
        }
        double value = BOLT_INTENSITY * (1 - .35 * distance / BOLT_DYE_RADIUS);
        int i = cy * W + cx;
        if (value > this.dye[i]) {
          this.dye[i] = value;
        }
      }
    }
  }

  // -------------------------------------------------------------- the particles

  private double ambientRatePerSecond() {
    double rate = this.particleRate.getValue();
    return 36 * rate * rate;
  }

  private double currentParticleLifespan() {
    return 1 + 9 * this.particleLifespan.getValue();
  }

  private void addParticle(double x, double y, double launchU, double launchV) {
    if (this.particleCount >= MAX_PARTICLES) {
      // Nothing reads the pool in order, so the tail fills the vacated slot
      // rather than shifting two thousand entries down by one.
      int last = this.particleCount - 1;
      this.particleX[0] = this.particleX[last];
      this.particleY[0] = this.particleY[last];
      this.particleAge[0] = this.particleAge[last];
      this.particleLife[0] = this.particleLife[last];
      this.particleLaunchU[0] = this.particleLaunchU[last];
      this.particleLaunchV[0] = this.particleLaunchV[last];
      this.particleCount = last;
    }
    int i = this.particleCount++;
    this.particleX[i] = clamp(x, 0, 1);
    this.particleY[i] = clamp(y, 0, 1);
    this.particleAge[i] = 0;
    this.particleLife[i] = currentParticleLifespan();
    this.particleLaunchU[i] = launchU;
    this.particleLaunchV[i] = launchV;
  }

  private void emitAmbientParticles(double dt) {
    this.ambientEmissionAccumulator += ambientRatePerSecond() * dt;
    while (this.ambientEmissionAccumulator >= 1) {
      this.ambientEmissionAccumulator -= 1;
      addParticle(this.random.nextDouble(), this.random.nextDouble(), 0, 0);
    }
  }

  private void emitSurfaceParticleAndPulse(Agent agent, double radius, double boost) {
    agentSurfaceSample(agent, this.random.nextDouble(), radius);
    double nx = this.sampleNX;
    double ny = this.sampleNY;
    double impulseScale = 2 * boost;
    // Start just outside the solid form so the new particle is immediately
    // visible, then give it a strong short-lived outward launch velocity.
    double x = this.pointX + nx * .75 / W1;
    double y = this.pointY + ny * .75 / H1;
    addParticle(x, y, nx * 21 * impulseScale, ny * 21 * impulseScale);

    double gx = x * W1;
    double gy = y * H1;
    double pulseRadius = 3;
    int minX = (int) Math.max(1, Math.floor(gx - pulseRadius));
    int maxX = (int) Math.min(W1 - 1, Math.ceil(gx + pulseRadius));
    int minY = (int) Math.max(1, Math.floor(gy - pulseRadius));
    int maxY = (int) Math.min(H1 - 1, Math.ceil(gy + pulseRadius));
    for (int cy = minY; cy <= maxY; ++cy) {
      for (int cx = minX; cx <= maxX; ++cx) {
        double dx = cx - gx;
        double dy = cy - gy;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance >= pulseRadius) {
          continue;
        }
        double falloff = 1 - distance / pulseRadius;
        int i = cy * W + cx;
        this.velU[i] += nx * 18 * impulseScale * falloff;
        this.velV[i] += ny * 18 * impulseScale * falloff;
      }
    }
  }

  // ------------------------------------------------------------------- B1, down

  /**
   * B1's press. A band along the top wall driven straight down, tapering over the
   * band so the leading edge of the slug is soft rather than a step.
   */
  private void applyTopEdgeSlam() {
    double reach = B1_SLAM_BAND + 1;
    int lowest = Math.max(1, H1 - B1_SLAM_BAND);
    for (int y = lowest; y < H1; ++y) {
      double depth = H1 - y;
      double scale = B1_SLAM_IMPULSE * (1 - depth / reach);
      for (int x = 1; x < W1; ++x) {
        this.velV[y * W + x] -= scale;
      }
    }
  }

  /**
   * B1 held. A downward push on every interior cell. The vertical term is scaled
   * by a positive random factor rather than offset by a signed one, so no cell is
   * ever pushed upward; the sideways term is the only part that takes a sign, and
   * it is what keeps the sheet from falling as one flat slab.
   */
  private void applyDownwash() {
    for (int y = 1; y < H1; ++y) {
      for (int x = 1; x < W1; ++x) {
        int i = y * W + x;
        double jitter = 1 - B1_DOWNWASH_NOISE * this.random.nextDouble();
        this.velV[i] -= B1_DOWNWASH_FORCE * jitter;
        this.velU[i] += (this.random.nextDouble() * 2 - 1)
          * B1_DOWNWASH_FORCE * B1_DOWNWASH_NOISE * B1_DOWNWASH_SPREAD;
      }
    }
  }

  private void handleB1() {
    boolean on = this.b1.isOn();
    boolean pressed = on && !this.b1Previous;
    this.b1Previous = on;
    if (pressed) {
      applyTopEdgeSlam();
    }
    if (on) {
      applyDownwash();
    }
  }

  // ------------------------------------------------------------------ B2, swirl

  /**
   * A ring vortex about the box center. Direction turns the tangential term
   * around; the inflow term is unsigned, so the spiral draws inward whichever way
   * it is spinning.
   */
  private void applySpiralImpulse(double force, double direction) {
    for (int y = 1; y < H1; ++y) {
      double yn = y / (double) H1 - .5;
      for (int x = 1; x < W1; ++x) {
        double xn = x / (double) W1 - .5;
        double r = Math.sqrt(xn * xn + yn * yn);
        if (r < 1e-5 || r >= B2_SPIRAL_EDGE) {
          continue;
        }
        double inverse = 1 / r;
        double falloff = Math.sin(Math.PI * r / B2_SPIRAL_EDGE) * force;
        int i = y * W + x;
        this.velU[i] += (direction * -yn - xn * B2_SPIRAL_INFLOW) * inverse * falloff;
        this.velV[i] += (direction * xn - yn * B2_SPIRAL_INFLOW) * inverse * falloff;
      }
    }
  }

  /**
   * The reversal is taken on the press, before the slam, so the slam a press
   * throws is already turning the new way. Holding then keeps that same direction
   * until the button is released and pressed again.
   */
  private void handleB2() {
    boolean on = this.b2.isOn();
    boolean pressed = on && !this.b2Previous;
    this.b2Previous = on;
    if (pressed) {
      this.swirlDirection = -this.swirlDirection;
      applySpiralImpulse(B2_SLAM_FORCE, this.swirlDirection);
    }
    if (on) {
      applySpiralImpulse(B2_CURL_FORCE, this.swirlDirection);
    }
  }

  // --------------------------------------------------------------- B3, implosion

  /**
   * B3's press. Every interior cell driven straight at the middle of the box, so
   * the whole scene collapses inward at once rather than four wall fronts marching
   * in over the still fluid between them.
   */
  private void applyImplosion() {
    for (int y = 1; y < H1; ++y) {
      double yn = y / (double) H1 - .5;
      for (int x = 1; x < W1; ++x) {
        double xn = x / (double) W1 - .5;
        double distance = Math.sqrt(xn * xn + yn * yn);
        if (distance < 1e-6) {
          continue;
        }
        // Dividing by the distance normalizes the offset to a direction; the ramp
        // is what supplies the magnitude, so the pull does not blow up near zero.
        double scale = B3_IMPLOSION_IMPULSE
          * Math.min(1, distance / B3_IMPLOSION_RAMP) / distance;
        int i = y * W + x;
        this.velU[i] -= xn * scale;
        this.velV[i] -= yn * scale;
      }
    }
  }

  /**
   * A point on the perimeter just inside the source band, p in [0,1). Left in
   * {@link #pointX}/{@link #pointY}.
   */
  private void perimeterPoint(double p) {
    double inset = (B3_EDGE_BAND + .5) / Math.max(W1, H1);
    double span = 1 - 2 * inset;
    double walk = wrap01(p) * 4;
    int side = (int) Math.floor(walk);
    double along = inset + (walk - side) * span;
    if (side == 0) {
      this.pointX = along;
      this.pointY = inset;
    } else if (side == 1) {
      this.pointX = 1 - inset;
      this.pointY = along;
    } else if (side == 2) {
      this.pointX = 1 - inset - (along - inset);
      this.pointY = 1 - inset;
    } else {
      this.pointX = inset;
      this.pointY = 1 - inset - (along - inset);
    }
  }

  /** One particle off the wall, launched at the middle of the box. */
  private void emitEdgeParticle() {
    perimeterPoint(this.random.nextDouble());
    double px = this.pointX;
    double py = this.pointY;
    double dx = .5 - px;
    double dy = .5 - py;
    double distance = Math.sqrt(dx * dx + dy * dy);
    if (distance == 0) {
      distance = 1;
    }
    addParticle(px, py,
      dx / distance * B3_PARTICLE_SPEED,
      dy / distance * B3_PARTICLE_SPEED);
  }

  private void handleB3() {
    boolean on = this.b3.isOn();
    boolean pressed = on && !this.b3Previous;
    this.b3Previous = on;
    if (!pressed) {
      return;
    }
    applyImplosion();
    for (int i = 0; i < B3_PARTICLES; ++i) {
      emitEdgeParticle();
    }
    this.pendingEdgeSource = true;
  }

  /**
   * The band is laid at a level rather than at full white, and takes the brighter
   * of itself and what is already there. It is a wash the walls arrive under, not
   * a wipe -- a bolt or an agent crossing the band keeps its own brightness.
   */
  private void stampEdgeSource() {
    for (int y = 0; y < H; ++y) {
      boolean inset = (y > B3_EDGE_BAND) && (y < H1 - B3_EDGE_BAND);
      for (int x = 0; x < W; ++x) {
        if (inset && (x > B3_EDGE_BAND) && (x < W1 - B3_EDGE_BAND)) {
          continue;
        }
        int i = y * W + x;
        if (B3_SOURCE_LEVEL > this.dye[i]) {
          this.dye[i] = B3_SOURCE_LEVEL;
        }
      }
    }
  }

  // ---------------------------------------------------------------- B4, shatter

  /** Every interior cell shoved in its own random direction, once. */
  private void applyRandomFieldPulse() {
    for (int y = 1; y < H1; ++y) {
      for (int x = 1; x < W1; ++x) {
        int i = y * W + x;
        this.velU[i] += (this.random.nextDouble() * 2 - 1) * B4_JOLT_IMPULSE;
        this.velV[i] += (this.random.nextDouble() * 2 - 1) * B4_JOLT_IMPULSE;
      }
    }
  }

  /**
   * The one impulse here that stays on the near side of the pressure projection,
   * and the reason is the whole character of the button. An uncorrelated field is
   * almost entirely divergence; letting the projection have it strips that off and
   * leaves the rotational part, so the box shatters into a spread of small eddies
   * rather than into static. Applied after the projection, as the other presses
   * are, it would land as one frame of snow and be gone.
   */
  private void handleB4() {
    boolean on = this.b4.isOn();
    boolean pressed = on && !this.b4Previous;
    this.b4Previous = on;
    if (pressed) {
      applyRandomFieldPulse();
    }
  }

  // ------------------------------------------------------------------ B6 and T1

  /**
   * B6. While on, viscosity and decay smear toward loose and fast-decaying over
   * half a second; switched off, they smear back to whatever the two knobs say
   * over the same half second. Latching rather than momentary, so the smeared
   * state can be held indefinitely without keeping the control pressed.
   */
  private void updateSlipEnvelope(double dt) {
    double step = dt / B6_TWEEN_SECONDS;
    this.slipEnvelope = this.b6.isOn()
      ? Math.min(1, this.slipEnvelope + step)
      : Math.max(0, this.slipEnvelope - step);
  }

  private double effectiveViscosity() {
    double rest = clamp(this.viscosity.getValue(), 0, 1);
    return rest + (B6_HELD_VISCOSITY - rest) * this.slipEnvelope;
  }

  private double effectiveDecayRate() {
    double rest = clamp(this.decayRate.getValue(), 0, 1);
    return rest + (B6_HELD_DECAY - rest) * this.slipEnvelope;
  }

  /**
   * Either flip of T1 throws one burst off the disc, and that is the whole of its
   * emission -- holding T1 on does not keep feeding particles in. Switching it off
   * is therefore as much of an event as switching it on, and the on state is the
   * sustained outward field alone.
   */
  private void handleT1Transition(double radius) {
    boolean on = this.t1.isOn();
    if (on == this.thrustPrevious) {
      return;
    }
    this.thrustPrevious = on;
    for (int i = 0; i < T1_TRANSITION_PARTICLES; ++i) {
      emitSurfaceParticleAndPulse(this.agents[0], radius, T1_TRANSITION_IMPULSE);
    }
  }

  private void advectParticles(double dt) {
    double launchRetention = Math.exp(-3.5 * dt);
    for (int i = this.particleCount - 1; i >= 0; --i) {
      this.particleAge[i] += dt;
      if (this.particleAge[i] >= this.particleLife[i]) {
        int last = --this.particleCount;
        this.particleX[i] = this.particleX[last];
        this.particleY[i] = this.particleY[last];
        this.particleAge[i] = this.particleAge[last];
        this.particleLife[i] = this.particleLife[last];
        this.particleLaunchU[i] = this.particleLaunchU[last];
        this.particleLaunchV[i] = this.particleLaunchV[last];
        continue;
      }
      double gx = this.particleX[i] * W1;
      double gy = this.particleY[i] * H1;
      double carriedU = sampleField(this.velU, gx, gy) + this.particleLaunchU[i];
      double carriedV = sampleField(this.velV, gx, gy) + this.particleLaunchV[i];
      this.particleX[i] = clamp(this.particleX[i] + carriedU * dt / W1, 0, 1);
      this.particleY[i] = clamp(this.particleY[i] + carriedV * dt / H1, 0, 1);
      this.particleLaunchU[i] *= launchRetention;
      this.particleLaunchV[i] *= launchRetention;
    }
  }

  /** A particle is a one-texel soft emitter with a sinusoidal 0 -&gt; 1 -&gt; 0 cycle. */
  private void emitParticleDye() {
    double radius = 1.25;
    double amplitude = .5 * Math.pow(20, this.particleAmplitude.getValue());
    for (int p = 0; p < this.particleCount; ++p) {
      double emission = amplitude
        * Math.sin(Math.PI * this.particleAge[p] / this.particleLife[p]);
      double gx = this.particleX[p] * W1;
      double gy = this.particleY[p] * H1;
      int minX = (int) Math.max(0, Math.floor(gx - radius));
      int maxX = (int) Math.min(W1, Math.ceil(gx + radius));
      int minY = (int) Math.max(0, Math.floor(gy - radius));
      int maxY = (int) Math.min(H1, Math.ceil(gy + radius));
      for (int y = minY; y <= maxY; ++y) {
        for (int x = minX; x <= maxX; ++x) {
          double dx = x - gx;
          double dy = y - gy;
          double distance = Math.sqrt(dx * dx + dy * dy);
          if (distance >= radius) {
            continue;
          }
          double falloff = 1 - distance / radius;
          double value = emission * falloff * falloff;
          int i = y * W + x;
          if (value > this.dye[i]) {
            this.dye[i] = value;
          }
        }
      }
    }
  }

  // ----------------------------------------------------------------- the solver

  private double sampleField(double[] field, double x, double y) {
    x = clamp(x, 0, W1);
    y = clamp(y, 0, H1);
    int x0 = (int) x;
    int y0 = (int) y;
    int x1 = (x0 < W1) ? x0 + 1 : x0;
    int y1 = (y0 < H1) ? y0 + 1 : y0;
    double tx = x - x0;
    double ty = y - y0;
    int row0 = y0 * W;
    int row1 = y1 * W;
    double lower = field[row0 + x0] + (field[row0 + x1] - field[row0 + x0]) * tx;
    double upper = field[row1 + x0] + (field[row1 + x1] - field[row1 + x0]) * tx;
    return lower + (upper - lower) * ty;
  }

  private void advectVelocity(double dt) {
    for (int y = 0; y < H; ++y) {
      for (int x = 0; x < W; ++x) {
        int i = y * W + x;
        double backX = x - this.velU[i] * dt;
        double backY = y - this.velV[i] * dt;
        this.velU2[i] = sampleField(this.velU, backX, backY);
        this.velV2[i] = sampleField(this.velV, backX, backY);
      }
    }
    double[] swapU = this.velU;
    this.velU = this.velU2;
    this.velU2 = swapU;
    double[] swapV = this.velV;
    this.velV = this.velV2;
    this.velV2 = swapV;
    enforceNoSlipWalls();
  }

  private void diffuseVelocity(double dt) {
    double thickness = effectiveViscosity();
    double viscosityRangeScale = .5 + 2.5 * thickness;
    double nu = thickness * thickness * 54 * viscosityRangeScale;
    if (nu <= 0) {
      return;
    }
    double a = nu * dt;
    double denominator = 1 + 4 * a;
    System.arraycopy(this.velU, 0, this.diffusionU, 0, CELLS);
    System.arraycopy(this.velV, 0, this.diffusionV, 0, CELLS);
    for (int iteration = 0; iteration < DIFFUSION_ITERATIONS; ++iteration) {
      for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
          int index = y * W + x;
          if (x == 0 || x == W1 || y == 0 || y == H1) {
            this.velU2[index] = 0;
            this.velV2[index] = 0;
          } else {
            this.velU2[index] = (this.diffusionU[index] + a * (this.velU[index - 1]
              + this.velU[index + 1] + this.velU[index - W] + this.velU[index + W])) / denominator;
            this.velV2[index] = (this.diffusionV[index] + a * (this.velV[index - 1]
              + this.velV[index + 1] + this.velV[index - W] + this.velV[index + W])) / denominator;
          }
        }
      }
      double[] swapU = this.velU;
      this.velU = this.velU2;
      this.velU2 = swapU;
      double[] swapV = this.velV;
      this.velV = this.velV2;
      this.velV2 = swapV;
    }
  }

  private void applyVorticityConfinement(double dt) {
    // Read once rather than per cell, but folded into the force in the script's
    // own multiply order. Pre-multiplying the three constant factors would be a
    // different rounding of the same expression, and a one-ulp difference in a
    // velocity field is not something a fluid solver reliably forgets.
    double turb = this.turbulence.getValue();
    for (int y = 0; y < H; ++y) {
      for (int x = 0; x < W; ++x) {
        int i = y * W + x;
        double left = (x > 0) ? this.velV[i - 1] : this.velV[i];
        double right = (x < W1) ? this.velV[i + 1] : this.velV[i];
        double bottom = (y > 0) ? this.velU[i - W] : this.velU[i];
        double top = (y < H1) ? this.velU[i + W] : this.velU[i];
        this.curl[i] = .5 * ((right - left) - (top - bottom));
      }
    }
    for (int yy = 1; yy < H1; ++yy) {
      for (int xx = 1; xx < W1; ++xx) {
        int j = yy * W + xx;
        double gx = .5 * (Math.abs(this.curl[j + 1]) - Math.abs(this.curl[j - 1]));
        double gy = .5 * (Math.abs(this.curl[j + W]) - Math.abs(this.curl[j - W]));
        double magnitude = Math.sqrt(gx * gx + gy * gy);
        if (magnitude < 1e-5) {
          continue;
        }
        double force = this.curl[j] * MAX_VORTICITY_CONFINEMENT * turb * dt / magnitude;
        this.velU[j] += gy * force;
        this.velV[j] -= gx * force;
      }
    }
  }

  private void projectVelocity() {
    for (int y = 0; y < H; ++y) {
      for (int x = 0; x < W; ++x) {
        int i = y * W + x;
        double left = (x > 0) ? this.velU[i - 1] : -this.velU[i];
        double right = (x < W1) ? this.velU[i + 1] : -this.velU[i];
        double bottom = (y > 0) ? this.velV[i - W] : -this.velV[i];
        double top = (y < H1) ? this.velV[i + W] : -this.velV[i];
        this.divergence[i] = .5 * ((right - left) + (top - bottom));
        this.pressure[i] *= .8;
      }
    }
    for (int iteration = 0; iteration < PRESSURE_ITERATIONS; ++iteration) {
      for (int yy = 0; yy < H; ++yy) {
        for (int xx = 0; xx < W; ++xx) {
          int j = yy * W + xx;
          double center = this.pressure[j];
          double leftP = (xx > 0) ? this.pressure[j - 1] : center;
          double rightP = (xx < W1) ? this.pressure[j + 1] : center;
          double bottomP = (yy > 0) ? this.pressure[j - W] : center;
          double topP = (yy < H1) ? this.pressure[j + W] : center;
          this.pressure2[j] = (leftP + rightP + bottomP + topP - this.divergence[j]) * .25;
        }
      }
      double[] swap = this.pressure;
      this.pressure = this.pressure2;
      this.pressure2 = swap;
    }
    for (int y2 = 0; y2 < H; ++y2) {
      for (int x2 = 0; x2 < W; ++x2) {
        int index = y2 * W + x2;
        double centerP = this.pressure[index];
        double left2 = (x2 > 0) ? this.pressure[index - 1] : centerP;
        double right2 = (x2 < W1) ? this.pressure[index + 1] : centerP;
        double bottom2 = (y2 > 0) ? this.pressure[index - W] : centerP;
        double top2 = (y2 < H1) ? this.pressure[index + W] : centerP;
        this.velU[index] -= .5 * (right2 - left2);
        this.velV[index] -= .5 * (top2 - bottom2);
      }
    }
    enforceNoSlipWalls();
  }

  private void enforceNoSlipWalls() {
    for (int x = 0; x < W; ++x) {
      this.velU[x] = 0;
      this.velV[x] = 0;
      int top = H1 * W + x;
      this.velU[top] = 0;
      this.velV[top] = 0;
    }
    for (int y = 1; y < H1; ++y) {
      int left = y * W;
      int right = left + W1;
      this.velU[left] = 0;
      this.velV[left] = 0;
      this.velU[right] = 0;
      this.velV[right] = 0;
    }
  }

  private void advectAndDecayDye(double dt) {
    double fade = effectiveDecayRate();
    double decayPerSecond = .05 + 13.95 * fade * fade;
    double retention = Math.exp(-decayPerSecond * dt);
    for (int y = 0; y < H; ++y) {
      for (int x = 0; x < W; ++x) {
        int i = y * W + x;
        this.dye2[i] = sampleField(this.dye,
          x - this.velU[i] * dt, y - this.velV[i] * dt) * retention;
      }
    }
    double[] swap = this.dye;
    this.dye = this.dye2;
    this.dye2 = swap;
  }

  // ------------------------------------------------------------------ the frame

  private void simulate(double dt) {
    // Baselined on first use rather than defaulted, so loading a project with a
    // switch already flipped does not read as a flip and fire on frame one.
    if (!this.togglesBaselined) {
      this.thrustPrevious = this.t1.isOn();
      this.sweepPrevious = this.t5.isOn();
      this.boltPrevious = this.t6.isOn();
      this.b1Previous = this.b1.isOn();
      this.b2Previous = this.b2.isOn();
      this.b3Previous = this.b3.isOn();
      this.b4Previous = this.b4.isOn();
      this.togglesBaselined = true;
    }

    double radius = agentRadiusValue();
    updateSlipEnvelope(dt);
    updateAgents(dt);
    emitAmbientParticles(dt);
    advectVelocity(dt);
    diffuseVelocity(dt);
    for (Agent agent : this.agents) {
      applyAgentSweep(agent, dt, radius);
    }

    handleB4();

    applyVorticityConfinement(dt);
    projectVelocity();

    // Everything below is radial, tangential or impulsive. It has to follow the
    // zero-divergence projection; applying it before would let pressure cancel
    // most of the visible motion.
    handleB1();
    handleB2();
    handleB3();

    if (this.t1.isOn()) {
      applyRadialField(this.agents[0], dt, radius, 1, T1_PUSH_MULTIPLIER);
    }
    if (this.t3.isOn()) {
      applyRadialField(this.agents[1], dt, radius, -1, T3_PULL_MULTIPLIER);
    }
    handleT1Transition(radius);
    updateSweep(dt);
    updateBolt();

    advectParticles(dt);
    advectAndDecayDye(dt);
    emitParticleDye();
    stampPendingBolt();
    if (this.pendingEdgeSource) {
      stampEdgeSource();
      this.pendingEdgeSource = false;
    }
    stampNodePair(sweepAngle(), NODE_LEVEL);
    stampNodePair(boltAngle(), NODE_LEVEL);
    stampAgent(this.agents[0], radius, false);
    stampAgent(this.agents[1], radius, this.t3.isOn());
  }

  @Override
  protected void run(double deltaMs) {
    // Hoisted out of the point loop, where it does not vary by point and would
    // cost one pow per LED per frame.
    this.renderGamma = Math.pow(4, this.gammaCorrection.getValue() * 2 - 1);

    double elapsed = Double.isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, .25) : 0;
    double step = 1 / SIM_HZ;
    this.simAccumulator = Math.min(this.simAccumulator + elapsed, MAX_SUBSTEPS * step);
    while (this.simAccumulator >= step) {
      this.simAccumulator -= step;
      simulate(step);
    }

    draw();
  }

  private void draw() {
    double gamma = this.renderGamma;
    for (LXPoint p : this.model.points) {
      double value = clamp(sampleField(this.dye, p.xn * W1, p.yn * H1), 0, 1);
      int channel = (int) Math.round(Math.pow(value, gamma) * 255);
      this.colors[p.index] = LXColor.rgb(channel, channel, channel);
    }
  }

  // ------------------------------------------------------------------ utilities

  private static double clamp(double value, double low, double high) {
    return (value < low) ? low : ((value > high) ? high : value);
  }

  private static double wrap01(double value) {
    value %= 1;
    return (value < 0) ? value + 1 : value;
  }

  private static double wrapAngle(double value) {
    value %= TAU;
    return (value < 0) ? value + TAU : value;
  }

  private static double shortestAngleDifference(double a, double b) {
    double difference = a - b;
    while (difference > Math.PI) {
      difference -= TAU;
    }
    while (difference < -Math.PI) {
      difference += TAU;
    }
    return difference;
  }
}
