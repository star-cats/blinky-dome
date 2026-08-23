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
 * Wing Dance, ported from a compact Processing sketch. Ported from
 * Scripts/WingDance.js.
 *
 * A Lorenz trajectory supplies 30,000 body points, interleaved by {@code i % 9}
 * into nine animated butterflies. The trajectory resets identically on every
 * source frame, so it is integrated once in init() and reused without changing
 * the drawing equations. Each active butterfly is then scaled around its live
 * centroid, preserving the native dance while making count and layout useful.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Wing Dance")
@LXComponent.Description("Butterflies drawn from a Lorenz trajectory, beating their wings in formation")
public class WingDancePattern extends LXPattern {

  private static final int POINT_COUNT = 30000;
  private static final int MAX_BUTTERFLIES = 9;
  private static final double LORENZ_STEP = .0005;

  private static final int GRID_SIZE = 256;
  private static final int GRID_LAST = GRID_SIZE - 1;
  private static final int GRID_CELLS = GRID_SIZE * GRID_SIZE;

  // The original advances t by one per draw. At 60 fps the two animation phases
  // therefore advance by 3 PI and PI / 8 radians per second respectively.
  private static final double FRAME_RATE = 60;
  private static final double FRAME_PERIOD = 960;

  public final CompoundParameter butterflies =
    new CompoundParameter("Butterflies", 1, 0, 1)
    .setDescription("Number of dancers, from 1 to 9");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .5, 0, 1)
    .setDescription("Speed of the original wing and formation motion");

  public final CompoundParameter flutter =
    new CompoundParameter("Flutter", .5, 0, 1)
    .setDescription("Depth of each butterfly's wing beat");

  public final CompoundParameter size =
    new CompoundParameter("Size", .5, 0, 1)
    .setDescription("Scale of each butterfly around its own moving center");

  public final CompoundParameter spread =
    new CompoundParameter("Spread", .5, 0, 1)
    .setDescription("Distance of the butterfly centers from the middle");

  public final CompoundParameter density =
    new CompoundParameter("Density", 1, 0, 1)
    .setDescription("Point density; lower values improve performance");

  public final CompoundParameter rotation =
    new CompoundParameter("Rotation", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Rotate the complete dance; center is upright");

  public final CompoundParameter centerX =
    new CompoundParameter("Center X", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Horizontal position of the complete dance");

  public final CompoundParameter centerY =
    new CompoundParameter("Center Y", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Vertical position of the complete dance");

  public final CompoundParameter glow =
    new CompoundParameter("Glow", .375, 0, 1)
    .setDescription("Radius of the point-cloud glow");

  public final CompoundParameter opacity =
    new CompoundParameter("Opacity", .61, 0, 1)
    .setDescription("Opacity of each accumulated point");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 1)
    .setDescription("Butterfly color");

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
    .setDescription("Keep the dance proportional on non-square models");

  private double[] ink = null;
  private double[] lorenzX = null;
  private double[] lorenzZ = null;
  private double[] rawX = null;
  private double[] rawY = null;
  private int[] rawGroup = null;

  private double[] groupSumX = null;
  private double[] groupSumY = null;
  private int[] groupPointCount = null;

  private double frameTime = 0;
  private double aspectX = 1;

  // Controls resolved once per frame.
  private int activeButterflies = 9;
  private double butterflyScale = 1;
  private double spreadScale = 1;
  private double flutterScale = 1;
  private int drawStride = 1;
  private double cosRotation = 1;
  private double sinRotation = 0;
  private double panX = 0;
  private double panY = 0;
  private double glowOffset = 2.25;
  private double pointTransmission = 1 - 144. / 255;
  private double outputBackground = 9. / 255;
  private double outputLevel = 1;

  public WingDancePattern(LX lx) {
    super(lx);
    addParameter("butterflies", this.butterflies);
    addParameter("speed", this.speed);
    addParameter("flutter", this.flutter);
    addParameter("size", this.size);
    addParameter("spread", this.spread);
    addParameter("density", this.density);
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
   * Allocates the buffers and integrates the Lorenz trajectory on first use.
   *
   * Deferred rather than done in the constructor: a project holds every pattern
   * it has ever been given, and only one of them is drawing.
   */
  private void init() {
    this.ink = new double[GRID_CELLS];
    this.lorenzX = new double[POINT_COUNT];
    this.lorenzZ = new double[POINT_COUNT];
    this.rawX = new double[POINT_COUNT];
    this.rawY = new double[POINT_COUNT];
    this.rawGroup = new int[POINT_COUNT];

    this.groupSumX = new double[MAX_BUTTERFLIES];
    this.groupSumY = new double[MAX_BUTTERFLIES];
    this.groupPointCount = new int[MAX_BUTTERFLIES];

    // Preserve the source's descending loop and simultaneous [x,y,z] update.
    double x = 9;
    double y = 9;
    double z = 9;
    for (int i = POINT_COUNT; i-- > 0;) {
      this.lorenzX[i] = x;
      this.lorenzZ[i] = z;

      double oldX = x;
      double oldY = y;
      double oldZ = z;
      x = oldX + 9 * (oldY - oldX) * LORENZ_STEP;
      y = oldY + (oldX * (28 - oldZ) - oldY) * LORENZ_STEP;
      z = oldZ + (oldX * oldY - oldZ - oldZ) * LORENZ_STEP;
    }
  }

  @Override
  protected void run(double deltaMs) {
    if (this.ink == null) {
      init();
    }

    double dt = Double.isFinite(deltaMs) ? Math.max(0, Math.min(deltaMs / 1000, .25)) : 0;
    this.frameTime += dt * FRAME_RATE * this.speed.getValue() * 2;
    if (this.frameTime >= FRAME_PERIOD) {
      this.frameTime -= Math.floor(this.frameTime / FRAME_PERIOD) * FRAME_PERIOD;
    }

    this.activeButterflies = Math.max(1, Math.min(
      MAX_BUTTERFLIES,
      1 + (int) Math.floor(this.butterflies.getValue() * 8.999999)));
    this.flutterScale = this.flutter.getValue() * 2;
    this.butterflyScale = .4 + this.size.getValue() * 1.2;
    this.spreadScale = this.spread.getValue() * 2;
    this.drawStride = 1 + (int) Math.floor((1 - this.density.getValue()) * 7.999999);

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
    Arrays.fill(this.groupSumX, 0);
    Arrays.fill(this.groupSumY, 0);
    Arrays.fill(this.groupPointCount, 0);

    double fastPhase = this.frameTime * Math.PI / 20;
    double slowPhase = this.frameTime * Math.PI / 480;
    int rawCount = 0;

    for (int i = POINT_COUNT; i-- > 0;) {
      int group = i % this.activeButterflies;

      // Apply Density evenly inside every modulo group. The Lorenz trajectory is
      // already tabulated, so omitted points cost no integration or trig work.
      if ((i / this.activeButterflies) % this.drawStride != 0) {
        continue;
      }

      double x = this.lorenzX[i];
      double z = this.lorenzZ[i];
      double wing = Math.sin(fastPhase - x * x / 99 + group);
      double e = 1 + this.flutterScale * wing;
      double q = x * e + 89;
      double k = z / 59 - e / 29 + slowPhase + group * 8;
      double sourceX = (q * Math.cos(k) + 200) / 400;
      double sourceY = (200 - (q + 60 * Math.cos(k / 2)) * Math.sin(k)) / 400;

      this.rawX[rawCount] = sourceX;
      this.rawY[rawCount] = sourceY;
      this.rawGroup[rawCount] = group;
      this.groupSumX[group] += sourceX;
      this.groupSumY[group] += sourceY;
      ++this.groupPointCount[group];
      ++rawCount;
    }

    for (int pointIndex = 0; pointIndex < rawCount; ++pointIndex) {
      int group = this.rawGroup[pointIndex];
      double groupCenterX = this.groupSumX[group] / this.groupPointCount[group];
      double groupCenterY = this.groupSumY[group] / this.groupPointCount[group];

      // Size is local to each butterfly. Spread moves only its live centroid.
      double x = .5 + (groupCenterX - .5) * this.spreadScale +
        (this.rawX[pointIndex] - groupCenterX) * this.butterflyScale;
      double y = .5 + (groupCenterY - .5) * this.spreadScale +
        (this.rawY[pointIndex] - groupCenterY) * this.butterflyScale;

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

  private void splat(double x, double y) {
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
