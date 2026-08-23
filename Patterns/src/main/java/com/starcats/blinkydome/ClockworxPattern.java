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
 * Clockworx, inspired by a compact Processing particle sketch. Ported from
 * Scripts/Clockworx.js.
 *
 * Fifteen random unit vectors are born per source frame, up to roughly 5,000.
 * Their X/Y components move through the original bitwise clockwork field while
 * a translucent background leaves fading traces. Particles live in fixed-size
 * ring buffers, so the pattern does not allocate continuously as it runs.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Clockworx")
@LXComponent.Description("Particles moving through a bitwise clockwork field, leaving fading traces")
public class ClockworxPattern extends LXPattern {

  private static final int MAX_PARTICLES = 5015;
  private static final int SOURCE_BIRTHS_PER_FRAME = 15;
  private static final double REFERENCE_FPS = 60;
  // Dividing this by 90 gives 32,000, whose low five bits are zero. Subtracting
  // it therefore leaves every supported & mask (1 through 31) exactly unchanged.
  private static final int CLOCK_PERIOD = 2880000;

  private static final int GRID_SIZE = 256;
  private static final int GRID_LAST = GRID_SIZE - 1;
  private static final int GRID_CELLS = GRID_SIZE * GRID_SIZE;

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .5, 0, 1)
    .setDescription("Rate of the clockwork particle simulation");

  public final CompoundParameter birthRate =
    new CompoundParameter("Birth Rate", .5, 0, 1)
    .setDescription("How quickly new particles enter the dance");

  public final CompoundParameter population =
    new CompoundParameter("Population", 1, 0, 1)
    .setDescription("Maximum number of live particles");

  public final CompoundParameter drift =
    new CompoundParameter("Drift", .5, 0, 1)
    .setDescription("Distance particles move on each simulation step");

  public final CompoundParameter gears =
    new CompoundParameter("Gears", .5, 0, 1)
    .setDescription("Number of bit-mask gears; center is the original & 7 field");

  public final CompoundParameter torque =
    new CompoundParameter("Torque", .5, 0, 1)
    .setDescription("Strength of the discrete rotational field");

  public final CompoundParameter size =
    new CompoundParameter("Zoom", .5, 0, 1)
    .setDescription("Mechanism zoom from 0.1x to 10x; center is 1x");

  public final CompoundParameter rotation =
    new CompoundParameter("Rotation", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Rotate the complete mechanism; center is upright");

  public final CompoundParameter centerX =
    new CompoundParameter("Center X", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Horizontal mechanism position");

  public final CompoundParameter centerY =
    new CompoundParameter("Center Y", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Vertical mechanism position");

  public final CompoundParameter trails =
    new CompoundParameter("Trails", .726, 0, 1)
    .setDescription("Persistence of previous particle positions");

  public final CompoundParameter glow =
    new CompoundParameter("Glow", .375, 0, 1)
    .setDescription("Radius of the particle glow");

  public final CompoundParameter opacity =
    new CompoundParameter("Opacity", .502, 0, 1)
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
    .setDescription("Keep the mechanism circular on non-square models");

  private double[] ink = null;
  private double[] particleX = null;
  private double[] particleY = null;
  private int particleHead = 0;
  private int particleCount = 0;

  private double spawnAccumulator = 0;
  private double motionAccumulator = 0;
  private int clock = 0;
  private double aspectX = 1;

  // Per-frame control values.
  private int particleLimit = MAX_PARTICLES;
  private double driftScale = 1;
  private int gearMask = 7;
  private double torqueScale = 9;
  private double sceneScale = 1;
  private double cosRotation = 1;
  private double sinRotation = 0;
  private double panX = 0;
  private double panY = 0;
  private double glowOffset = 2.25;
  private double pointAlpha = 96. / 255;
  private double outputBackground = 0;
  private double outputLevel = 1;

  private final Random random = new Random();

  public ClockworxPattern(LX lx) {
    super(lx);
    addParameter("speed", this.speed);
    addParameter("birthRate", this.birthRate);
    addParameter("population", this.population);
    addParameter("drift", this.drift);
    addParameter("gears", this.gears);
    addParameter("torque", this.torque);
    addParameter("size", this.size);
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
   * Allocates the buffers on first use rather than at construction.
   *
   * A project holds every pattern it has ever been given, and only one of them
   * is running; half a megabyte of ink apiece is worth deferring until the
   * pattern actually draws.
   */
  private void init() {
    this.ink = new double[GRID_CELLS];
    this.particleX = new double[MAX_PARTICLES];
    this.particleY = new double[MAX_PARTICLES];
  }

  @Override
  protected void run(double deltaMs) {
    if (this.ink == null) {
      init();
    }

    double dt = Double.isFinite(deltaMs) ? Math.max(0, Math.min(deltaMs / 1000, .25)) : 0;
    double frameEquivalent = dt * REFERENCE_FPS;

    this.particleLimit =
      (int) Math.round(200 + this.population.getValue() * (MAX_PARTICLES - 200));
    this.driftScale = this.drift.getValue() * 2;
    int gearBits = 1 + (int) Math.floor(this.gears.getValue() * 4.999999);
    this.gearMask = (1 << gearBits) - 1;
    this.torqueScale = 2 + this.torque.getValue() * 14;
    // Two decades of exponential zoom with an exact 1x center detent.
    this.sceneScale = Math.pow(10, (this.size.getValue() - .5) * 2);

    double angle = (this.rotation.getValue() - .5) * Math.PI * 2;
    this.cosRotation = Math.cos(angle);
    this.sinRotation = Math.sin(angle);
    // Pan is effectively measured in source space, then enlarged with Zoom. At
    // 10x this gives +/-6 screen widths for exploring the magnified mechanism.
    this.panX = (this.centerX.getValue() - .5) * 1.2 * this.sceneScale;
    this.panY = (this.centerY.getValue() - .5) * 1.2 * this.sceneScale;
    this.glowOffset = this.glow.getValue() * 6;
    this.outputBackground = this.background.getValue();
    this.outputLevel = this.level.getValue();

    // background(0, 9) at the Trails default, generalized to the actual frame
    // duration. The exponential mapping gives the upper knob range useful room.
    double backgroundAlpha60 = Math.pow(.01, this.trails.getValue());
    double backgroundTransmission = Math.pow(1 - backgroundAlpha60, frameEquivalent);
    double pointAlpha60 = this.opacity.getValue() * .75;
    this.pointAlpha = 1 - Math.pow(1 - pointAlpha60, frameEquivalent);

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    for (int cell = 0; cell < GRID_CELLS; ++cell) {
      this.ink[cell] = this.outputBackground +
        (this.ink[cell] - this.outputBackground) * backgroundTransmission;
    }

    // If Population is reduced, discard the oldest particles first.
    while (this.particleCount > this.particleLimit) {
      this.particleHead = (this.particleHead + 1) % MAX_PARTICLES;
      --this.particleCount;
    }

    double birthsPerFrame = this.birthRate.getValue() * SOURCE_BIRTHS_PER_FRAME * 2;
    this.spawnAccumulator += frameEquivalent * birthsPerFrame;
    int births = (int) Math.floor(this.spawnAccumulator);
    this.spawnAccumulator -= births;
    for (int born = 0; born < births; ++born) {
      spawnParticle();
    }

    // Fixed source-frame steps keep the nonlinear state update stable. A hitch
    // may catch up several steps, but deltaMs is capped above to bound the work.
    this.motionAccumulator += frameEquivalent * this.speed.getValue() * 2;
    int motionSteps = (int) Math.floor(this.motionAccumulator);
    this.motionAccumulator -= motionSteps;
    for (int step = 0; step < motionSteps; ++step) {
      ++this.clock;
      if (this.clock >= CLOCK_PERIOD) {
        this.clock -= CLOCK_PERIOD;
      }
      updateParticles();
    }

    for (int n = 0; n < this.particleCount; ++n) {
      int index = (this.particleHead + n) % MAX_PARTICLES;
      double dx = this.particleX[index] * (119. / 540) * this.sceneScale;
      double dy = this.particleY[index] * (119. / 540) * this.sceneScale;
      double x = dx * this.cosRotation - dy * this.sinRotation + .5 + this.panX;
      double y = dx * this.sinRotation + dy * this.cosRotation + .5 + this.panY;
      splat(x * GRID_LAST, y * GRID_LAST);
    }

    draw();
  }

  /** Adds one p5.Vector.random3D()-equivalent particle to the ring. */
  private void spawnParticle() {
    double z = this.random.nextDouble() * 2 - 1;
    double angle = this.random.nextDouble() * Math.PI * 2;
    double radius = Math.sqrt(Math.max(0, 1 - z * z));
    double x = radius * Math.cos(angle);
    double y = radius * Math.sin(angle);

    int index;
    if (this.particleCount < this.particleLimit) {
      index = (this.particleHead + this.particleCount) % MAX_PARTICLES;
      ++this.particleCount;
    } else {
      index = this.particleHead;
      this.particleHead = (this.particleHead + 1) % MAX_PARTICLES;
    }
    this.particleX[index] = x;
    this.particleY[index] = y;
  }

  private void updateParticles() {
    for (int n = 0; n < this.particleCount; ++n) {
      int index = (this.particleHead + n) % MAX_PARTICLES;
      double x = this.particleX[index];
      double y = this.particleY[index];
      double k = x + 5 + y;

      // toInt32 makes the compact source's coercion explicit: ^ and & are
      // JavaScript 32-bit integer operators even though their inputs are floats.
      // JavaScript's + binds tighter than ^, so the clock term joins y * k.
      int bucket = (toInt32(x * k) ^ toInt32(y * k + this.clock / 90.)) & this.gearMask;
      double r = this.torqueScale * bucket - .1;
      x += Math.sin(r * y) / 119 * this.driftScale;
      y += Math.cos(x * r) / 119 * this.driftScale; // Uses the newly updated x.
      this.particleX[index] = x;
      this.particleY[index] = y;
    }
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

  /**
   * JavaScript's ToInt32 coercion, which the clockwork field is built on.
   *
   * A plain Java cast will not do: it saturates at Integer.MAX_VALUE, while the
   * source's operands are chaotic and routinely leave 32-bit range, where
   * JavaScript wraps modulo 2^32 instead. Saturating there would freeze the
   * bucket at a single value for every runaway particle.
   */
  private static int toInt32(double value) {
    if (!Double.isFinite(value)) {
      return 0;
    }
    double truncated = (value < 0) ? Math.ceil(value) : Math.floor(value);
    // Now inside +/-2^32, so the long is exact and its low word is the answer.
    return (int) (long) (truncated % 4294967296.);
  }
}
