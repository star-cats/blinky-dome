package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * A shape that turns and breathes, printing a copy of itself four times a beat
 * and leaving the copies behind to recede. Ported from Scripts/RotoShapes.js.
 *
 * The shape is a closed contour written as a polar function r(theta), which is
 * what makes the whole thing work: a squircle, a triangle and a five-lobe flower
 * are three such functions, so morphing between them is nothing more exotic than
 * blending two radii. There is no vertex correspondence to solve and no way for
 * the contour to tangle or self-intersect on the way across, which is the usual
 * problem with morphing outlines.
 *
 * Every sixteenth note the live shape is stamped: its exact state — angle,
 * scale, position, where it had got to in the morph, and how bright it was — is
 * frozen into a copy. Copies never update their shape again. They only recede:
 * shrinking, drifting, turning a little further and fading, each on exp(-t), so
 * the trail thins out fast near the head and lingers at the tail rather than
 * marching away in even steps.
 *
 * Only the contour is drawn, as a stroke of its own thickness with an optional
 * halo around it. The interiors are empty, so the trail is seen through itself
 * and a copy twenty deep is still legible behind the ones in front. Depth still
 * decides what happens where two strokes cross: copies stack strictly back to
 * front, newest in front, and rendering runs front to back per LED so the nearer
 * stroke wins the crossing and the pixel can stop early once it is covered.
 *
 * <h2>Timing</h2>
 *
 * The script ran on a hard-coded 120 BPM. Here the sixteenth-note grid comes off
 * the {@link PrimaryController} through a {@link PrimaryController.Follower},
 * exactly as {@link StarCatPattern} does, so the stamps land with the show and
 * the pattern free-runs at Free BPM when nothing is driving it.
 *
 * The accent — the flash to white and the jump in size — is a separate thing
 * from the grid, and can be fired from outside through the Beat trigger. Wire
 * {@link DriveTracker}'s beat output to it, or a MIDI note, or anything else,
 * and turn Auto off to stop the internal grid firing it as well. Stamping stays
 * on the grid either way: the copies are a metronome, and the accent is what is
 * played over it.
 *
 * One detail falls out rather than being built. A stamp copies the live shape's
 * brightness and size along with everything else, and the live shape is grey at
 * its resting size except on the accent, where it flashes white and jumps
 * larger. Both fall back fast enough to be gone before the next sixteenth, so of
 * every four stamps the accented one is frozen white and oversized while the
 * other three are neither. The trail ends up self-marking its own downbeats
 * twice over, and they stay legible as the whole thing recedes.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Roto Shapes")
@LXComponent.Description("A morphing contour stamping copies of itself on every sixteenth")
public class RotoShapesPattern extends LXPattern {

  private static final double TAU = Math.PI * 2;

  /** Stamps per beat. Four is a sixteenth note. */
  private static final int STAMPS_PER_BEAT = 4;

  /**
   * Beats in one cycle of the slow scale breath.
   *
   * On the grid rather than on a wall clock, so the breath keeps its relationship
   * to the stamps at any tempo — two bars of four, whatever that is in seconds.
   */
  private static final double BREATHE_BEATS = 8;

  /** Copies kept alive. The oldest is dropped when a new stamp needs the room. */
  private static final int MAX_SHAPES = 20;

  /**
   * Time constant of the accent's size pulse falling back to normal.
   *
   * Deliberately much shorter than the sixteenth between stamps — at 50ms the
   * pulse is 8% of its height by the time the next stamp is taken, so the
   * accented copy is frozen visibly larger and the three after it are not. At
   * tempos far above 120 the sixteenth closes on this and the distinction
   * softens, which is the honest behaviour rather than something to compensate.
   */
  private static final double PULSE_TAU = .05;

  private static final int SHAPE_COUNT = 3;
  private static final int SHAPE_SQUIRCLE = 0;
  private static final int SHAPE_TRIANGLE = 1;

  /** Superellipse exponent for the squircle: 2 is a circle, high is a square. */
  private static final double SQUIRCLE_N = 4;

  /**
   * How deep the flower's lobes cut, as a fraction of its radius. Shallow enough
   * that it reads as a rippled circle rather than a star.
   */
  private static final double FLOWER_DEPTH = .175;
  private static final double FLOWER_LOBES = 5;

  /**
   * Radius samples per contour.
   *
   * Every shape is baked to a table of radii once, rather than evaluated per LED.
   * A copy's morph state is frozen the moment it is stamped, so its table is
   * built once at stamp time and never again; only the live shape rebuilds each
   * frame. That turns a few hundred thousand pow() and cos() calls a frame into a
   * few hundred, and leaves the inner loop doing nothing but a lookup and a lerp.
   */
  private static final int LUT_SIZE = 256;

  /** Fraction of each morph stage spent holding the shape before it starts to go. */
  private static final double MORPH_HOLD = .45;

  // ------------------------------------------------------------------ parameters

  public final CompoundParameter size =
    new CompoundParameter("Size", .3, 0, 1)
    .setDescription("Leading shape size, as a fraction of the frame");

  public final CompoundParameter posX =
    new CompoundParameter("X", .5, 0, 1)
    .setDescription("Center, horizontal");

  public final CompoundParameter posY =
    new CompoundParameter("Y", .5, 0, 1)
    .setDescription("Center, vertical");

  public final CompoundParameter spin =
    new CompoundParameter("Spin", 18, -180, 180)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("How fast the leading shape turns, in degrees per second");

  public final CompoundParameter breathe =
    new CompoundParameter("Breathe", .35, 0, 1)
    .setDescription("How much the leading shape's scale swells and shrinks");

  public final CompoundParameter pulse =
    new CompoundParameter("Pulse", 20, 0, 100)
    .setUnits(LXParameter.Units.PERCENT)
    .setDescription("How much bigger the leading shape jumps on the accent");

  public final CompoundParameter morph =
    new CompoundParameter("Morph", .3, 0, 1)
    .setDescription("How fast it works around squircle, triangle, flower");

  // --- The accent ------------------------------------------------------------

  public final TriggerParameter beat =
    new TriggerParameter("Beat", this::onBeat)
    .setDescription("Fire the accent -- wire this to a beat, or leave Auto on");

  public final BooleanParameter autoBeat =
    new BooleanParameter("Auto", true)
    .setDescription("Also fire the accent on every beat of the controller's grid");

  public final CompoundParameter flashTau =
    new CompoundParameter("Flash", .1, .01, .5)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("How long the accent's flash to white takes to fall back");

  // --- The trail -------------------------------------------------------------

  public final CompoundParameter shrink =
    new CompoundParameter("Shrink", .4, 0, 1)
    .setDescription("How fast a stamped copy shrinks away");

  public final CompoundParameter trail =
    new CompoundParameter("Trail", 1.45, .2, 3)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("How long a stamped copy takes to fade out");

  public final CompoundParameter drift =
    new CompoundParameter("Drift", .35, 0, 1)
    .setDescription("How far a stamped copy travels as it recedes");

  public final CompoundParameter driftDir =
    new CompoundParameter("Dir", 90, 0, 360)
    .setUnits(LXParameter.Units.DEGREES)
    .setDescription("Which way copies travel; 90 is straight up");

  public final CompoundParameter twist =
    new CompoundParameter("Twist", .55, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("How much further a copy turns as it recedes; .5 is none");

  // --- Stroke and colour -----------------------------------------------------

  public final CompoundParameter thickness =
    new CompoundParameter("Thick", .3, 0, 1)
    .setDescription("Stroke width of the contour");

  public final CompoundParameter glow =
    new CompoundParameter("Glow", .35, 0, 1)
    .setDescription("Halo spreading out from the stroke");

  public final CompoundParameter base =
    new CompoundParameter("Base", 65, 0, 100)
    .setUnits(LXParameter.Units.PERCENT)
    .setDescription("Leading shape's resting grey between accents");

  public final CompoundParameter soft =
    new CompoundParameter("Soft", .25, 0, 1)
    .setDescription("Edge softness -- this is the anti-aliasing");

  public final CompoundParameter level =
    new CompoundParameter("Level", 100, 0, 100)
    .setUnits(LXParameter.Units.PERCENT)
    .setDescription("Overall brightness");

  // --- Clock -----------------------------------------------------------------

  public final CompoundParameter phase =
    new CompoundParameter("Phase", 0, -.5, .5)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Slide the stamp grid earlier or later against the beat, in beats");

  public final BoundedParameter fallbackBpm =
    new BoundedParameter("Free BPM", 120, 40, 200)
    .setDescription("Tempo to stamp at when there is no controller, or before it has found one");

  public final CompoundParameter sync =
    new CompoundParameter("Sync", 1, .1, 10)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("How long the stamps take to drift back onto the beat grid; they never snap");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Correct for a non-square model");

  // ------------------------------------------------------------------- the shapes

  /**
   * Each contour scaled so all three enclose the same area, worked out at
   * construction. Without this the morph reads as a size change as much as a
   * shape change: a triangle on a unit circumradius covers less than half the
   * squircle's area, and it visibly shrinks and swells as the contour crosses.
   */
  private final double[] shapeNorm = new double[SHAPE_COUNT];

  private final double[] liveLut = new double[LUT_SIZE];
  private final double[][] stampLut = new double[MAX_SHAPES][LUT_SIZE];

  // Stamped copies, as a ring buffer. Parallel flat arrays rather than objects:
  // these are walked per LED, and the flat form keeps the inner loop free of
  // field lookups through a reference.
  private final double[] stampAge = new double[MAX_SHAPES];
  private final double[] stampRot = new double[MAX_SHAPES];
  private final double[] stampScale = new double[MAX_SHAPES];
  private final double[] stampCx = new double[MAX_SHAPES];
  private final double[] stampCy = new double[MAX_SHAPES];
  private final double[] stampBright = new double[MAX_SHAPES];
  private final double[] stampMaxR = new double[MAX_SHAPES];
  private final double[] stampMinR = new double[MAX_SHAPES];
  private int stampHead = 0;
  private int stampCount = 0;

  /** Extremes of the table buildLut last wrote. See buildLut. */
  private double builtMaxR = 1;
  private double builtMinR = 1;

  // The draw list for this frame: every visible shape, front to back, with the
  // live shape always at index 0. Rebuilt each frame so the render loop is a
  // straight walk with no ordering logic in it.
  private final double[][] drawLut = new double[MAX_SHAPES + 1][];
  private final double[] drawCx = new double[MAX_SHAPES + 1];
  private final double[] drawCy = new double[MAX_SHAPES + 1];
  private final double[] drawScale = new double[MAX_SHAPES + 1];
  private final double[] drawRot = new double[MAX_SHAPES + 1];
  private final double[] drawBright = new double[MAX_SHAPES + 1];
  private final double[] drawMaxR = new double[MAX_SHAPES + 1];
  private final double[] drawMinR = new double[MAX_SHAPES + 1];
  private int drawCount = 0;

  private final PrimaryController.Follower clock = new PrimaryController.Follower();
  private long lastStampIndex = Long.MIN_VALUE;
  private long lastBeatIndex = Long.MIN_VALUE;

  /**
   * Accents that arrived since the last frame.
   *
   * Counted rather than applied, because the trigger fires from whatever thread
   * rang it while the animation belongs to the render pass.
   */
  private int pendingBeats = 0;

  /**
   * Seconds since the last accent, which is what the flash and the size pulse
   * decay against.
   *
   * Held in seconds rather than read off the beat phase because both time
   * constants are absolute: a 50ms pulse is 50ms whether the show is at 90 BPM
   * or 160. Starts large so nothing flashes on the first frame.
   */
  private double sinceAccent = 99;

  private double liveRot = 0;
  private double morphPhase = 0;
  private double liveMaxR = 1;
  private double liveMinR = 1;

  private double softWorld = .02;
  private double strokeHalf = .02;
  private double glowAmount = 0;
  private double glowReach = .01;
  private double strokeBand = .04;
  private double aspectX = 1;

  public RotoShapesPattern(LX lx) {
    super(lx);
    addParameter("size", this.size);
    addParameter("posX", this.posX);
    addParameter("posY", this.posY);
    addParameter("spin", this.spin);
    addParameter("breathe", this.breathe);
    addParameter("pulse", this.pulse);
    addParameter("morph", this.morph);
    addParameter("beat", this.beat);
    addParameter("autoBeat", this.autoBeat);
    addParameter("flashTau", this.flashTau);
    addParameter("shrink", this.shrink);
    addParameter("trail", this.trail);
    addParameter("drift", this.drift);
    addParameter("driftDir", this.driftDir);
    addParameter("twist", this.twist);
    addParameter("thickness", this.thickness);
    addParameter("glow", this.glow);
    addParameter("base", this.base);
    addParameter("soft", this.soft);
    addParameter("level", this.level);
    addParameter("phase", this.phase);
    addParameter("fallbackBpm", this.fallbackBpm);
    addParameter("sync", this.sync);
    addParameter("autoAspect", this.autoAspect);

    normalizeShapes();
  }

  /** Fire the accent. Wired to the Beat trigger; the grid calls it too. */
  private void onBeat() {
    ++this.pendingBeats;
  }

  @Override
  protected void onActive() {
    // Nothing carried over from the last time this was up: a trail left half
    // faded would pop back in at whatever it held when the pattern went away.
    this.stampCount = 0;
    this.stampHead = 0;
    this.drawCount = 0;
    this.lastStampIndex = Long.MIN_VALUE;
    this.lastBeatIndex = Long.MIN_VALUE;
    this.sinceAccent = 99;
    this.pendingBeats = 0;
  }

  // -------------------------------------------------------------- the contours
  //
  // Each is r(theta) for a closed curve about the origin. They only have to be
  // continuous and positive; nothing downstream cares what they are.

  private static double shapeRadius(int shape, double theta) {
    if (shape == SHAPE_SQUIRCLE) {
      // Superellipse |x|^n + |y|^n = 1, solved for r along the ray at theta.
      double c = Math.abs(Math.cos(theta));
      double s = Math.abs(Math.sin(theta));
      return Math.pow(Math.pow(c, SQUIRCLE_N) + Math.pow(s, SQUIRCLE_N), -1 / SQUIRCLE_N);
    }
    if (shape == SHAPE_TRIANGLE) {
      // A regular polygon in polar form: the ray hits a flat face, and the
      // distance to that face is the inradius over the cosine of the angle off
      // the face's normal. Sharp vertices, and continuous everywhere.
      double wedge = TAU / 3;
      double half = wedge / 2;
      double a = theta - Math.floor(theta / wedge) * wedge;
      return Math.cos(half) / Math.cos(a - half);
    }
    // Five lobes: a circle with a cosine ripple around it.
    return 1 + FLOWER_DEPTH * Math.cos(FLOWER_LOBES * theta);
  }

  /**
   * Scale each contour to a common enclosed area.
   *
   * Area is (1/2) * integral of r^2, so making the mean of r^2 equal across the
   * three is exactly making their areas equal. Measured by sampling rather than
   * derived, so editing a contour above — or adding a fourth — needs no new
   * constant worked out by hand to keep the morph from pulsing.
   */
  private void normalizeShapes() {
    for (int shape = 0; shape < SHAPE_COUNT; ++shape) {
      double sum = 0;
      for (int i = 0; i < LUT_SIZE; ++i) {
        double r = shapeRadius(shape, i * TAU / LUT_SIZE);
        sum += r * r;
      }
      this.shapeNorm[shape] = 1 / Math.sqrt(sum / LUT_SIZE);
    }
  }

  /**
   * Bake the contour at morph position m into a table of radii.
   *
   * m runs 0..3 and wraps: its whole part picks the pair being crossed and its
   * fraction says how far across. The fraction is held flat for the first
   * MORPH_HOLD of each stage and smoothstepped over the rest, so each shape is
   * legible as itself for a while instead of the contour being permanently
   * caught between two things.
   *
   * Leaves the table's smallest and largest radius in builtMinR and builtMaxR.
   * Drawing outlines rather than fills makes both worth having: the renderer
   * rejects a point that is outside the contour's reach *and* one that is deep
   * enough inside to be clear of the stroke, which for a hollow shape is most of
   * the area it covers.
   */
  private void buildLut(double[] lut, double m) {
    int stage = (int) Math.floor(m) % SHAPE_COUNT;
    if (stage < 0) {
      stage += SHAPE_COUNT;
    }
    int next = (stage + 1) % SHAPE_COUNT;

    double frac = m - Math.floor(m);
    double u = clamp((frac - MORPH_HOLD) / (1 - MORPH_HOLD), 0, 1);
    double blend = u * u * (3 - 2 * u);

    double normA = this.shapeNorm[stage];
    double normB = this.shapeNorm[next];
    double maxR = 0;
    double minR = Double.MAX_VALUE;

    for (int i = 0; i < LUT_SIZE; ++i) {
      double theta = i * TAU / LUT_SIZE;
      double r = lerp(
        shapeRadius(stage, theta) * normA,
        shapeRadius(next, theta) * normB,
        blend);
      lut[i] = r;
      if (r > maxR) {
        maxR = r;
      }
      if (r < minR) {
        minR = r;
      }
    }
    this.builtMaxR = maxR;
    this.builtMinR = minR;
  }

  /** Radius at an arbitrary angle, wrapping and interpolating between samples. */
  private static double sampleLut(double[] lut, double theta) {
    double turns = theta / TAU;
    turns -= Math.floor(turns);
    double f = turns * LUT_SIZE;
    int i0 = (int) f;
    if (i0 >= LUT_SIZE) {
      i0 = LUT_SIZE - 1;
    }
    int i1 = (i0 + 1) % LUT_SIZE;
    double t = f - i0;
    return lut[i0] + (lut[i1] - lut[i0]) * t;
  }

  // ------------------------------------------------------------------ the frame

  @Override
  protected void run(double deltaMs) {
    this.clock.loop(deltaMs, this.fallbackBpm.getValue(), this.sync.getValue());

    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, .25) : 0;
    this.sinceAccent += dt;

    double beats = this.clock.getBeats() + this.phase.getValue();

    // The grid's own accent, if it is switched on. Forward crossings only, as
    // Follower.beatIndex requires: the drift correction can walk the clock back
    // over a boundary it just crossed, and re-crossing must not accent twice.
    long beatIndex = (long) Math.floor(beats);
    if (beatIndex > this.lastBeatIndex) {
      boolean first = (this.lastBeatIndex == Long.MIN_VALUE);
      this.lastBeatIndex = beatIndex;
      if (!first && this.autoBeat.isOn()) {
        onBeat();
      }
    }

    // Spent before the brightness and size are worked out, so the accent lands
    // on this frame rather than the next one.
    if (this.pendingBeats > 0) {
      this.pendingBeats = 0;
      this.sinceAccent = 0;
    }

    this.liveRot += Math.toRadians(this.spin.getValue()) * dt;
    this.morphPhase += lerp(.005, .12, this.morph.getValue()) * dt * SHAPE_COUNT;
    this.morphPhase -= Math.floor(this.morphPhase / SHAPE_COUNT) * SHAPE_COUNT;

    // The live contour is the only one that has to be rebuilt, because it is the
    // only one still moving through the morph.
    buildLut(this.liveLut, this.morphPhase);
    this.liveMaxR = this.builtMaxR;
    this.liveMinR = this.builtMinR;

    // Grey at rest, white on the accent, falling back exponentially in between.
    double rest = clamp(this.base.getValue() / 100, 0, 1);
    double liveBright = rest
      + (1 - rest) * Math.exp(-this.sinceAccent / this.flashTau.getValue());

    // Size is the slow breath, then a sharp jump on the accent falling straight
    // back out of it. The two multiply rather than add, so the accent is the
    // same proportional kick wherever the breath has got to.
    double liveScale = lerp(.05, .6, this.size.getValue())
      * (1 + this.breathe.getValue() * .45 * Math.sin(beats * TAU / BREATHE_BEATS))
      * (1 + (this.pulse.getValue() / 100) * Math.exp(-this.sinceAccent / PULSE_TAU));
    if (liveScale < 0) {
      liveScale = 0;
    }

    this.softWorld = lerp(.004, .06, this.soft.getValue());
    this.strokeHalf = lerp(.003, .055, this.thickness.getValue());
    this.glowAmount = this.glow.getValue();
    // Reach grows with the amount, so one knob widens and brightens the halo
    // together rather than needing two dialed in agreement.
    this.glowReach = lerp(.006, .11, this.glowAmount);
    // How far from the contour anything can still be lit. Four time constants of
    // the halo is under 2% of its peak, which no fixture resolves.
    this.strokeBand = this.strokeHalf + this.softWorld
      + ((this.glowAmount > 0) ? 4 * this.glowReach : 0);

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    // One stamp per sixteenth, off the grid rather than a countdown, so a long
    // frame cannot let the phase slip: the stamp lands on the same grid it would
    // have if every frame had been short.
    long stampIndex = (long) Math.floor(beats * STAMPS_PER_BEAT);
    if (stampIndex > this.lastStampIndex) {
      boolean first = (this.lastStampIndex == Long.MIN_VALUE);
      this.lastStampIndex = stampIndex;
      if (!first) {
        // Only ever one per frame. A frame long enough to span two sixteenths
        // would otherwise stamp two copies in the same state and at the same
        // age, which draws as one copy and wastes a slot out of the twenty.
        stamp(liveScale, liveBright);
      }
    }

    for (int i = 0; i < this.stampCount; ++i) {
      this.stampAge[i] += dt;
    }

    buildDrawList(liveScale, liveBright);
    draw();
  }

  /** Freeze the live shape into the ring, dropping the oldest if it is full. */
  private void stamp(double scale, double brightness) {
    int slot = this.stampHead;
    this.stampHead = (this.stampHead + 1) % MAX_SHAPES;
    if (this.stampCount < MAX_SHAPES) {
      ++this.stampCount;
    }

    // The copy's contour never changes again, so its table is written once here
    // rather than rebuilt every frame for the rest of its life.
    buildLut(this.stampLut[slot], this.morphPhase);
    this.stampMaxR[slot] = this.builtMaxR;
    this.stampMinR[slot] = this.builtMinR;

    this.stampAge[slot] = 0;
    this.stampRot[slot] = this.liveRot;
    this.stampScale[slot] = scale;
    this.stampCx[slot] = this.posX.getValue();
    this.stampCy[slot] = this.posY.getValue();
    // Brightness is copied along with the geometry, which is what makes the
    // accented copy in the trail a white one.
    this.stampBright[slot] = brightness;
  }

  /**
   * Everything visible this frame, front to back.
   *
   * The live shape leads, then the copies newest first. A copy's four recessions
   * all run on exp(-t): shrink and fade decay toward nothing, while drift and
   * twist are the integral of a decaying rate, so they ease out to a limit
   * instead of running away. That is what makes the trail bunch up at its tail
   * rather than spreading evenly.
   */
  private void buildDrawList(double liveScale, double liveBright) {
    this.drawLut[0] = this.liveLut;
    this.drawCx[0] = this.posX.getValue();
    this.drawCy[0] = this.posY.getValue();
    this.drawScale[0] = liveScale;
    this.drawRot[0] = this.liveRot;
    this.drawBright[0] = liveBright;
    this.drawMaxR[0] = this.liveMaxR;
    this.drawMinR[0] = this.liveMinR;
    this.drawCount = 1;

    double shrinkRate = lerp(.05, 1.6, this.shrink.getValue());
    double fadeTau = this.trail.getValue();
    double driftDist = lerp(0, .55, this.drift.getValue());
    double driftAngle = Math.toRadians(this.driftDir.getValue());
    double driftX = Math.cos(driftAngle) * driftDist;
    double driftY = Math.sin(driftAngle) * driftDist;
    double twistAmount = (this.twist.getValue() - .5) * 2 * TAU * .5;

    // The time constant the eased motions settle over. Tied to the fade, so a
    // copy finishes travelling at about the point it becomes invisible rather
    // than still visibly creeping when it is nearly gone.
    double easeTau = fadeTau * .8;

    for (int n = 0; n < this.stampCount; ++n) {
      // Newest first: walk backwards from the slot before the write head.
      int slot = (this.stampHead - 1 - n + MAX_SHAPES * 2) % MAX_SHAPES;
      double t = this.stampAge[slot];

      double brightness = this.stampBright[slot] * Math.exp(-t / fadeTau);
      double scale = this.stampScale[slot] * Math.exp(-shrinkRate * t);
      if (brightness < .002 || scale < .002) {
        // Faded or shrunk past anything the fixture can show. Skipping it here
        // costs the renderer nothing and keeps the front-to-back walk short.
        continue;
      }

      double ease = 1 - Math.exp(-t / easeTau);
      int at = this.drawCount++;
      this.drawLut[at] = this.stampLut[slot];
      this.drawCx[at] = this.stampCx[slot] + driftX * ease;
      this.drawCy[at] = this.stampCy[slot] + driftY * ease;
      this.drawScale[at] = scale;
      this.drawRot[at] = this.stampRot[slot] + twistAmount * ease;
      this.drawBright[at] = brightness;
      this.drawMaxR[at] = this.stampMaxR[slot];
      this.drawMinR[at] = this.stampMinR[slot];
    }
  }

  private void draw() {
    final double lvl = this.level.getValue() / 100;

    for (LXPoint p : this.model.points) {
      double px = p.xn;
      double py = p.yn;

      // Front to back, accumulating what each shape contributes through whatever
      // the shapes in front of it left uncovered. Only the strokes cover
      // anything, so a point inside a contour goes on to see every copy behind
      // it — which is the point of drawing outlines, and the reason both
      // rejection radii below earn their keep.
      double accum = 0;
      double remaining = 1;

      for (int i = 0; i < this.drawCount; ++i) {
        double scale = this.drawScale[i];
        double dx = (px - this.drawCx[i]) * this.aspectX;
        double dy = py - this.drawCy[i];
        double distSq = dx * dx + dy * dy;

        // Outside everything this contour can reach.
        double outer = this.drawMaxR[i] * scale + this.strokeBand;
        if (distSq > outer * outer) {
          continue;
        }
        // Deep enough inside to be clear of the stroke everywhere around. For a
        // hollow shape this is most of the area it covers, and skipping it here
        // is what keeps an outline no more expensive to draw than a fill was.
        double inner = this.drawMinR[i] * scale - this.strokeBand;
        if (inner > 0 && distSq < inner * inner) {
          continue;
        }

        double dist = Math.sqrt(distSq);
        double radius = sampleLut(this.drawLut[i],
          Math.atan2(dy, dx) - this.drawRot[i]) * scale;

        // Distance to the contour, measured along the ray from the center. That
        // is not quite the true distance to the curve — it understates it where
        // the contour runs steeply, so a stroke thickens a little at a flower's
        // waist and a triangle's corners. At this stroke width it reads as
        // weight in the corners rather than as error, and it saves a gradient
        // estimate per shape per LED.
        double offset = Math.abs(dist - radius);

        // The solid stroke, which is the only thing that occludes.
        double coverage = clamp(.5 - (offset - this.strokeHalf) / this.softWorld, 0, 1);

        // The halo outside it. Folded into the gap the stroke leaves rather than
        // added on top, so the core stays exactly the shape's own brightness and
        // turning Glow up spreads light outward instead of blowing the stroke
        // out.
        double value = coverage;
        if (this.glowAmount > 0 && coverage < 1) {
          double outside = offset - this.strokeHalf;
          if (outside < 0) {
            outside = 0;
          }
          value += (1 - coverage) * this.glowAmount * Math.exp(-outside / this.glowReach);
        }

        if (value <= 0) {
          continue;
        }

        accum += remaining * value * this.drawBright[i];
        // Only the stroke takes light away from what is behind it. A halo is
        // light, not surface, so it never hides the copies further back.
        remaining *= 1 - coverage;
        if (remaining < .004) {
          break;
        }
      }

      this.colors[p.index] = (accum <= 0)
        ? LXColor.BLACK
        : LXColor.gray(clamp(accum * lvl, 0, 1) * 100);
    }
  }

  private static double clamp(double v, double min, double max) {
    return (v < min) ? min : (v > max) ? max : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
