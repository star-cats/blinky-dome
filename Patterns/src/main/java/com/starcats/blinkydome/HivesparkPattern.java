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
 * Hivespark, ported from a compact Processing particle sketch. Ported from
 * Scripts/Hivespark.js.
 *
 * Random unit vectors accumulate into a 4,000-particle binary flow field. The
 * source's changing RGB strokes are converted to normalized Rec.709 luminance,
 * retaining their rhythmic variation as a strictly monochrome black-to-white
 * signal. A persistent buffer reproduces background(0, 9) motion trails.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Hivespark")
@LXComponent.Description("Sparks streaming through a binary flow field, trailing monochrome light")
public class HivesparkPattern extends LXPattern {

  private static final int MAX_PARTICLES = 4000;
  private static final int SOURCE_BIRTHS_PER_FRAME = 15;
  private static final double REFERENCE_FPS = 60;
  private static final double MAX_SOURCE_LUMA = 0.8230737254901961;

  private static final int GRID_SIZE = 256;
  private static final int GRID_LAST = GRID_SIZE - 1;
  private static final int GRID_CELLS = GRID_SIZE * GRID_SIZE;

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .5, 0, 1)
    .setDescription("Rate of the particle-flow simulation");

  public final CompoundParameter birthRate =
    new CompoundParameter("Birth Rate", .5, 0, 1)
    .setDescription("How quickly new sparks enter the hive");

  public final CompoundParameter population =
    new CompoundParameter("Population", 1, 0, 1)
    .setDescription("Maximum number of live sparks");

  public final CompoundParameter drift =
    new CompoundParameter("Drift", .5, 0, 1)
    .setDescription("Distance sparks move on each simulation step");

  public final CompoundParameter xCells =
    new CompoundParameter("X Cells", .43, 0, 1)
    .setDescription("Horizontal frequency of the binary flow field");

  public final CompoundParameter yCells =
    new CompoundParameter("Y Cells", .13, 0, 1)
    .setDescription("Vertical frequency of the binary flow field");

  public final CompoundParameter zoom =
    new CompoundParameter("Zoom", .5, 0, 1)
    .setDescription("Hive zoom from 0.1x to 10x; center is 1x");

  public final CompoundParameter rotation =
    new CompoundParameter("Rotation", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Rotate the complete hive; center is upright");

  public final CompoundParameter centerX =
    new CompoundParameter("Center X", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Horizontal hive position");

  public final CompoundParameter centerY =
    new CompoundParameter("Center Y", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Vertical hive position");

  public final CompoundParameter trails =
    new CompoundParameter("Trails", .726, 0, 1)
    .setDescription("Persistence of previous spark positions");

  public final CompoundParameter glow =
    new CompoundParameter("Glow", .375, 0, 1)
    .setDescription("Radius of the spark glow");

  public final CompoundParameter opacity =
    new CompoundParameter("Opacity", 1, 0, 1)
    .setDescription("Opacity of each monochrome spark");

  public final CompoundParameter contrast =
    new CompoundParameter("Contrast", .5, 0, 1)
    .setDescription("Contrast of the black-to-white particle sequence");

  public final CompoundParameter background =
    new CompoundParameter("Background", 0, 0, 1)
    .setDescription("Background brightness");

  public final CompoundParameter level =
    new CompoundParameter("Level", 1, 0, 1)
    .setDescription("Overall output brightness");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Keep the hive circular on non-square models");

  private double[] ink = null;
  private double[] particleX = null;
  private double[] particleY = null;
  private double[] particleZ = null;
  private int particleHead = 0;
  private int particleCount = 0;

  private double spawnAccumulator = 0;
  private double motionAccumulator = 0;
  private int clock = 0;
  private double aspectX = 1;

  private int particleLimit = MAX_PARTICLES;
  private double driftScale = 1;
  private int fieldX = 4;
  private int fieldY = 2;
  private double sceneScale = 1;
  private double cosRotation = 1;
  private double sinRotation = 0;
  private double panX = 0;
  private double panY = 0;
  private double glowOffset = 2.25;
  private double pointAlpha = 1;
  private double lumaGamma = 1;
  private double outputBackground = 0;
  private double outputLevel = 1;

  private final Random random = new Random();

  public HivesparkPattern(LX lx) {
    super(lx);
    addParameter("speed", this.speed);
    addParameter("birthRate", this.birthRate);
    addParameter("population", this.population);
    addParameter("drift", this.drift);
    addParameter("xCells", this.xCells);
    addParameter("yCells", this.yCells);
    addParameter("zoom", this.zoom);
    addParameter("rotation", this.rotation);
    addParameter("centerX", this.centerX);
    addParameter("centerY", this.centerY);
    addParameter("trails", this.trails);
    addParameter("glow", this.glow);
    addParameter("opacity", this.opacity);
    addParameter("contrast", this.contrast);
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

    this.particleLimit =
      (int) Math.round(200 + this.population.getValue() * (MAX_PARTICLES - 200));
    this.driftScale = this.drift.getValue() * 2;
    this.fieldX = 1 + (int) Math.floor(this.xCells.getValue() * 7.999999);
    this.fieldY = 1 + (int) Math.floor(this.yCells.getValue() * 7.999999);
    this.sceneScale = Math.pow(10, (this.zoom.getValue() - .5) * 2);
    this.lumaGamma = Math.pow(4, (this.contrast.getValue() - .5) * 2);

    double angle = (this.rotation.getValue() - .5) * Math.PI * 2;
    this.cosRotation = Math.cos(angle);
    this.sinRotation = Math.sin(angle);
    // Pan in source space so its range grows with magnification.
    this.panX = (this.centerX.getValue() - .5) * 1.2 * this.sceneScale;
    this.panY = (this.centerY.getValue() - .5) * 1.2 * this.sceneScale;
    this.glowOffset = this.glow.getValue() * 6;
    this.outputBackground = this.background.getValue();
    this.outputLevel = this.level.getValue();

    double backgroundAlpha60 = Math.pow(.01, this.trails.getValue());
    double backgroundTransmission = Math.pow(1 - backgroundAlpha60, frameEquivalent);
    this.pointAlpha = 1 - Math.pow(1 - this.opacity.getValue(), frameEquivalent);

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    for (int cell = 0; cell < GRID_CELLS; ++cell) {
      this.ink[cell] = this.outputBackground +
        (this.ink[cell] - this.outputBackground) * backgroundTransmission;
    }

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

    this.motionAccumulator += frameEquivalent * this.speed.getValue() * 2;
    int motionSteps = (int) Math.floor(this.motionAccumulator);
    this.motionAccumulator -= motionSteps;
    for (int step = 0; step < motionSteps; ++step) {
      ++this.clock;
      updateParticles();
    }

    // Original color sequence, converted to perceptual luminance and normalized
    // so its brightest RGB triplet becomes white rather than pale gray.
    for (int n = 0; n < this.particleCount; ++n) {
      int index = (this.particleHead + n) % MAX_PARTICLES;
      int red = (n % 15) * 17;
      int green = (n % 5) * 50;
      int blue = (n % 10) * 25;
      double sourceLuma = (.2126 * red + .7152 * green + .0722 * blue) /
        (255 * MAX_SOURCE_LUMA);
      sourceLuma = Math.pow(sourceLuma, this.lumaGamma);

      double dx = this.particleX[index] / 6 * this.sceneScale;
      double dy = this.particleY[index] / 6 * this.sceneScale;
      double x = dx * this.cosRotation - dy * this.sinRotation + .5 + this.panX;
      double y = dx * this.sinRotation + dy * this.cosRotation + .5 + this.panY;
      splat(x * GRID_LAST, y * GRID_LAST, sourceLuma);
    }

    draw();
  }

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
    this.particleZ[index] = z;
  }

  private void updateParticles() {
    double sineTime = Math.sin(this.clock / 99.);
    for (int n = 0; n < this.particleCount; ++n) {
      int index = (this.particleHead + n) % MAX_PARTICLES;
      double x = this.particleX[index];
      double y = this.particleY[index];
      double z = this.particleZ[index];
      // JavaScript's + binds tighter than ^, so each side keeps its own + 9.
      int polarity = ((toInt32(x * this.fieldX + 9) ^ toInt32(y * this.fieldY + 9)) & 1)
        * 2 - 1;

      // sin(polarity*t/99) is polarity*sin(t/99), while cos(polarity) is
      // cos(1) for both signs. Expanding those identities removes all per-point
      // trig calls without altering the binary field.
      x += polarity * sineTime / 99 * z * this.driftScale;
      y += Math.cos(1) / 99 * z * this.driftScale;
      this.particleX[index] = x;
      this.particleY[index] = y;
    }
  }

  private void draw() {
    final double bg = this.outputBackground;
    final double lvl = this.outputLevel;
    final double offset = this.glowOffset;

    for (LXPoint point : this.model.points) {
      double u = .5 + (point.xn - .5) * this.aspectX;
      double v = 1 - point.yn;
      if (u < 0 || u > 1 || v < 0 || v > 1) {
        this.colors[point.index] = LXColor.gray(bg * lvl * 100);
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

      this.colors[point.index] = LXColor.gray(Math.min(1, bg + signal) * lvl * 100);
    }
  }

  /** Alpha-composites one grayscale point into four anti-aliased cells. */
  private void splat(double x, double y, double sourceLuma) {
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

    compositeCell(y0 * GRID_SIZE + x0, sourceLuma,
      this.pointAlpha * (1 - fx) * (1 - fy));
    compositeCell(y0 * GRID_SIZE + x1, sourceLuma,
      this.pointAlpha * fx * (1 - fy));
    compositeCell(y1 * GRID_SIZE + x0, sourceLuma,
      this.pointAlpha * (1 - fx) * fy);
    compositeCell(y1 * GRID_SIZE + x1, sourceLuma,
      this.pointAlpha * fx * fy);
  }

  private void compositeCell(int index, double sourceLuma, double alpha) {
    this.ink[index] += (sourceLuma - this.ink[index]) * alpha;
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
   * JavaScript's ToInt32 coercion, which the binary flow field is built on.
   *
   * A plain Java cast saturates at Integer.MAX_VALUE where JavaScript wraps
   * modulo 2^32, and a saturated operand would pin the field's parity bit for
   * any spark that drifts far enough out.
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
