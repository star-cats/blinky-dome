package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Lava lamp metaballs, ported from Scripts/LavaLamp.js.
 *
 * A field of blobs, each contributing an inverse-fourth-power falloff to every
 * point. Where the sum crosses a threshold the point is inside the lava; the
 * crossing is smoothstepped rather than cut, which is what makes the blobs read
 * as fluid meeting and parting instead of as circles overlapping.
 *
 * There is no beat here and deliberately so — a lava lamp that moved in time
 * with the music would stop being a lava lamp. It runs off its own accumulated
 * clock rather than wall time, so Speed changes ease in instead of jumping the
 * blobs to wherever the new rate says they should have been.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Lava Lamp")
@LXComponent.Description("Metaball lava lamp")
public class LavaLampPattern extends LXPattern {

  private static final int MAX_BLOBS = 16;

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .35, 0, 1)
    .setDescription("Blob movement speed");

  public final CompoundParameter count =
    new CompoundParameter("Count", .45, 0, 1)
    .setDescription("Number of blobs");

  public final CompoundParameter size =
    new CompoundParameter("Size", .45, 0, 1)
    .setDescription("Blob field threshold");

  public final CompoundParameter motion =
    new CompoundParameter("Motion", .5, 0, 1)
    .setDescription("Blob travel range");

  public final CompoundParameter contrast =
    new CompoundParameter("Contrast", .65, 0, 1)
    .setDescription("Blob edge-to-center luminance contrast");

  public final CompoundParameter bg =
    new CompoundParameter("Bg", .18, 0, 1)
    .setDescription("Background luminance");

  public final CompoundParameter aspect =
    new CompoundParameter("Aspect", .5, 0, 1)
    .setDescription("Vertical aspect correction");

  public final CompoundParameter level =
    new CompoundParameter("Level", .8, 0, 1)
    .setDescription("Overall brightness");

  /**
   * Seconds since the pattern loaded.
   *
   * Integrated from deltaMs rather than read off the wall clock, which is what
   * the script did. Wall time makes Speed a discontinuity: turning it up
   * multiplies a number that is already in the millions, and every blob teleports.
   */
  private double clock = 0;

  // Per-frame values, so the knob mappings happen once a frame and not once per
  // LED. blobX/blobY/blobR hold the whole blob field for this frame.
  private final double[] blobX = new double[MAX_BLOBS];
  private final double[] blobY = new double[MAX_BLOBS];
  private final double[] blobR = new double[MAX_BLOBS];
  private int blobCount = 0;
  private double threshold = 1;
  private double aspectCorrection = 1;

  public LavaLampPattern(LX lx) {
    super(lx);
    addParameter("speed", this.speed);
    addParameter("count", this.count);
    addParameter("size", this.size);
    addParameter("motion", this.motion);
    addParameter("contrast", this.contrast);
    addParameter("bg", this.bg);
    addParameter("aspect", this.aspect);
    addParameter("level", this.level);
  }

  @Override
  protected void run(double deltaMs) {
    if (Double.isFinite(deltaMs)) {
      this.clock += deltaMs * .001;
    }
    layout();
    draw();
  }

  private void layout() {
    this.blobCount = Math.max(1, (int) Math.round(lerp(2, MAX_BLOBS, this.count.getValue())));
    this.threshold = lerp(8000, 800, this.size.getValue());
    this.aspectCorrection = lerp(.5, 1.5, this.aspect.getValue());

    // The script's blob clock ran five times its own time base; kept so the
    // motion matches the original at the same knob settings.
    double time = this.clock * 5;
    double spd = lerp(.05, .55, this.speed.getValue());
    double moveRange = lerp(.08, .75, this.motion.getValue());

    for (int i = 0; i < this.blobCount; ++i) {
      double cx = .5 + .1 * rand(i);
      double cy = .5 + .1 * rand(i + 42);
      cx += moveRange * Math.sin((spd + rand(i) * .1) * time * rand(i + 2)) * rand(i + 56);
      // The original indexes rand() by a bare +17 here rather than by the blob
      // index. It is almost certainly a typo for i + 17, but it is load-bearing:
      // it makes every blob share one vertical rate, and that is the motion the
      // knobs were tuned against. Left as it was.
      cy += moveRange * -Math.sin((spd + rand(17) * .2) * time) * rand(i * 9);
      this.blobX[i] = cx;
      this.blobY[i] = cy * this.aspectCorrection;
      this.blobR[i] = .1 * Math.abs(rand(i + 3));
    }
  }

  private void draw() {
    final double lvl = this.level.getValue();
    final double contrastAmount = this.contrast.getValue();
    final double background = lvl * this.bg.getValue() * 100;

    for (LXPoint p : this.model.points) {
      double uvX = p.xn;
      double uvY = p.yn * this.aspectCorrection;
      double distSum = 0;

      for (int i = 0; i < this.blobCount; ++i) {
        double dx = this.blobX[i] - uvX;
        double dy = this.blobY[i] - uvY;
        double d = Math.max(Math.sqrt(dx * dx + dy * dy) + this.blobR[i] / 2, 0);
        double sq = d * d;
        distSum += 1 / (sq * sq);
      }

      if (distSum > this.threshold) {
        double t = smoothstep(this.threshold, 0, distSum - this.threshold);
        this.colors[p.index] = LXColor.gray(lvl * lerp(100, 100 * (1 - contrastAmount), t));
      } else {
        this.colors[p.index] = LXColor.gray(background);
      }
    }
  }

  /**
   * The script's deterministic stand-in for a random number.
   *
   * Not random at all — just sin() of a scaled index — but it is stable across
   * frames without storing anything, which is the whole requirement. Its exact
   * values are part of the look, so it stays as it was rather than becoming a
   * real hash.
   */
  private static double rand(int i) {
    return Math.sin(i * 1.64);
  }

  private static double smoothstep(double edge0, double edge1, double x) {
    double t = clamp((x - edge0) / (edge1 - edge0));
    return t * t * (3 - 2 * t);
  }

  private static double clamp(double v) {
    return (v < 0) ? 0 : (v > 1) ? 1 : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
