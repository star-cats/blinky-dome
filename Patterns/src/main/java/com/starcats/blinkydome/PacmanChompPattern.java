package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * PAC-MAN, ported from Scripts/PacmanChomp120.js.
 *
 * One scene: PAC-MAN at the origin with a line of {@link #DOT_COUNT} food dots
 * feeding into his mouth. Over one cycle the whole line advances DOT_COUNT
 * slots, so every dot is eaten by the end of it and the scene starts over with a
 * full line. One dot per chomp, one chomp per beat.
 *
 * The script hard-coded 123 BPM and read the wall clock, which meant it was in
 * time with the music only by coincidence and drifted out of it within a bar.
 * Here the chomp comes off the {@link PrimaryController}'s beat grid through a
 * {@link PrimaryController.Follower}, exactly as {@link StarCatPattern} does: it
 * free-runs at Free BPM when there is no controller and drifts onto the grid
 * when there is, so the jaws land with the kick without the animation ever
 * jumping to get there.
 *
 * Phase slides the whole animation against that grid. It is in beats, so -0.25
 * is a quarter beat early — worth having because "mouth shut" should land on the
 * transient, and where that sits depends on how the rest of the show is tuned.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Pacman")
@LXComponent.Description("PAC-MAN eating a line of dots, one per beat")
public class PacmanChompPattern extends LXPattern {

  /** Dots in the line, and therefore beats in one cycle of the scene. */
  private static final int DOT_COUNT = 4;

  private static final double PAC_X = .5;
  private static final double PAC_Y = .55;

  // Sizes at scale 1. The far dot sits at DOT_COUNT * DOT_SPACING, so the whole
  // line fits the frame, and PAC-MAN is about two slots across so the dots clear
  // his mouth.
  private static final double PAC_RADIUS = .2;
  private static final double DOT_SPACING = .3;
  private static final double DOT_RADIUS = .04;

  /** How wide the jaws open, in radians of half-angle. */
  private static final double MOUTH_OPEN = .82;

  /** Fraction of the cycle spent zooming, versus snapping back at the end. */
  private static final double ZOOM_FRACTION = .06;

  /** Time constant of the per-beat size pop, in beats. */
  private static final double POP_DECAY_BEATS = 4;

  /**
   * Nothing here. Alpha is zero, which no drawn colour has, so this doubles as
   * the "no scene at this point" answer without a second return value.
   */
  private static final int TRANSPARENT = 0;

  private static final int PAC_COLOR = LXColor.hsb(54, 100, 100);
  private static final int DOT_COLOR = LXColor.hsb(45, 20, 100);

  public final CompoundParameter angle =
    new CompoundParameter("Angle", .5, 0, 1)
    .setDescription("PAC-MAN facing angle");

  public final CompoundParameter phase =
    new CompoundParameter("Phase", 0, -.5, .5)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Slide the chomp earlier or later against the beat grid, in beats");

  public final BoundedParameter fallbackBpm =
    new BoundedParameter("Free BPM", 123, 40, 200)
    .setDescription("Tempo to animate at when there is no controller, or before it has found one");

  public final CompoundParameter sync =
    new CompoundParameter("Sync", 1, .1, 10)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("How long the scene takes to drift back onto the controller's beat grid; it never snaps");

  private final PrimaryController.Follower clock = new PrimaryController.Follower();

  // Per-frame state, worked out once in run() and read by every point.
  private double cycleT;
  private double scaleCycle;
  private double panD;
  private double panD2;
  private double theta;
  private double faceX;
  private double faceY;
  private double travel;
  private double mouthAngle;

  public PacmanChompPattern(LX lx) {
    super(lx);
    addParameter("angle", this.angle);
    addParameter("phase", this.phase);
    addParameter("fallbackBpm", this.fallbackBpm);
    addParameter("sync", this.sync);
  }

  @Override
  protected void run(double deltaMs) {
    this.clock.loop(deltaMs, this.fallbackBpm.getValue(), this.sync.getValue());
    layout();
    draw();
  }

  private void layout() {
    double beats = this.clock.getBeats() + this.phase.getValue();

    // Two clocks off the one beat position: where we are in the four-beat cycle,
    // and where we are within the current beat. The script derived both from
    // wall time and a fixed BPM; they are the same two numbers, now on the grid.
    this.cycleT = frac(beats / DOT_COUNT);
    double beatPhase = frac(beats);

    // The scene zooms slowly through the cycle and snaps back over the last
    // tenth of it, with a sharp pop on each beat riding on top.
    double pop = Math.exp(-beatPhase * POP_DECAY_BEATS) * .1;
    double zoom = pop
      + this.cycleT * ZOOM_FRACTION
      + smoothstep(.9, 1, this.cycleT) * (1 - ZOOM_FRACTION);
    this.scaleCycle = 1 + zoom / (5 * DOT_RADIUS);

    this.panD = this.cycleT * DOT_SPACING * this.scaleCycle;
    this.panD2 = (DOT_SPACING * (DOT_COUNT + 1) - DOT_SPACING * DOT_COUNT * this.cycleT)
      * this.scaleCycle;

    this.theta = this.angle.getValue() * Math.PI * 2;
    this.faceX = Math.cos(this.theta);
    this.faceY = Math.sin(this.theta);

    // Slots travelled so far. Dots are eaten on whole slots, and the jaws shut on
    // whole slots too, so PAC-MAN bites down exactly as a dot reaches him.
    this.travel = this.cycleT * DOT_COUNT;
    this.mouthAngle = MOUTH_OPEN * Math.abs(Math.sin(this.travel * Math.PI));
  }

  private void draw() {
    double nearPan = this.panD - this.panD2;

    for (LXPoint p : this.model.points) {
      double x = p.xn - PAC_X;
      double y = p.yn - PAC_Y;

      // Two copies of the same scene at different scales: the far one receding
      // and the near one arriving, which is what makes the zoom seamless.
      int near = renderScene(
        x + this.faceX * nearPan,
        y + this.faceY * nearPan,
        this.scaleCycle * (5 * DOT_RADIUS),
        true);
      if (near != TRANSPARENT) {
        this.colors[p.index] = near;
        continue;
      }

      int far = renderScene(
        x + this.faceX * this.panD,
        y + this.faceY * this.panD,
        this.scaleCycle,
        false);
      this.colors[p.index] = (far != TRANSPARENT) ? far : LXColor.BLACK;
    }
  }

  /**
   * Render one PAC-MAN scene, with PAC-MAN at the origin facing the Angle knob.
   *
   * @param x point to shade, relative to PAC-MAN
   * @param y point to shade, relative to PAC-MAN
   * @param scale size of the scene; 1 is full frame
   * @param retainDots keep the dots and PAC-MAN alive past the end of the cycle,
   *   which the incoming copy of the scene needs and the outgoing one must not
   * @return colour, or {@link #TRANSPARENT} where the scene is empty
   */
  private int renderScene(double x, double y, double scale, boolean retainDots) {
    double radius = PAC_RADIUS * scale;
    double spacing = DOT_SPACING * scale;
    double dotRadius = DOT_RADIUS * scale;

    if (retainDots || this.cycleT < .96) {
      if (x * x + y * y <= radius * radius
        && Math.abs(angleDelta(Math.atan2(y, x), this.theta)) >= this.mouthAngle) {
        return PAC_COLOR;
      }
    }

    // Dot k starts in slot k and rides in to the mouth. Spacing is wider than a
    // dot, so only the nearest slot can cover this point.
    long slot = Math.round((x * this.faceX + y * this.faceY) / spacing + this.travel);
    if (!retainDots && (slot < 1 || slot > DOT_COUNT)) {
      return TRANSPARENT;
    }
    double center = (slot - this.travel) * spacing;
    if (center <= 0) {
      return TRANSPARENT;
    }
    double ox = x - this.faceX * center;
    double oy = y - this.faceY * center;
    return (ox * ox + oy * oy <= dotRadius * dotRadius) ? DOT_COLOR : TRANSPARENT;
  }

  private static double angleDelta(double a, double b) {
    double d = a - b;
    while (d > Math.PI) {
      d -= Math.PI * 2;
    }
    while (d < -Math.PI) {
      d += Math.PI * 2;
    }
    return d;
  }

  private static double frac(double v) {
    return v - Math.floor(v);
  }

  private static double smoothstep(double edge0, double edge1, double x) {
    double t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
    return t * t * (3 - 2 * t);
  }
}
