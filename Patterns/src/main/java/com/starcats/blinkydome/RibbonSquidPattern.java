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
 * Ribbon squid, ported from a compact Processing sketch. Ported from
 * Scripts/RibbonSquid.js.
 *
 * The original 10,000 translucent points and its native motion are preserved.
 * Time-independent trigonometric phases are tabulated at load, leaving only a
 * handful of trig calls per frame. A luminance buffer recreates Processing's
 * point accumulation while remaining practical for a sparse LED model.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Ribbon Squid")
@LXComponent.Description("A rippling point-cloud squid trailing ribbons through the frame")
public class RibbonSquidPattern extends LXPattern {

  private static final int POINT_COUNT = 10000;
  private static final int GRID_SIZE = 256;
  private static final int GRID_LAST = GRID_SIZE - 1;
  private static final int GRID_CELLS = GRID_SIZE * GRID_SIZE;

  // PI / 40 per Processing draw at 60 fps.
  private static final double TIME_RATE = Math.PI * 1.5;
  // All time-dependent terms repeat together after 18 PI.
  private static final double TIME_PERIOD = Math.PI * 18;

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .5, 0, 1)
    .setDescription("Speed of the original swimming and ribbon motion");

  public final CompoundParameter wave =
    new CompoundParameter("Wave", .5, 0, 1)
    .setDescription("Strength of the squid's vertical ripple and tentacle motion");

  public final CompoundParameter size =
    new CompoundParameter("Size", .5, 0, 1)
    .setDescription("Overall squid size");

  public final CompoundParameter width =
    new CompoundParameter("Width", .5, 0, 1)
    .setDescription("Horizontal body and ribbon scale");

  public final CompoundParameter height =
    new CompoundParameter("Height", .5, 0, 1)
    .setDescription("Vertical body and ribbon scale");

  public final CompoundParameter rotation =
    new CompoundParameter("Rotation", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Rotate the complete squid; center is upright");

  public final CompoundParameter centerX =
    new CompoundParameter("Center X", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Horizontal squid position");

  public final CompoundParameter centerY =
    new CompoundParameter("Center Y", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Vertical squid position");

  public final CompoundParameter glow =
    new CompoundParameter("Glow", .375, 0, 1)
    .setDescription("Radius of the point-cloud glow");

  public final CompoundParameter opacity =
    new CompoundParameter("Opacity", .61, 0, 1)
    .setDescription("Opacity of each accumulated point");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 1)
    .setDescription("Ribbon squid color");

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
    .setDescription("Keep the squid proportional on non-square models");

  private double[] ink = null;
  private double[] pointXAmplitude = null;
  private double[] pointStaticY = null;
  private double[] pointWaveAmplitude = null;
  private double[] pointD2Third = null;

  private double[] pointCosD = null;
  private double[] pointSinD = null;
  private double[] pointCosD3 = null;
  private double[] pointSinD3 = null;
  private double[] pointCosWave = null;
  private double[] pointSinWave = null;
  private double[] pointCosD2Seventh = null;
  private double[] pointSinD2Seventh = null;

  private double time = 0;
  private double aspectX = 1;

  // Control values resolved once per frame.
  private double waveScale = 1;
  private double scaleX = 1;
  private double scaleY = 1;
  private double cosRotation = 1;
  private double sinRotation = 0;
  private double panX = 0;
  private double panY = 0;
  private double glowOffset = 2.25;
  private double pointTransmission = 1 - 144. / 255;
  private double outputBackground = 9. / 255;
  private double outputLevel = 1;

  public RibbonSquidPattern(LX lx) {
    super(lx);
    addParameter("speed", this.speed);
    addParameter("wave", this.wave);
    addParameter("size", this.size);
    addParameter("width", this.width);
    addParameter("height", this.height);
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
   * Allocates the buffers and tabulates the fixed phases on first use.
   *
   * Deferred rather than done in the constructor: a project holds every pattern
   * it has ever been given, and only one of them is drawing.
   */
  private void init() {
    this.ink = new double[GRID_CELLS];
    this.pointXAmplitude = new double[POINT_COUNT];
    this.pointStaticY = new double[POINT_COUNT];
    this.pointWaveAmplitude = new double[POINT_COUNT];
    this.pointD2Third = new double[POINT_COUNT];

    this.pointCosD = new double[POINT_COUNT];
    this.pointSinD = new double[POINT_COUNT];
    this.pointCosD3 = new double[POINT_COUNT];
    this.pointSinD3 = new double[POINT_COUNT];
    this.pointCosWave = new double[POINT_COUNT];
    this.pointSinWave = new double[POINT_COUNT];
    this.pointCosD2Seventh = new double[POINT_COUNT];
    this.pointSinD2Seventh = new double[POINT_COUNT];

    for (int i = 0; i < POINT_COUNT; ++i) {
      double y = i / 295.;
      double k = 4 * Math.cos(i / 29.);
      double e = y / 4 - 16;
      double d = Math.sqrt(k * k + e * e) - 5;
      double d2 = d * d;
      double waveAmplitude = y / 9 * k;
      double wavePhase = e * 9 - d * 3;
      double d2Seventh = d2 / 7;

      this.pointXAmplitude[i] = d2 / .7 - k * k * 2 + y;
      this.pointStaticY[i] = 3 * Math.sin(k * 2) + Math.cos(y) / k +
        waveAmplitude * 3;
      this.pointWaveAmplitude[i] = waveAmplitude;
      this.pointD2Third[i] = d2 / 3;

      this.pointCosD[i] = Math.cos(d);
      this.pointSinD[i] = Math.sin(d);
      this.pointCosD3[i] = Math.cos(d / 3);
      this.pointSinD3[i] = Math.sin(d / 3);
      this.pointCosWave[i] = Math.cos(wavePhase);
      this.pointSinWave[i] = Math.sin(wavePhase);
      this.pointCosD2Seventh[i] = Math.cos(d2Seventh);
      this.pointSinD2Seventh[i] = Math.sin(d2Seventh);
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

    this.waveScale = this.wave.getValue() * 2;
    double overallScale = .4 + this.size.getValue() * 1.2;
    this.scaleX = overallScale * (.5 + this.width.getValue());
    this.scaleY = overallScale * (.5 + this.height.getValue());
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

    // Every per-point angle is a fixed phase plus one of these time shifts.
    double cosThirdTime = Math.cos(this.time / 3);
    double sinThirdTime = Math.sin(this.time / 3);
    double cosNinthTime = Math.cos(this.time / 9);
    double sinNinthTime = Math.sin(this.time / 9);
    double cosTime = Math.cos(this.time);
    double sinTime = Math.sin(this.time);

    for (int i = 0; i < POINT_COUNT; ++i) {
      // cos(d - t/3)
      double cosC = this.pointCosD[i] * cosThirdTime + this.pointSinD[i] * sinThirdTime;
      // sin(d/3 - t/9)
      double sinC3 = this.pointSinD3[i] * cosNinthTime - this.pointCosD3[i] * sinNinthTime;
      // sin(e*9 - d*3 + t)
      double ribbonWave = this.pointSinWave[i] * cosTime + this.pointCosWave[i] * sinTime;
      // sin(t - d*d/7)
      double deepWave = sinTime * this.pointCosD2Seventh[i] -
        cosTime * this.pointSinD2Seventh[i];

      double sourceX = (this.pointXAmplitude[i] * cosC + 200) / 400;
      double animatedY = this.pointWaveAmplitude[i] * ribbonWave +
        79 * sinC3 + this.pointD2Third[i] * deepWave;
      double sourceY = (this.pointStaticY[i] + 200 + this.waveScale * animatedY) / 400;

      double dx = (sourceX - .5) * this.scaleX;
      double dy = (sourceY - .5) * this.scaleY;
      double x = dx * this.cosRotation - dy * this.sinRotation + .5 + this.panX;
      double y = dx * this.sinRotation + dy * this.cosRotation + .5 + this.panY;
      splat(x * GRID_LAST, y * GRID_LAST);
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
    // The source's cos(y)/k term deliberately sends a few ribbon points far off
    // canvas. Discarding them here matches Processing's clipping behavior.
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
