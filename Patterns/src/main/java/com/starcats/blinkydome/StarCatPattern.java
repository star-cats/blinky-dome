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
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * A cat face — circle head, outlined ears, two slanted almond eyes, a third eye
 * up top, a nose — that bounces, squashes and changes colour on the beat.
 *
 * Everything renders in one hue; only brightness varies. It is a deliberately
 * simplified silhouette rather than a literal render, because the panel this was
 * drawn for is 40 columns by 60 rows and the columns are about three times
 * sparser than the rows. Fine detail does not survive that pitch. Every edge is
 * anti-aliased with a soft falloff rather than a hard cutoff for the same
 * reason: a crisp one-pixel outline would fall between columns and vanish.
 *
 * The tempo and the beat grid come from the {@link PrimaryController} via
 * {@link MoodState}, so the cat is on the same clock as everything else in the
 * show and there is no per-pattern beat tracking to tune. Its own clock
 * free-runs and is drifted onto the controller's rather than being set from it,
 * so nothing here ever jumps; see {@link #advanceClock}. With no controller in
 * the project, or before it has worked out a tempo, that free run is all there
 * is, at {@link #fallbackBpm}.
 *
 * Geometry is expressed in units of the face radius and the radius itself as a
 * fraction of the model, so this renders the same shape on any model rather than
 * only on the panel it was drawn for.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Starcat")
@LXComponent.Description("Cat face that bounces, squashes and changes colour on the beat")
public class StarCatPattern extends LXPattern {

  private static final double TWO_PI = 2 * Math.PI;

  /*
   * Feature positions and sizes, in units of the face radius R, centred on the
   * face: (0,0) is the middle of the head, +y is up, so y=+1 is the top of the
   * head circle and y=-1 the bottom.
   */

  private static final double EYE_X = -.50;
  private static final double EYE_Y = -.32;
  private static final double EYE_A = .34;
  private static final double EYE_B = .2;
  private static final double EYE_TILT = -20;

  private static final double THIRD_EYE_X = 0;
  private static final double THIRD_EYE_Y = .42;
  /** Half-length of each arm of the third eye's asterisk. */
  private static final double THIRD_EYE_ARM = .3;

  /*
   * The left ear, drawn as just its two outer sides: base corner up to the apex,
   * twice. No bottom edge — that is where the ear meets the head circle, and a
   * stroke there would read as a line across the face. The right ear is this one
   * mirrored in x.
   */
  private static final double EAR_BASE1_X = -1.2, EAR_BASE1_Y = 0;
  private static final double EAR_BASE2_X = -.22, EAR_BASE2_Y = .9;
  private static final double EAR_APEX_X = -1.0, EAR_APEX_Y = 1.3;

  private static final double NOSE_X = 0;
  private static final double NOSE_Y = -.62;
  private static final double NOSE_R = .05;

  /**
   * Radius, in R units, beyond which no feature can reach — the ear apex is the
   * furthest thing out, at 1.803. Points past this get black without evaluating
   * the mask stack, which is most of the model most of the time.
   */
  private static final double FEATURE_BOUNDS = 1.85;

  /** How far the eyes and nose slide across the face at full manual gaze, in R units. */
  private static final double LOOK_RANGE = .35;

  /**
   * Radians the bounce runs ahead of the beat. The cat should be arriving at the
   * bottom of its drop as the kick lands, not starting to move once it has
   * already been heard. Small, because the controller's Shift already handles
   * the bulk of that alignment for the whole show.
   */
  private static final double PHASE_LEAD = -.1;

  /** Fraction of a beat by which the colour change leads the beat itself. */
  private static final double HUE_LEAD = .3 / TWO_PI;

  /** Time constant of the beat swell falling back to the resting size. */
  private static final double POP_DECAY_MS = 200;

  // --- Colour ----------------------------------------------------------------

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 200, 0, 360)
    .setUnits(LXParameter.Units.DEGREES)
    .setDescription("Base hue; Beat Hue rotates away from this");

  public final BooleanParameter beatHue =
    new BooleanParameter("Beat Hue", true)
    .setDescription("Jump to a new hue on every beat, instead of holding the Hue knob");

  public final CompoundParameter saturation =
    new CompoundParameter("Sat", 100, 0, 100)
    .setUnits(LXParameter.Units.PERCENT)
    .setDescription("Saturation; at 0 the cat is white and the hue does nothing");

  public final CompoundParameter level =
    new CompoundParameter("Level", 100, 0, 100)
    .setUnits(LXParameter.Units.PERCENT)
    .setDescription("Overall brightness");

  // --- Shape -----------------------------------------------------------------

  public final CompoundParameter size =
    new CompoundParameter("Size", .356, .05, 1)
    .setDescription("Resting face radius, as a fraction of the model's height");

  public final CompoundParameter lineWidth =
    new CompoundParameter("Line", .086, .01, .4)
    .setDescription("Stroke half-width, as a fraction of the face radius");

  public final CompoundParameter glow =
    new CompoundParameter("Glow", .029, .005, .3)
    .setDescription("Edge softness, as a fraction of the face radius -- this is the anti-aliasing");

  public final CompoundParameter centerY =
    new CompoundParameter("Pos Y", .65, 0, 1)
    .setDescription("Resting face centre, as a fraction of the model's height below the top");

  // --- Motion ----------------------------------------------------------------

  public final CompoundParameter bounce =
    new CompoundParameter("Bounce", .1, 0, .5)
    .setDescription("How far the cat hops each beat, as a fraction of the model's height");

  public final CompoundParameter sway =
    new CompoundParameter("Sway", .15, 0, .5)
    .setDescription("How far the cat drifts side to side, as a fraction of the model's width");

  public final CompoundParameter squash =
    new CompoundParameter("Squash", 1, 0, 2)
    .setDescription("How much the head squashes and the ears lag through the hop");

  public final CompoundParameter pop =
    new CompoundParameter("Pop", .14, 0, 1)
    .setDescription("How much the whole face swells on each beat");

  public final CompoundParameter lookX =
    new CompoundParameter("Look X", 0, -1, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Pans the eyes and nose across the face, on top of the automatic gaze");

  public final CompoundParameter lookY =
    new CompoundParameter("Look Y", 0, -1, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Tilts the eyes and nose across the face, on top of the automatic gaze");

  public final BoundedParameter fallbackBpm =
    new BoundedParameter("Free BPM", 120, 40, 200)
    .setDescription("Tempo to animate at when there is no controller, or before it has found one");

  public final CompoundParameter sync =
    new CompoundParameter("Sync", 1, .1, 10)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("How long the cat takes to drift back onto the controller's beat grid; it never snaps");

  // --- State -----------------------------------------------------------------

  /**
   * The animation clock, in beats. One beat is one full turn of the bounce, so
   * everything below is a function of this and nothing reads wall time directly.
   */
  private double beats = 0;

  private long lastBeatIndex = Long.MIN_VALUE;

  /** Hue rotation accumulated by the beat colour changes, in turns. */
  private double hueOffset = 0;

  /** How far into the beat swell we are, decaying to 0 between beats. */
  private double swell = 0;

  private final Random random = new Random();

  // Per-frame render state, worked out once in run() and read by every point.
  private double faceR;
  private double faceX;
  private double faceY;
  private double strokeHalfWidth;
  private double softness;
  private double squishY;
  private double earLagY;
  private double gazeX;
  private double gazeY;

  public StarCatPattern(LX lx) {
    super(lx);
    addParameter("hue", this.hue);
    addParameter("beatHue", this.beatHue);
    addParameter("saturation", this.saturation);
    addParameter("level", this.level);
    addParameter("size", this.size);
    addParameter("lineWidth", this.lineWidth);
    addParameter("glow", this.glow);
    addParameter("centerY", this.centerY);
    addParameter("bounce", this.bounce);
    addParameter("sway", this.sway);
    addParameter("squash", this.squash);
    addParameter("pop", this.pop);
    addParameter("lookX", this.lookX);
    addParameter("lookY", this.lookY);
    addParameter("fallbackBpm", this.fallbackBpm);
    addParameter("sync", this.sync);
  }

  @Override
  protected void run(double deltaMs) {
    advanceClock(deltaMs);
    onBeat();
    layout(deltaMs);
    draw();
  }

  /**
   * Moves the animation clock, following the {@link PrimaryController}.
   *
   * The clock always free-runs, at the controller's tempo when it has one. It is
   * never assigned the controller's position, only pulled toward it -- a cat is
   * a physical object and it cannot teleport. A snap onto the grid, however
   * small, reads as the animation glitching rather than as the beat arriving,
   * and the moments the controller most wants to correct in -- a tempo change,
   * the first bars of a new track -- are exactly the moments a viewer is
   * watching. So the error is closed over {@link #sync} seconds instead.
   *
   * The correction is taken modulo one beat, toward whichever boundary is
   * nearer, so the worst case to drift across is half a beat and the absolute
   * beat count never matters. Reading the count rather than watching a per-frame
   * flag keeps this right whichever order the engine runs the pattern and the
   * modulator in.
   */
  private void advanceClock(double deltaMs) {
    PrimaryController controller = MoodState.get();
    BeatClock clock = (controller != null) ? controller.getClock() : null;

    // Free-running is the normal case, not the fallback: with no tempo to follow
    // the cat keeps hopping rather than freezing mid-air, which would read as a
    // crash instead of as a pause.
    double bpm = (controller != null && controller.getBpm() > 0)
      ? controller.getBpm()
      : this.fallbackBpm.getValue();
    this.beats += deltaMs * .001 * bpm / 60;

    if (clock == null || !clock.hasTempo()) {
      return;
    }

    // Both clocks run at the same tempo, so this error is a standing offset the
    // drift below can actually close, rather than something it has to chase.
    double error = controller.getBeatCount() + clock.getOutputPhase() - this.beats;
    error -= Math.floor(error);
    if (error > .5) {
      error -= 1;
    }
    // Exponential, so the correction eases in and out instead of arriving at a
    // hard stop. Most of the way in one Sync, the rest shortly after.
    double tauMs = this.sync.getValue() * 1000;
    this.beats += error * (1 - Math.exp(-deltaMs / tauMs));
  }

  /** Colour change and size swell, once per beat of the clock above. */
  private void onBeat() {
    long beatIndex = (long) Math.floor(this.beats + HUE_LEAD);
    // Forward crossings only. The drift correction can walk the clock back over
    // a boundary it just crossed, and re-crossing should not spend a second
    // colour on the same beat.
    if (beatIndex <= this.lastBeatIndex) {
      return;
    }
    boolean first = (this.lastBeatIndex == Long.MIN_VALUE);
    this.lastBeatIndex = beatIndex;
    if (first) {
      return;
    }
    // A random walk rather than a cycle: the minimum step is wide enough that
    // consecutive beats never read as the same colour, and the random part stops
    // the sequence itself from becoming predictable over a long set.
    this.hueOffset = (this.hueOffset + .35 + this.random.nextDouble() * .4) % 1;
    this.swell = this.pop.getValue();
  }

  /** Everything that is per-frame rather than per-point. */
  private void layout(double deltaMs) {
    // Exponential rather than a fixed fraction per frame, so the swell falls at
    // the same rate whatever the frame rate is doing.
    this.swell -= this.swell * (1 - Math.exp(-deltaMs / POP_DECAY_MS));

    double phase = this.beats * TWO_PI;
    double squashAmount = this.squash.getValue();

    // |sin| rather than sin: the cat bounces off the floor once a beat instead
    // of swinging smoothly through it.
    double hop = Math.abs(Math.sin(phase / 2 + PHASE_LEAD));
    double drift = Math.cos(phase / 8);

    // The head squashes on impact and the ears keep travelling for a moment
    // after it -- the lag is the same curve a little later in the beat.
    this.squishY = Math.abs(Math.cos(phase / 2 + PHASE_LEAD + .4)) * .6 * squashAmount;
    double squishLag = Math.abs(Math.cos(phase / 2 + PHASE_LEAD + 1.8)) * .7 * squashAmount;
    this.earLagY = -squishLag * .5;

    this.faceR = this.size.getValue() * this.model.yRange * (1 + this.swell);
    if (this.faceR <= 0) {
      this.faceR = 1;
    }
    this.faceX = this.model.cx + drift * this.sway.getValue() * this.model.xRange;
    this.faceY = this.model.yMax
      - (this.centerY.getValue() - hop * this.bounce.getValue()) * this.model.yRange;

    this.strokeHalfWidth = this.lineWidth.getValue();
    this.softness = Math.max(.001, this.glow.getValue());

    // The eyes drift on the same clock as the body, a little out of step with
    // it, so the gaze wanders instead of staring dead ahead. Manual Look adds on
    // top. This is a linear slide; real spherical foreshortening, where features
    // near the rim move less, would be the next thing to try.
    this.gazeX = Math.cos(phase / 8 - 1.4) * .2 + this.lookX.getValue() * LOOK_RANGE;
    this.gazeY = (Math.abs(Math.sin(phase / 2 + PHASE_LEAD - .1)) - .3) * .25
      + this.lookY.getValue() * LOOK_RANGE;
  }

  private void draw() {
    final double r = this.faceR;
    final double soft = this.softness;
    final double halfWidth = this.strokeHalfWidth;
    final double level = this.level.getValue() / 100;
    final double sat = this.saturation.getValue();
    final double hue = normalizeDegrees(this.hue.getValue()
      + (this.beatHue.isOn() ? this.hueOffset * 360 : 0));

    // Everything the eyes and nose share: the gaze slide, plus a little dip as
    // the head squashes under them.
    final double eyeDropY = this.gazeY - this.squishY * .15;
    final double thirdEyeX = THIRD_EYE_X + this.gazeX;
    final double thirdEyeY = THIRD_EYE_Y + this.gazeY - this.squishY * .35;
    // Asterisk arms at 0, 60 and 120 degrees, so the two diagonals are half the
    // arm length across and sqrt(3)/2 of it up.
    final double armX = THIRD_EYE_ARM * .5;
    final double armY = THIRD_EYE_ARM * .8660254;

    final double earApexY = EAR_APEX_Y + this.earLagY;
    final double boundsSq = (FEATURE_BOUNDS + halfWidth + soft) * (FEATURE_BOUNDS + halfWidth + soft);

    for (LXPoint p : this.model.points) {
      double x = (p.x - this.faceX) / r;
      double y = (p.y - this.faceY) / r;

      if (x * x + y * y > boundsSq) {
        // Nothing can reach out here, so skip the whole mask stack -- which on a
        // model much bigger than the face is nearly every point.
        this.colors[p.index] = LXColor.BLACK;
        continue;
      }

      double mask = ringMask(x, y, 1 + this.squishY, halfWidth, soft);

      mask = Math.max(mask, segmentMask(x, y, EAR_BASE1_X, EAR_BASE1_Y, EAR_APEX_X, earApexY, halfWidth, soft));
      mask = Math.max(mask, segmentMask(x, y, EAR_BASE2_X, EAR_BASE2_Y, EAR_APEX_X, earApexY, halfWidth, soft));
      mask = Math.max(mask, segmentMask(x, y, -EAR_BASE1_X, EAR_BASE1_Y, -EAR_APEX_X, earApexY, halfWidth, soft));
      mask = Math.max(mask, segmentMask(x, y, -EAR_BASE2_X, EAR_BASE2_Y, -EAR_APEX_X, earApexY, halfWidth, soft));

      // Both eyes pan the same way across the screen; the right eye's centre is
      // the mirror of the left, so its offset takes the opposite sign.
      mask = Math.max(mask, ellipseMask(x - (EYE_X + this.gazeX), y - (EYE_Y + eyeDropY), EYE_TILT, EYE_A, EYE_B, soft));
      mask = Math.max(mask, ellipseMask(x + EYE_X - this.gazeX, y - (EYE_Y + eyeDropY), -EYE_TILT, EYE_A, EYE_B, soft));

      mask = Math.max(mask, segmentMask(x, y, thirdEyeX - THIRD_EYE_ARM, thirdEyeY, thirdEyeX + THIRD_EYE_ARM, thirdEyeY, halfWidth, soft));
      mask = Math.max(mask, segmentMask(x, y, thirdEyeX - armX, thirdEyeY - armY, thirdEyeX + armX, thirdEyeY + armY, halfWidth, soft));
      mask = Math.max(mask, segmentMask(x, y, thirdEyeX + armX, thirdEyeY - armY, thirdEyeX - armX, thirdEyeY + armY, halfWidth, soft));

      mask = Math.max(mask, circleMask(x - (NOSE_X + this.gazeX), y - (NOSE_Y + this.gazeY), NOSE_R, soft));

      this.colors[p.index] = LXColor.hsb(hue, sat, clamp(mask * level) * 100);
    }
  }

  /*
   * The mask primitives. Each returns 0-1 coverage for one point against one
   * shape, and they compose with max() -- a union, so overlapping strokes stay
   * the same brightness as a single one rather than blowing out where they meet.
   *
   * Every one of them ramps over `soft` instead of thresholding. That is the
   * whole reason this reads at all on a sparse fixture: a hard edge lands
   * between columns and disappears, while a ramp puts a dim pixel either side.
   */

  /** Filled disc of radius r. */
  private static double circleMask(double dx, double dy, double r, double soft) {
    double d = Math.sqrt(dx * dx + dy * dy) / r;
    return clamp(1 - (d - 1) / soft);
  }

  /**
   * The head: a unit-radius outline, squashed vertically by `squish` and spread
   * horizontally to match, so the head keeps roughly its area as it deforms.
   */
  private static double ringMask(double dx, double dy, double squish, double halfWidth, double soft) {
    double d = Math.sqrt(dx * dx * 1.1 / (1 + .5 * squish) + dy * dy * squish);
    return clamp(1 - (Math.abs(d - 1) - halfWidth) / soft);
  }

  /** Filled ellipse with semi-axes a and b, rotated by angleDeg. Used for the eyes. */
  private static double ellipseMask(double dx, double dy, double angleDeg, double a, double b, double soft) {
    double rad = -Math.toRadians(angleDeg);
    double c = Math.cos(rad), s = Math.sin(rad);
    double rx = dx * c - dy * s;
    double ry = dx * s + dy * c;
    double d = Math.sqrt((rx / a) * (rx / a) + (ry / b) * (ry / b));
    return clamp(1 - (d - 1) / soft);
  }

  /** Stroke along the segment a-b: distance to the segment, same falloff as the ring. */
  private static double segmentMask(double px, double py, double ax, double ay, double bx, double by, double halfWidth, double soft) {
    double ex = bx - ax, ey = by - ay;
    double lenSq = ex * ex + ey * ey;
    double t = (lenSq > 0) ? clamp(((px - ax) * ex + (py - ay) * ey) / lenSq) : 0;
    double dx = px - (ax + t * ex);
    double dy = py - (ay + t * ey);
    return clamp(1 - (Math.sqrt(dx * dx + dy * dy) - halfWidth) / soft);
  }

  private static double clamp(double v) {
    return (v < 0) ? 0 : (v > 1) ? 1 : v;
  }

  private static double normalizeDegrees(double degrees) {
    double wrapped = degrees % 360;
    return (wrapped < 0) ? wrapped + 360 : wrapped;
  }
}
