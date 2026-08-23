package com.starcats.blinkydome;

import java.util.Arrays;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Two dragonfish, ported from a compact Processing sketch. Ported from
 * Scripts/DragonFish.js.
 *
 * The source interleaves two fish with {@code i % 2}. Both genuine strands are
 * kept: their geometry and native motion are calculated first, then optional
 * scene transforms are applied around their live centroids. Points are
 * accumulated into a luminance buffer so the original translucent drawing
 * remains visible when sampled by a sparse LED model.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Dragon Fish")
@LXComponent.Description("Two point-cloud dragonfish swimming with the original sketch's motion")
public class DragonFishPattern extends LXPattern {

  private static final int POINT_COUNT = 20000;
  private static final int FISH_COUNT = 2;
  private static final int POINTS_PER_FISH = POINT_COUNT / FISH_COUNT;

  private static final int GRID_SIZE = 256;
  private static final int GRID_LAST = GRID_SIZE - 1;
  private static final int GRID_CELLS = GRID_SIZE * GRID_SIZE;

  // PI / 80 per Processing draw at 60 fps.
  private static final double TIME_RATE = Math.PI * .75;
  // Every time-dependent term returns exactly to its starting value after 12 PI.
  private static final double TIME_PERIOD = Math.PI * 12;

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .5, 0, 1)
    .setDescription("Speed of the original swimming and body motion");

  public final CompoundParameter size =
    new CompoundParameter("Size", .5, 0, 1)
    .setDescription("Scale of each fish around its moving center");

  public final CompoundParameter separation =
    new CompoundParameter("Separation", .5, 0, 1)
    .setDescription("Distance between the fish; center preserves the original spacing");

  public final CompoundParameter rotation =
    new CompoundParameter("Rotation", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Rotate the complete two-fish scene; center is upright");

  public final CompoundParameter centerX =
    new CompoundParameter("Center X", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Horizontal position of the complete scene");

  public final CompoundParameter centerY =
    new CompoundParameter("Center Y", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Vertical position of the complete scene");

  public final CompoundParameter glow =
    new CompoundParameter("Glow", .375, 0, 1)
    .setDescription("Radius of the point-cloud glow");

  public final CompoundParameter opacity =
    new CompoundParameter("Opacity", .61, 0, 1)
    .setDescription("Opacity of each accumulated point");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 1)
    .setDescription("Dragonfish color");

  public final CompoundParameter saturation =
    new CompoundParameter("Saturation", 0, 0, 1)
    .setDescription("Color saturation; zero is the original white");

  public final CompoundParameter background =
    new CompoundParameter("Background", .035, 0, 1)
    .setDescription("Background brightness");

  public final CompoundParameter level =
    new CompoundParameter("Level", 1, 0, 1)
    .setDescription("Overall output brightness");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Keep the fish proportional on non-square models");

  private double[] ink = null;
  private double[] pointRadius = null;
  private double[] pointD2 = null;
  private double[] pointStaticY = null;
  private double[] pointCosC = null;
  private double[] pointSinC = null;
  private double[] pointCosC3 = null;
  private double[] pointSinC3 = null;
  private double[] pointCosD = null;
  private double[] pointSinD = null;

  // Coefficient sums resolve each fish's live centroid without a separate pass.
  private final double[] centerXCos = new double[FISH_COUNT];
  private final double[] centerXSin = new double[FISH_COUNT];
  private final double[] centerYSinC3 = new double[FISH_COUNT];
  private final double[] centerYCosC3 = new double[FISH_COUNT];
  private final double[] centerYD2CosD = new double[FISH_COUNT];
  private final double[] centerYD2SinD = new double[FISH_COUNT];
  private final double[] centerYStatic = new double[FISH_COUNT];

  private double time = 0;
  private double aspectX = 1;

  // Values derived from controls once per frame.
  private double fishScale = 1;
  private double separationScale = 1;
  private double cosRotation = 1;
  private double sinRotation = 0;
  private double panX = 0;
  private double panY = 0;
  private double glowOffset = 2.25;
  private double pointTransmission = 1 - 144. / 255;
  private double outputBackground = 9. / 255;
  private double outputLevel = 1;

  public DragonFishPattern(LX lx) {
    super(lx);
    addParameter("speed", this.speed);
    addParameter("size", this.size);
    addParameter("separation", this.separation);
    addParameter("rotation", this.rotation);
    addParameter("centerX", this.centerX);
    addParameter("centerY", this.centerY);
    addParameter("glow", this.glow);
    addParameter("opacity", this.opacity);
    addParameter("hue", this.hue);
    addParameter("saturation", this.saturation);
    addParameter("background", this.background);
    addParameter("level", this.level);
    addParameter("autoAspect", this.autoAspect);
  }

  /**
   * Allocates the buffers and tabulates the index-only terms on first use.
   *
   * Deferred rather than done in the constructor: a project holds every pattern
   * it has ever been given, and only one of them is drawing.
   */
  private void init() {
    this.ink = new double[GRID_CELLS];
    this.pointRadius = new double[POINT_COUNT];
    this.pointD2 = new double[POINT_COUNT];
    this.pointStaticY = new double[POINT_COUNT];
    this.pointCosC = new double[POINT_COUNT];
    this.pointSinC = new double[POINT_COUNT];
    this.pointCosC3 = new double[POINT_COUNT];
    this.pointSinC3 = new double[POINT_COUNT];
    this.pointCosD = new double[POINT_COUNT];
    this.pointSinD = new double[POINT_COUNT];

    // Everything here depends only on i. Pulling it out of the frame loop keeps
    // the full 20,000-point source affordable without changing its equations.
    for (int i = 0; i < POINT_COUNT; ++i) {
      double y = i / 663.;
      double k = (4 + Math.cos(y)) * Math.cos(i);
      double e = y / 5 - 11;
      double d = Math.sqrt(k * k + e * e) - 5;
      double baseC = d / 2.5 + (i % 2) * 8;
      this.pointRadius[i] = 79 + k * k;
      this.pointD2[i] = d * d;
      this.pointStaticY[i] = 3 * Math.sin(k * 2) +
        Math.sin(y / 9 + 6) * k * (e + Math.sin(e * 4 - d * 4));
      this.pointCosC[i] = Math.cos(baseC);
      this.pointSinC[i] = Math.sin(baseC);
      this.pointCosC3[i] = Math.cos(baseC / 3);
      this.pointSinC3[i] = Math.sin(baseC / 3);
      this.pointCosD[i] = Math.cos(d);
      this.pointSinD[i] = Math.sin(d);

      int fish = i % 2;
      this.centerXCos[fish] += this.pointRadius[i] * this.pointCosC[i];
      this.centerXSin[fish] += this.pointRadius[i] * this.pointSinC[i];
      this.centerYSinC3[fish] += this.pointSinC3[i];
      this.centerYCosC3[fish] += this.pointCosC3[i];
      this.centerYD2CosD[fish] += this.pointD2[i] * this.pointCosD[i];
      this.centerYD2SinD[fish] += this.pointD2[i] * this.pointSinD[i];
      this.centerYStatic[fish] += this.pointStaticY[i];
    }
  }

  @Override
  protected void run(double deltaMs) {
    if (this.ink == null) {
      init();
    }

    double dt = Double.isFinite(deltaMs) ? Math.max(0, Math.min(deltaMs / 1000, .25)) : 0;
    this.time += dt * TIME_RATE * this.speed.getValue() * 2;
    if (this.time >= TIME_PERIOD) {
      this.time -= Math.floor(this.time / TIME_PERIOD) * TIME_PERIOD;
    }

    this.fishScale = .4 + this.size.getValue() * 1.2;
    this.separationScale = this.separation.getValue() * 2;
    double angle = (this.rotation.getValue() - .5) * Math.PI * 2;
    this.cosRotation = Math.cos(angle);
    this.sinRotation = Math.sin(angle);
    this.panX = (this.centerX.getValue() - .5) * 1.2;
    this.panY = (this.centerY.getValue() - .5) * 1.2;
    this.glowOffset = this.glow.getValue() * 6;
    this.pointTransmission = 1 - (.05 + this.opacity.getValue() * .85);
    this.outputBackground = this.background.getValue();
    this.outputLevel = this.level.getValue();

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    Arrays.fill(this.ink, 0);

    // Expand the three time-shifted trig functions once. Their per-point phases
    // were tabulated in init(), turning 60,000 trig calls into six per frame.
    double cosHalfTime = Math.cos(this.time / 2);
    double sinHalfTime = Math.sin(this.time / 2);
    double cosSixthTime = Math.cos(this.time / 6);
    double sinSixthTime = Math.sin(this.time / 6);
    double cosDoubleTime = Math.cos(this.time * 2);
    double sinDoubleTime = Math.sin(this.time * 2);

    double fishCenterX0 = .5 +
      (this.centerXCos[0] * cosHalfTime + this.centerXSin[0] * sinHalfTime) /
      (400. * POINTS_PER_FISH);
    double fishCenterX1 = .5 +
      (this.centerXCos[1] * cosHalfTime + this.centerXSin[1] * sinHalfTime) /
      (400. * POINTS_PER_FISH);
    double fishCenterY0 = .5 + (
      99 * (this.centerYSinC3[0] * cosSixthTime - this.centerYCosC3[0] * sinSixthTime) +
      this.centerYD2CosD[0] * sinDoubleTime - this.centerYD2SinD[0] * cosDoubleTime +
      this.centerYStatic[0]
    ) / (400. * POINTS_PER_FISH);
    double fishCenterY1 = .5 + (
      99 * (this.centerYSinC3[1] * cosSixthTime - this.centerYCosC3[1] * sinSixthTime) +
      this.centerYD2CosD[1] * sinDoubleTime - this.centerYD2SinD[1] * cosDoubleTime +
      this.centerYStatic[1]
    ) / (400. * POINTS_PER_FISH);

    // This is the original formula, expanded and normalized from 400 pixels to
    // 0..1. Even and odd indices remain the two separate dragonfish.
    for (int i = 0; i < POINT_COUNT; ++i) {
      double cosC = this.pointCosC[i] * cosHalfTime + this.pointSinC[i] * sinHalfTime;
      double sinC3 = this.pointSinC3[i] * cosSixthTime - this.pointCosC3[i] * sinSixthTime;
      double sinMotion = sinDoubleTime * this.pointCosD[i] - cosDoubleTime * this.pointSinD[i];
      double sourceX = (this.pointRadius[i] * cosC + 200) / 400;
      double sourceY = (99 * sinC3 + 200 +
        this.pointD2[i] * sinMotion + this.pointStaticY[i]) / 400;
      double fishCenterX = (i % 2 == 0) ? fishCenterX0 : fishCenterX1;
      double fishCenterY = (i % 2 == 0) ? fishCenterY0 : fishCenterY1;

      // Size acts locally on each fish. Separation acts only on their moving
      // centers, so neither control suppresses the source animation.
      double x = .5 + (fishCenterX - .5) * this.separationScale +
        (sourceX - fishCenterX) * this.fishScale;
      double y = .5 + (fishCenterY - .5) * this.separationScale +
        (sourceY - fishCenterY) * this.fishScale;

      // Rotate the combined scene about its center, then pan in screen space.
      double dx = x - .5;
      double dy = y - .5;
      double rotatedX = dx * this.cosRotation - dy * this.sinRotation + .5 + this.panX;
      double rotatedY = dx * this.sinRotation + dy * this.cosRotation + .5 + this.panY;
      splat(rotatedX * GRID_LAST, rotatedY * GRID_LAST);
    }

    draw();
  }

  private void draw() {
    final double h = this.hue.getValue() * 360;
    final double s = this.saturation.getValue() * 100;
    final double bg = this.outputBackground;
    final double lvl = this.outputLevel;
    final double offset = this.glowOffset;

    for (LXPoint point : this.model.points) {
      double u = .5 + (point.xn - .5) * this.aspectX;
      double v = 1 - point.yn;
      if (u < 0 || u > 1 || v < 0 || v > 1) {
        this.colors[point.index] = LXColor.hsb(h, s, bg * lvl * 100);
        continue;
      }

      double gx = u * GRID_LAST;
      double gy = v * GRID_LAST;
      double coverage = sampleInk(gx, gy);
      coverage += .32 * sampleInk(gx - offset, gy);
      coverage += .32 * sampleInk(gx + offset, gy);
      coverage += .32 * sampleInk(gx, gy - offset);
      coverage += .32 * sampleInk(gx, gy + offset);
      coverage += .14 * sampleInk(gx - offset, gy - offset);
      coverage += .14 * sampleInk(gx + offset, gy - offset);
      coverage += .14 * sampleInk(gx - offset, gy + offset);
      coverage += .14 * sampleInk(gx + offset, gy + offset);

      double accumulatedOpacity = 1 - Math.pow(this.pointTransmission, coverage);
      double luminance = bg + (1 - bg) * accumulatedOpacity;
      this.colors[point.index] = LXColor.hsb(h, s, luminance * lvl * 100);
    }
  }

  /** Adds one anti-aliased point to the four surrounding buffer cells. */
  private void splat(double x, double y) {
    // The script leans on JavaScript quietly discarding a NaN array index. Java
    // would fold one into cell zero instead, so the finite test is explicit.
    if (!Double.isFinite(x) || !Double.isFinite(y) ||
        x < 0 || x > GRID_LAST || y < 0 || y > GRID_LAST) {
      return;
    }

    int x0 = (int) Math.floor(x);
    int y0 = (int) Math.floor(y);
    int x1 = Math.min(x0 + 1, GRID_LAST);
    int y1 = Math.min(y0 + 1, GRID_LAST);
    double fx = x - x0;
    double fy = y - y0;

    this.ink[y0 * GRID_SIZE + x0] += (1 - fx) * (1 - fy);
    this.ink[y0 * GRID_SIZE + x1] += fx * (1 - fy);
    this.ink[y1 * GRID_SIZE + x0] += (1 - fx) * fy;
    this.ink[y1 * GRID_SIZE + x1] += fx * fy;
  }

  /** Bilinearly samples the point buffer, returning black beyond its edges. */
  private double sampleInk(double x, double y) {
    if (x < 0 || x > GRID_LAST || y < 0 || y > GRID_LAST) {
      return 0;
    }

    int x0 = (int) Math.floor(x);
    int y0 = (int) Math.floor(y);
    int x1 = Math.min(x0 + 1, GRID_LAST);
    int y1 = Math.min(y0 + 1, GRID_LAST);
    double fx = x - x0;
    double fy = y - y0;
    double top = this.ink[y0 * GRID_SIZE + x0] * (1 - fx) +
      this.ink[y0 * GRID_SIZE + x1] * fx;
    double bottom = this.ink[y1 * GRID_SIZE + x0] * (1 - fx) +
      this.ink[y1 * GRID_SIZE + x1] * fx;
    return top * (1 - fy) + bottom * fy;
  }
}
