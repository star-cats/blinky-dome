package com.starcats.blinkydome;

import java.util.Random;

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
 * Swirltech, ported from a compact Processing particle sketch. Ported from
 * Scripts/Swirltech.js.
 *
 * Random 3D vectors flow through an index-dependent singular field, flatten in
 * Z, and draw around canvas position (270,270). The source periodically prunes
 * its oldest vectors after crossing 3,000 particles; a ring buffer preserves
 * that lifecycle without continuous array allocation. Translucent background
 * fading provides the long, interwoven trails.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Swirltech")
@LXComponent.Description("Vectors spiralling through a singular field into long interwoven trails")
public class SwirltechPattern extends LXPattern {

  // Birth Rate reaches 30 at maximum, so the backing ring allows one such batch
  // beyond the source's 3,000-particle threshold.
  private static final int MAX_PARTICLES = 3030;
  private static final int SOURCE_BIRTHS_PER_FRAME = 15;
  private static final double REFERENCE_FPS = 60;

  private static final int GRID_SIZE = 256;
  private static final int GRID_LAST = GRID_SIZE - 1;
  private static final int GRID_CELLS = GRID_SIZE * GRID_SIZE;

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .5, 0, 1)
    .setDescription("Rate of the vector simulation and particle lifecycle");

  public final CompoundParameter birthRate =
    new CompoundParameter("Birth Rate", .5, 0, 1)
    .setDescription("How many new vectors enter each source frame");

  public final CompoundParameter population =
    new CompoundParameter("Population", 1, 0, 1)
    .setDescription("Particle threshold before old vectors are pruned");

  public final CompoundParameter flow =
    new CompoundParameter("Flow", .5, 0, 1)
    .setDescription("Strength of X/Y movement through the singular field");

  public final CompoundParameter warp =
    new CompoundParameter("Warp", .5, 0, 1)
    .setDescription("Strength of the radial secant distortion");

  public final CompoundParameter flatten =
    new CompoundParameter("Flatten", .5, 0, 1)
    .setDescription("How quickly particle depth collapses toward zero");

  public final CompoundParameter zoom =
    new CompoundParameter("Zoom", .5, 0, 1)
    .setDescription("Swirl zoom from 0.1x to 10x; center is 1x");

  public final CompoundParameter rotation =
    new CompoundParameter("Rotation", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Rotate the complete swirl; center is upright");

  public final CompoundParameter centerX =
    new CompoundParameter("Center X", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Horizontal swirl position");

  public final CompoundParameter centerY =
    new CompoundParameter("Center Y", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Vertical swirl position");

  public final CompoundParameter trails =
    new CompoundParameter("Trails", .814194465025, 0, 1)
    .setDescription("Persistence of previous particle positions");

  public final CompoundParameter glow =
    new CompoundParameter("Glow", .375, 0, 1)
    .setDescription("Radius of the particle glow");

  public final CompoundParameter opacity =
    new CompoundParameter("Opacity", .5, 0, 1)
    .setDescription("Opacity of each particle");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 1)
    .setDescription("Particle color");

  public final CompoundParameter saturation =
    new CompoundParameter("Saturation", 0, 0, 1)
    .setDescription("Color saturation; zero is the original white");

  public final CompoundParameter background =
    new CompoundParameter("Background", 0, 0, 1)
    .setDescription("Background brightness");

  public final CompoundParameter level =
    new CompoundParameter("Level", 1, 0, 1)
    .setDescription("Overall output brightness");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Keep the swirl circular on non-square models");

  private double[] ink = null;
  private double[] particleX = null;
  private double[] particleY = null;
  private double[] particleZ = null;
  private int particleHead = 0;
  private int particleCount = 0;

  private double simulationAccumulator = 0;
  private int frameClock = 0;
  private double aspectX = 1;

  private int particleThreshold = 3000;
  private int birthsPerStep = 15;
  private double flowScale = 1;
  private double warpScale = 9;
  private double flattenAmount = 1. / 3;
  private double sceneScale = 1;
  private double cosRotation = 1;
  private double sinRotation = 0;
  private double panX = 0;
  private double panY = 0;
  private double glowOffset = 2.25;
  private double pointAlpha = 62. / 255;
  private double outputBackground = 0;
  private double outputLevel = 1;

  private final Random random = new Random();

  public SwirltechPattern(LX lx) {
    super(lx);
    addParameter("speed", this.speed);
    addParameter("birthRate", this.birthRate);
    addParameter("population", this.population);
    addParameter("flow", this.flow);
    addParameter("warp", this.warp);
    addParameter("flatten", this.flatten);
    addParameter("zoom", this.zoom);
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

  /** Buffers are allocated on first draw, not held by every idle pattern. */
  private void init() {
    this.ink = new double[GRID_CELLS];
    this.particleX = new double[MAX_PARTICLES];
    this.particleY = new double[MAX_PARTICLES];
    this.particleZ = new double[MAX_PARTICLES];
  }

  @Override
  protected void run(double deltaMs) {
    if (this.ink == null) {
      init();
    }

    double dt = Double.isFinite(deltaMs) ? Math.max(0, Math.min(deltaMs / 1000, .25)) : 0;
    double frameEquivalent = dt * REFERENCE_FPS;

    this.particleThreshold = (int) Math.round(300 + this.population.getValue() * 2700);
    this.birthsPerStep =
      (int) Math.round(this.birthRate.getValue() * SOURCE_BIRTHS_PER_FRAME * 2);
    this.flowScale = this.flow.getValue() * 2;
    this.warpScale = this.warp.getValue() * 18;
    this.flattenAmount = this.flatten.getValue() * (2. / 3);
    this.sceneScale = Math.pow(10, (this.zoom.getValue() - .5) * 2);

    double angle = (this.rotation.getValue() - .5) * Math.PI * 2;
    this.cosRotation = Math.cos(angle);
    this.sinRotation = Math.sin(angle);
    this.panX = (this.centerX.getValue() - .5) * 1.2 * this.sceneScale;
    this.panY = (this.centerY.getValue() - .5) * 1.2 * this.sceneScale;
    this.glowOffset = this.glow.getValue() * 6;
    this.pointAlpha = this.opacity.getValue() * (124. / 255);
    this.outputBackground = this.background.getValue();
    this.outputLevel = this.level.getValue();

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    // background(0,6) at the default. Fade remains tied to wall time even when
    // Speed pauses the vector simulation.
    double backgroundAlpha60 = Math.pow(.01, this.trails.getValue());
    double backgroundTransmission = Math.pow(1 - backgroundAlpha60, frameEquivalent);
    for (int cell = 0; cell < GRID_CELLS; ++cell) {
      this.ink[cell] = this.outputBackground +
        (this.ink[cell] - this.outputBackground) * backgroundTransmission;
    }

    this.simulationAccumulator += frameEquivalent * this.speed.getValue() * 2;
    int simulationSteps = (int) Math.floor(this.simulationAccumulator);
    this.simulationAccumulator -= simulationSteps;
    for (int step = 0; step < simulationSteps; ++step) {
      ++this.frameClock;
      updateAndDrawParticles();
      updateLifecycle();
    }

    draw();
  }

  /** Updates and draws the current array, matching the source's map() order. */
  private void updateAndDrawParticles() {
    for (int n = 0; n < this.particleCount; ++n) {
      int index = (this.particleHead + n) % MAX_PARTICLES;
      double x = this.particleX[index];
      double y = this.particleY[index];
      double z = this.particleZ[index];
      double magnitude = Math.sqrt(x * x + y * y + z * z);
      double denominator = Math.cos(magnitude * 2 - this.frameClock / 99.);
      double r = 4 + this.warpScale / denominator;

      // The source divides by array index. At n=0 this intentionally creates a
      // non-finite oldest vector; splat() clips it like an off-canvas point.
      x += Math.sin(y * r) / n * 9 * this.flowScale;
      y += Math.cos(r * x) / n * 9 * this.flowScale; // Uses the newly updated x.
      z -= z * this.flattenAmount;
      this.particleX[index] = x;
      this.particleY[index] = y;
      this.particleZ[index] = z;

      double dx = x / 6 * this.sceneScale;
      double dy = y / 6 * this.sceneScale;
      double screenX = dx * this.cosRotation - dy * this.sinRotation + .5 + this.panX;
      double screenY = dx * this.sinRotation + dy * this.cosRotation + .5 + this.panY;
      splat(screenX * GRID_LAST, screenY * GRID_LAST);
    }
  }

  /** Implements {@code $[3000] ? $.slice(-2985) : [...$, ...15 new vectors]}. */
  private void updateLifecycle() {
    if (this.particleCount > this.particleThreshold) {
      int keep = Math.max(0, this.particleThreshold - SOURCE_BIRTHS_PER_FRAME);
      int remove = this.particleCount - keep;
      this.particleHead = (this.particleHead + remove) % MAX_PARTICLES;
      this.particleCount = keep;
      return;
    }

    for (int born = 0; born < this.birthsPerStep; ++born) {
      spawnParticle();
    }
  }

  private void spawnParticle() {
    double z = this.random.nextDouble() * 2 - 1;
    double angle = this.random.nextDouble() * Math.PI * 2;
    double radius = Math.sqrt(Math.max(0, 1 - z * z));
    double x = radius * Math.cos(angle);
    double y = radius * Math.sin(angle);

    int index;
    if (this.particleCount < MAX_PARTICLES) {
      index = (this.particleHead + this.particleCount) % MAX_PARTICLES;
      ++this.particleCount;
    } else {
      index = this.particleHead;
      this.particleHead = (this.particleHead + 1) % MAX_PARTICLES;
    }
    this.particleX[index] = x;
    this.particleY[index] = y;
    this.particleZ[index] = z;
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
