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
 * Face Hugger, ported from a compact Processing sketch. Ported from
 * Scripts/FaceHugger.js.
 *
 * The source evaluates a 200 x 200 field and draws it with a translucent
 * background, so old poses fade instead of disappearing immediately. This port
 * keeps a persistent luminance buffer and applies the same fade and point-alpha
 * compositing in a frame-rate-independent form.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Face Hugger")
@LXComponent.Description("A pulsing creature drawn from a 200x200 field, its old poses fading behind it")
public class FaceHuggerPattern extends LXPattern {

  private static final int POINT_COUNT = 40000;
  private static final int GRID_SIZE = 256;
  private static final int GRID_LAST = GRID_SIZE - 1;
  private static final int GRID_CELLS = GRID_SIZE * GRID_SIZE;

  // PI / 90 per Processing draw at 60 fps. Every animated term repeats at 2 PI.
  private static final double TIME_RATE = Math.PI * 2 / 3;
  private static final double TIME_PERIOD = Math.PI * 2;
  private static final double REFERENCE_FPS = 60;

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .5, 0, 1)
    .setDescription("Speed of the original pulsing motion");

  public final CompoundParameter motion =
    new CompoundParameter("Motion", .5, 0, 1)
    .setDescription("Strength of the animated limb deformation");

  public final CompoundParameter density =
    new CompoundParameter("Density", 1, 0, 1)
    .setDescription("Source-grid density; lower values improve performance");

  public final CompoundParameter size =
    new CompoundParameter("Size", .5, 0, 1)
    .setDescription("Overall creature size");

  public final CompoundParameter width =
    new CompoundParameter("Width", .5, 0, 1)
    .setDescription("Horizontal body and limb scale");

  public final CompoundParameter height =
    new CompoundParameter("Height", .5, 0, 1)
    .setDescription("Vertical body and limb scale");

  public final CompoundParameter rotation =
    new CompoundParameter("Rotation", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Rotate the creature; center is upright");

  public final CompoundParameter centerX =
    new CompoundParameter("Center X", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Horizontal creature position");

  public final CompoundParameter centerY =
    new CompoundParameter("Center Y", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Vertical creature position");

  public final CompoundParameter trails =
    new CompoundParameter("Trails", .649509803922, 0, 1)
    .setDescription("Persistence of previous poses; zero clears every frame");

  public final CompoundParameter glow =
    new CompoundParameter("Glow", .375, 0, 1)
    .setDescription("Radius of the point-cloud glow");

  public final CompoundParameter opacity =
    new CompoundParameter("Opacity", .4, 0, 1)
    .setDescription("Opacity of each accumulated source point");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 1)
    .setDescription("Creature color");

  public final CompoundParameter saturation =
    new CompoundParameter("Saturation", 0, 0, 1)
    .setDescription("Color saturation; zero is the original white");

  public final CompoundParameter background =
    new CompoundParameter("Background", .023529411765, 0, 1)
    .setDescription("Background brightness");

  public final CompoundParameter level =
    new CompoundParameter("Level", 1, 0, 1)
    .setDescription("Overall output brightness");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Keep the creature proportional on non-square models");

  private double[] ink = null;
  private double[] pointBaseX = null;
  private double[] pointBaseY = null;
  private double[] pointMotionX = null;
  private double[] pointCos2D = null;
  private double[] pointSin2D = null;
  private double[] pointCosD = null;
  private double[] pointSinD = null;

  private double time = 0;
  private double aspectX = 1;

  // Values resolved once per frame.
  private double motionScale = 1;
  private double scaleX = 1;
  private double scaleY = 1;
  private double cosRotation = 1;
  private double sinRotation = 0;
  private double panX = 0;
  private double panY = 0;
  private double glowOffset = 2.25;
  private double pointAlpha = 46. / 255;
  private double outputBackground = 6. / 255;
  private double outputLevel = 1;
  private int sampleStep = 1;

  public FaceHuggerPattern(LX lx) {
    super(lx);
    addParameter("speed", this.speed);
    addParameter("motion", this.motion);
    addParameter("density", this.density);
    addParameter("size", this.size);
    addParameter("width", this.width);
    addParameter("height", this.height);
    addParameter("rotation", this.rotation);
    addParameter("centerX", this.centerX);
    addParameter("centerY", this.centerY);
    addParameter("trails", this.trails);
    addParameter("glow", this.glow);
    addParameter("opacity", this.opacity);
    addParameter("hue", this.hue);
    addParameter("saturation", this.saturation);
    addParameter("background", this.background);
    addParameter("level", this.level);
    addParameter("autoAspect", this.autoAspect);
  }

  /**
   * Allocates the buffers and tabulates the field's fixed terms on first use.
   *
   * Deferred rather than done in the constructor: a project holds every pattern
   * it has ever been given, and only one of them is drawing.
   */
  private void init() {
    this.ink = new double[GRID_CELLS];
    this.pointBaseX = new double[POINT_COUNT];
    this.pointBaseY = new double[POINT_COUNT];
    this.pointMotionX = new double[POINT_COUNT];
    this.pointCos2D = new double[POINT_COUNT];
    this.pointSin2D = new double[POINT_COUNT];
    this.pointCosD = new double[POINT_COUNT];
    this.pointSinD = new double[POINT_COUNT];

    Arrays.fill(this.ink, 6. / 255);

    for (int i = 0; i < POINT_COUNT; ++i) {
      int x = i % 200;
      int y = i / 200;
      double k = x / 8. - 12.5;
      double e = y / 8. - 12.5;
      double radius = Math.sqrt(k * k + e * e) / 12;
      double o = radius * Math.cos(Math.sin(k / 2) * Math.cos(e / 2));
      double d = 5 * Math.cos(o);
      double staticLimb = Math.sin(y * o * o) / 9;

      this.pointBaseX[i] = (x + d * k * staticLimb) / 1.5 + 133;
      this.pointMotionX[i] = d * k / 1.5;
      this.pointBaseY[i] = (y / 3. - d * 40) * 1.5 + 300;
      this.pointCos2D[i] = Math.cos(d * 2);
      this.pointSin2D[i] = Math.sin(d * 2);
      this.pointCosD[i] = Math.cos(d);
      this.pointSinD[i] = Math.sin(d);
    }
  }

  @Override
  protected void run(double deltaMs) {
    if (this.ink == null) {
      init();
    }

    double dt = Double.isFinite(deltaMs) ? Math.max(0, Math.min(deltaMs / 1000, .25)) : 0;
    double frameEquivalent = dt * REFERENCE_FPS;
    this.time += dt * TIME_RATE * this.speed.getValue() * 2;
    if (this.time >= TIME_PERIOD) {
      this.time -= Math.floor(this.time / TIME_PERIOD) * TIME_PERIOD;
    }

    this.motionScale = this.motion.getValue() * 2;
    this.sampleStep = 1 + (int) Math.floor((1 - this.density.getValue()) * 3.999999);
    double overallScale = .4 + this.size.getValue() * 1.2;
    this.scaleX = overallScale * (.5 + this.width.getValue());
    this.scaleY = overallScale * (.5 + this.height.getValue());
    double angle = (this.rotation.getValue() - .5) * Math.PI * 2;
    this.cosRotation = Math.cos(angle);
    this.sinRotation = Math.sin(angle);
    this.panX = (this.centerX.getValue() - .5) * 1.2;
    this.panY = (this.centerY.getValue() - .5) * 1.2;
    this.glowOffset = this.glow.getValue() * 6;
    this.outputBackground = this.background.getValue();
    this.outputLevel = this.level.getValue();

    // Defaults reproduce background(6, 96) and stroke(400, 46). Convert their
    // per-60fps-frame alpha to the actual frame duration so trails do not change
    // length when the renderer's frame rate changes.
    double backgroundAlpha60 = 1 - this.trails.getValue() * .96;
    double backgroundTransmission = Math.pow(1 - backgroundAlpha60, frameEquivalent);
    double pointAlpha60 = this.opacity.getValue() * (115. / 255);
    this.pointAlpha = 1 - Math.pow(1 - pointAlpha60, frameEquivalent);

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    // Translucent background: fade old poses toward the selected background.
    for (int cell = 0; cell < GRID_CELLS; ++cell) {
      this.ink[cell] = this.outputBackground +
        (this.ink[cell] - this.outputBackground) * backgroundTransmission;
    }

    double cosTime = Math.cos(this.time);
    double sinTime = Math.sin(this.time);

    // Step both grid axes equally so reduced Density does not introduce stripes.
    for (int sourceRow = 0; sourceRow < 200; sourceRow += this.sampleStep) {
      int rowBase = sourceRow * 200;
      for (int sourceColumn = 0; sourceColumn < 200; sourceColumn += this.sampleStep) {
        int i = rowBase + sourceColumn;
        // sin(2d + t) and cos(d + t), expanded from precomputed d phases.
        double limbWave = this.pointSin2D[i] * cosTime + this.pointCos2D[i] * sinTime;
        double bodyWave = this.pointCosD[i] * cosTime - this.pointSinD[i] * sinTime;
        double sourceX = (this.pointBaseX[i] +
          this.pointMotionX[i] * this.motionScale * limbWave) / 400;
        double sourceY = (this.pointBaseY[i] + 28.5 * this.motionScale * bodyWave) / 400;

        double dx = (sourceX - .5) * this.scaleX;
        double dy = (sourceY - .5) * this.scaleY;
        double x = dx * this.cosRotation - dy * this.sinRotation + .5 + this.panX;
        double y = dx * this.sinRotation + dy * this.cosRotation + .5 + this.panY;
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
      double u = .5 + (point.xn - .5) * this.aspectX;
      double v = 1 - point.yn;
      if (u < 0 || u > 1 || v < 0 || v > 1) {
        this.colors[point.index] = LXColor.hsb(h, s, bg * lvl * 100);
        continue;
      }

      double gx = u * GRID_LAST;
      double gy = v * GRID_LAST;
      double center = sampleInk(gx, gy);
      double signal = Math.max(0, center - bg);
      signal += .32 * Math.max(0, sampleInk(gx - offset, gy) - bg);
      signal += .32 * Math.max(0, sampleInk(gx + offset, gy) - bg);
      signal += .32 * Math.max(0, sampleInk(gx, gy - offset) - bg);
      signal += .32 * Math.max(0, sampleInk(gx, gy + offset) - bg);
      signal += .14 * Math.max(0, sampleInk(gx - offset, gy - offset) - bg);
      signal += .14 * Math.max(0, sampleInk(gx + offset, gy - offset) - bg);
      signal += .14 * Math.max(0, sampleInk(gx - offset, gy + offset) - bg);
      signal += .14 * Math.max(0, sampleInk(gx + offset, gy + offset) - bg);

      double luminance = Math.min(1, bg + signal);
      this.colors[point.index] = LXColor.hsb(h, s, luminance * lvl * 100);
    }
  }

  /** Composites one translucent white point into four anti-aliased cells. */
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

    int index = y0 * GRID_SIZE + x0;
    double alpha = this.pointAlpha * (1 - fx) * (1 - fy);
    this.ink[index] += (1 - this.ink[index]) * alpha;
    index = y0 * GRID_SIZE + x1;
    alpha = this.pointAlpha * fx * (1 - fy);
    this.ink[index] += (1 - this.ink[index]) * alpha;
    index = y1 * GRID_SIZE + x0;
    alpha = this.pointAlpha * (1 - fx) * fy;
    this.ink[index] += (1 - this.ink[index]) * alpha;
    index = y1 * GRID_SIZE + x1;
    alpha = this.pointAlpha * fx * fy;
    this.ink[index] += (1 - this.ink[index]) * alpha;
  }

  private double sampleInk(double x, double y) {
    if (x < 0 || x > GRID_LAST || y < 0 || y > GRID_LAST) {
      return this.outputBackground;
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
