package com.starcats.blinkydome;

import java.util.Arrays;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Jelly Time, ported from a つぶやきProcessing sketch. Ported from
 * Scripts/JellyTime.js.
 *
 * The original draws 10,000 translucent points into a 400 x 400 Processing
 * canvas every frame. Those points are interleaved between sixteen individual
 * jellies by {@code i % 16}. This version isolates three of those jellies,
 * recenters and enlarges each one. The three jellies roam independently in X
 * and Y, and every point gets a compact glow so the fine point clouds remain
 * legible on sparse LED layouts.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Jelly Time")
@LXComponent.Description("Rippling point-cloud jellies roaming independently through the frame")
public class JellyTimePattern extends LXPattern {

  private static final int POINT_COUNT = 10000;
  private static final int GRID_SIZE = 256;
  private static final int GRID_LAST = GRID_SIZE - 1;
  private static final int GRID_CELLS = GRID_SIZE * GRID_SIZE;

  private static final int JELLY_COUNT = 3;
  private static final int POINTS_PER_JELLY = 625;

  // Independent Lissajous-like motion keeps the jellies circulating through the
  // frame without locking them into a formation. Frequencies are cycles/second.
  private static final double[] MOVE_X_HZ = { .071, .093, .057 };
  private static final double[] MOVE_Y_HZ = { .089, .061, .107 };
  private static final double[] MOVE_X_PHASE = { .2, 2.4, 4.5 };
  private static final double[] MOVE_Y_PHASE = { 1.4, 4.1, 5.6 };

  // Processing advances t by PI / 20 per draw. At its conventional 60 fps that
  // is 3 PI units per second; integrating deltaMs makes the port frame-rate
  // independent while preserving the original pace.
  private static final double TIME_RATE = Math.PI * 3;
  private static final double TIME_PERIOD = Math.PI * 96;

  public final CompoundParameter count =
    new CompoundParameter("Jellies", 1, 0, 1)
    .setDescription("Number of independently moving jellies, from 1 to 3");

  public final CompoundParameter form =
    new CompoundParameter("Form", .44, 0, 1)
    .setDescription("Choose which three strands from the original sketch become jellies");

  public final CompoundParameter size =
    new CompoundParameter("Size", .5, 0, 1)
    .setDescription("Scale of each jelly");

  public final CompoundParameter jellySpeed =
    new CompoundParameter("Jelly Speed", .5, 0, 1)
    .setDescription("Speed of the internal rippling and deformation");

  public final CompoundParameter moveSpeed =
    new CompoundParameter("Move Speed", .5, 0, 1)
    .setDescription("Speed at which the jellies travel around the frame");

  public final CompoundParameter moveX =
    new CompoundParameter("X Travel", .55, 0, 1)
    .setDescription("Horizontal roaming distance");

  public final CompoundParameter moveY =
    new CompoundParameter("Y Travel", .5, 0, 1)
    .setDescription("Vertical roaming distance");

  public final CompoundParameter orbit =
    new CompoundParameter("Orbit", 0, 0, 1)
    .setDescription("Blend irregular two-axis wandering into smooth elliptical orbits");

  public final CompoundParameter glow =
    new CompoundParameter("Glow", .375, 0, 1)
    .setDescription("Radius of the point-cloud glow");

  public final CompoundParameter opacity =
    new CompoundParameter("Opacity", .61, 0, 1)
    .setDescription("Opacity of each accumulated point");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 1)
    .setDescription("Jelly color");

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
    .setDescription("Keep motion and jelly shapes square on non-square models");

  private double[] ink = null;
  private double[] pointK = null;
  private double[] pointE = null;
  private double[] pointBaseD = null;
  private double[] jellyPointX = null;
  private double[] jellyPointY = null;

  private double time = 0;
  private double motionTime = 0;
  private double aspectX = 1;

  // Control values resolved once per frame rather than once per LED.
  private int activeJellyCount = 3;
  private double jellyScale = 5;
  private int firstGroup = 7;
  private double moveRangeX = .22;
  private double moveRangeY = .2;
  private double orbitAmount = 0;
  private double glowOffset = 2.25;
  private double pointTransmission = 1 - 144. / 255;
  private double outputBackground = 9. / 255;
  private double outputLevel = 1;

  public JellyTimePattern(LX lx) {
    super(lx);
    addParameter("count", this.count);
    addParameter("form", this.form);
    addParameter("size", this.size);
    addParameter("jellySpeed", this.jellySpeed);
    addParameter("moveSpeed", this.moveSpeed);
    addParameter("moveX", this.moveX);
    addParameter("moveY", this.moveY);
    addParameter("orbit", this.orbit);
    addParameter("glow", this.glow);
    addParameter("opacity", this.opacity);
    addParameter("hue", this.hue);
    addParameter("saturation", this.saturation);
    addParameter("background", this.background);
    addParameter("level", this.level);
    addParameter("autoAspect", this.autoAspect);
  }

  /**
   * Allocates the buffers and tabulates the time-independent terms on first use.
   *
   * Deferred rather than done in the constructor: a project holds every pattern
   * it has ever been given, and only one of them is drawing.
   */
  private void init() {
    this.ink = new double[GRID_CELLS];
    this.pointK = new double[POINT_COUNT];
    this.pointE = new double[POINT_COUNT];
    this.pointBaseD = new double[POINT_COUNT];
    this.jellyPointX = new double[JELLY_COUNT * POINTS_PER_JELLY];
    this.jellyPointY = new double[JELLY_COUNT * POINTS_PER_JELLY];

    // These terms depend only on the point index, not on animation time.
    for (int i = 0; i < POINT_COUNT; ++i) {
      double k = 9 * Math.cos(i * 5.) * Math.sin(i);
      double e = 9 * Math.cos(i * 3.) * Math.cos(i * 2.);
      double magnitude = Math.sqrt(k * k + e * e);
      this.pointK[i] = k;
      this.pointE[i] = e;
      this.pointBaseD[i] = magnitude * magnitude * magnitude / 1999;
    }
  }

  @Override
  protected void run(double deltaMs) {
    if (this.ink == null) {
      init();
    }

    double dt = Double.isFinite(deltaMs) ? Math.max(0, Math.min(deltaMs / 1000, .25)) : 0;
    double jellyRate = this.jellySpeed.getValue() * 2;
    double travelRate = this.moveSpeed.getValue() * 2;
    this.time += dt * TIME_RATE * jellyRate;
    if (this.time >= TIME_PERIOD) {
      this.time -= Math.floor(this.time / TIME_PERIOD) * TIME_PERIOD;
    }
    this.motionTime += dt * travelRate;

    this.activeJellyCount = Math.max(1, Math.min(3,
      1 + (int) Math.floor(this.count.getValue() * 2.999999)));
    this.jellyScale = 2.5 + this.size.getValue() * 5;
    this.firstGroup = Math.min(15, (int) Math.floor(this.form.getValue() * 15.999999));
    this.moveRangeX = this.moveX.getValue() * .4;
    this.moveRangeY = this.moveY.getValue() * .4;
    this.orbitAmount = this.orbit.getValue();
    this.glowOffset = this.glow.getValue() * 6;
    double pointAlpha = .05 + this.opacity.getValue() * .85;
    this.pointTransmission = 1 - pointAlpha;
    this.outputBackground = this.background.getValue();
    this.outputLevel = this.level.getValue();

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    Arrays.fill(this.ink, 0);

    for (int jelly = 0; jelly < this.activeJellyCount; ++jelly) {
      int group = (this.firstGroup + jelly) % 16;
      int m = group * 13;
      double wave = Math.sin(this.time / 2 + m);
      double groupWave = wave * wave * wave / 3;
      int base = jelly * POINTS_PER_JELLY;
      double sumX = 0;
      double sumY = 0;
      int pointCount = 0;

      // Selecting one residue class isolates one genuine jelly from the source;
      // no complete-scene replicas are made here.
      for (int i = group; i < POINT_COUNT; i += 16) {
        double d = this.pointBaseD[i] + 1.5 - groupWave;
        double c = d / 16 - this.time / 48 + m;
        double p = Math.pow(d, Math.sin(d * d - this.time + m));
        double sourceX = (99 * Math.sin(c) + this.pointK[i] * p + 200) / 400;
        double sourceY = (99 * Math.sin(c * 4) + this.pointE[i] * p + 200) / 400;
        this.jellyPointX[base + pointCount] = sourceX;
        this.jellyPointY[base + pointCount] = sourceY;
        sumX += sourceX;
        sumY += sourceY;
        ++pointCount;
      }

      // Each strand naturally travels around the original scene. Recenter it on
      // its own live centroid before scaling, then give it an independent X/Y
      // orbit instead of pinning all three to fixed positions.
      double centerX = sumX / pointCount;
      double centerY = sumY / pointCount;
      double xFrequency = MOVE_X_HZ[jelly];
      double yFrequency = MOVE_Y_HZ[jelly] +
        (xFrequency - MOVE_Y_HZ[jelly]) * this.orbitAmount;
      double yPhase = MOVE_Y_PHASE[jelly] +
        (MOVE_X_PHASE[jelly] + Math.PI / 2 - MOVE_Y_PHASE[jelly]) * this.orbitAmount;
      double movingX = .5 + this.moveRangeX * Math.sin(
        Math.PI * 2 * MOVE_X_HZ[jelly] * this.motionTime + MOVE_X_PHASE[jelly]);
      double movingY = .5 + this.moveRangeY * Math.sin(
        Math.PI * 2 * yFrequency * this.motionTime + yPhase);
      for (int n = 0; n < pointCount; ++n) {
        double x = movingX + (this.jellyPointX[base + n] - centerX) * this.jellyScale;
        double y = movingY + (this.jellyPointY[base + n] - centerY) * this.jellyScale;
        splat(x * GRID_LAST, y * GRID_LAST);
      }
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
      // Preserve the source's square canvas on non-square models.
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

      // Repeated translucent points composite over the selected background.
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
