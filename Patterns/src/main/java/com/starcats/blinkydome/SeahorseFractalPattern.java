package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Mandelbrot / Multibrot explorer aimed at the famous Seahorse Valley.
 *
 * <p>The camera zooms exponentially toward
 * {@code -0.743643887037151 + 0.131825904205330i} while an independent clock
 * rotates the view. Ordinary floating point cannot zoom into a Mandelbrot set
 * forever, so the last two of every fourteen zoom octaves crossfade into a new
 * wide pass. That pass becomes the next cycle's primary image at an identical
 * frame, giving the animation a continuous temporal seam. The iteration budget
 * rises with depth so the boundary does not disappear before the handoff.</p>
 *
 * <p>The expensive escape calculation runs on an offscreen grid at half the
 * model's inferred horizontal and vertical resolution (about one quarter as
 * many evaluations as LEDs). Each LED then bilinearly samples that grid. This
 * is intentionally a luminance grid rather than a color grid, so interpolation
 * preserves the normalized grayscale signal used for downstream recoloring.</p>
 *
 * <p>Power continuously moves through the Multibrot family. Julia Morph moves
 * both initial values from the Mandelbrot state ({@code z = 0, c = pixel}) to
 * the Julia state ({@code z = pixel, c = Julia Re + Julia Im i}). Output is a
 * normalized grayscale signal through {@link LXColor#grayn(double)}, ready for
 * downstream recoloring.</p>
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Seahorse Fractal")
@LXComponent.Description("Seamlessly looping Mandelbrot zoom into Seahorse Valley")
public class SeahorseFractalPattern extends LXPattern {

  private static final double TAU = 2 * Math.PI;
  private static final double LOOP_ZOOM_OCTAVES = 14;
  private static final double LOOP_BLEND_OCTAVES = 2;
  private static final double BASE_VIEW_WIDTH = .035;

  public final CompoundParameter zoomSpeed =
    new CompoundParameter("Zoom Speed", .01 * Math.pow(40, .28), 0, .4)
    .setDescription("Exponential camera speed in zoom octaves per second; zero pauses");

  public final CompoundParameter rotateSpeed =
    new CompoundParameter("Rotate Speed", .12 * .12 * .12, -.12, .12)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Camera rotation in turns per second; zero is still");

  public final CompoundParameter centerX =
    new CompoundParameter("Center X", -.743643887037151, -2.1, .8)
    .setDescription("Real component of the zoom target");

  public final CompoundParameter centerY =
    new CompoundParameter("Center Y", .131825904205330, -1.4, 1.4)
    .setDescription("Imaginary component of the zoom target");

  public final CompoundParameter power =
    new CompoundParameter("Power", 2, 2, 6)
    .setDescription("Continuous exponent in z^power + c; 2 is the classic Mandelbrot set");

  public final CompoundParameter juliaMorph =
    new CompoundParameter("Julia Morph", 0, 0, 1)
    .setDescription("Continuously morph the Mandelbrot set into a Julia set");

  public final CompoundParameter juliaRe =
    new CompoundParameter("Julia Re", -.8, -2, 1)
    .setDescription("Real part of the Julia constant");

  public final CompoundParameter juliaIm =
    new CompoundParameter("Julia Im", .156, -1.5, 1.5)
    .setDescription("Imaginary part of the Julia constant");

  public final DiscreteParameter iterations =
    new DiscreteParameter("Iterations", 107, 40, 181)
    .setDescription("Base escape iterations; deep zoom adds more automatically");

  public final CompoundParameter bands =
    new CompoundParameter("Bands", lerp(.035, .22, .38), .035, .22)
    .setDescription("Monochrome detail frequency across the boundary");

  public final CompoundParameter level =
    new CompoundParameter("Level", .9, 0, 1)
    .setDescription("Maximum normalized output level");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Correct for a non-square model");

  private double zoomOctaves = 0;
  private double cameraAngle = 0;

  /** Half-resolution offscreen luminance raster, resized when the model changes. */
  private double[] raster = new double[0];
  private int rasterWidth = 0;
  private int rasterHeight = 0;

  public SeahorseFractalPattern(LX lx) {
    super(lx);
    addParameter("zoomSpeed", this.zoomSpeed);
    addParameter("rotateSpeed", this.rotateSpeed);
    addParameter("centerX", this.centerX);
    addParameter("centerY", this.centerY);
    addParameter("power", this.power);
    addParameter("juliaMorph", this.juliaMorph);
    addParameter("juliaRe", this.juliaRe);
    addParameter("juliaIm", this.juliaIm);
    addParameter("iterations", this.iterations);
    addParameter("bands", this.bands);
    addParameter("level", this.level);
    addParameter("autoAspect", this.autoAspect);
  }

  @Override
  protected void run(double deltaMs) {
    final double dt = Double.isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, .25) : 0;
    this.zoomOctaves = wrap(this.zoomOctaves + dt * this.zoomSpeed.getValue(), LOOP_ZOOM_OCTAVES);
    this.cameraAngle = wrapAngle(
      this.cameraAngle + dt * this.rotateSpeed.getValue() * TAU
    );

    final double cosCamera = Math.cos(this.cameraAngle);
    final double sinCamera = Math.sin(this.cameraAngle);
    final double aspectX = (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0)
      ? this.model.xRange / this.model.yRange
      : 1;
    final double blendStart = LOOP_ZOOM_OCTAVES - LOOP_BLEND_OCTAVES;
    final boolean blending = this.zoomOctaves > blendStart;
    final double blend = blending
      ? smoothstep(blendStart, LOOP_ZOOM_OCTAVES, this.zoomOctaves)
      : 0;

    ensureRasterSize(aspectX);
    renderRaster(cosCamera, sinCamera, aspectX, blending, blend);

    for (LXPoint point : this.model.points) {
      this.colors[point.index] = LXColor.grayn(sampleRaster(point.xn, point.yn));
    }
  }

  /**
   * Infers a square-pixel full-resolution raster from point count and model
   * aspect, then halves each dimension. This gives roughly N/4 fractal samples
   * for N LEDs without assuming that the active model is a particular panel.
   */
  private void ensureRasterSize(double aspectX) {
    final int pointCount = Math.max(1, this.model.points.length);
    final double rasterAspect = clamp(aspectX, .25, 4);
    final int width = Math.max(2, (int) Math.round(Math.sqrt(pointCount * rasterAspect) * .5));
    final int height = Math.max(2, (int) Math.round(Math.sqrt(pointCount / rasterAspect) * .5));

    if (width != this.rasterWidth || height != this.rasterHeight) {
      this.rasterWidth = width;
      this.rasterHeight = height;
      this.raster = new double[width * height];
    }
  }

  private void renderRaster(
    double cosCamera,
    double sinCamera,
    double aspectX,
    boolean blending,
    double blend
  ) {
    final double recursiveDepth = this.zoomOctaves - LOOP_ZOOM_OCTAVES;
    final double xScale = 1. / (this.rasterWidth - 1);
    final double yScale = 1. / (this.rasterHeight - 1);

    for (int y = 0; y < this.rasterHeight; ++y) {
      final double screenY = y * yScale - .5;
      final int row = y * this.rasterWidth;

      for (int x = 0; x < this.rasterWidth; ++x) {
        final double screenX = (x * xScale - .5) * aspectX;
        final double rotatedX = screenX * cosCamera - screenY * sinCamera;
        final double rotatedY = screenX * sinCamera + screenY * cosCamera;
        double output = intensityForEscape(
          sampleFractal(rotatedX, rotatedY, this.zoomOctaves)
        );

        if (blending) {
          final double recursiveOutput = intensityForEscape(
            sampleFractal(rotatedX, rotatedY, recursiveDepth)
          );
          output = lerp(output, recursiveOutput, blend);
        }
        this.raster[row + x] = clamp(output);
      }
    }
  }

  /** Bilinear interpolation of the half-resolution luminance raster. */
  private double sampleRaster(double u, double v) {
    final double x = clamp(u) * (this.rasterWidth - 1);
    final double y = clamp(v) * (this.rasterHeight - 1);
    final int x0 = (int) x;
    final int y0 = (int) y;
    final int x1 = Math.min(x0 + 1, this.rasterWidth - 1);
    final int y1 = Math.min(y0 + 1, this.rasterHeight - 1);
    final double tx = x - x0;
    final double ty = y - y0;
    final int row0 = y0 * this.rasterWidth;
    final int row1 = y1 * this.rasterWidth;
    final double top = lerp(this.raster[row0 + x0], this.raster[row0 + x1], tx);
    final double bottom = lerp(this.raster[row1 + x0], this.raster[row1 + x1], tx);
    return lerp(top, bottom, ty);
  }

  /** Returns fractional escape time, or -1 for a point inside the filled set. */
  private double sampleFractal(double rotatedX, double rotatedY, double zoomDepth) {
    final double viewWidth = BASE_VIEW_WIDTH * Math.pow(2, -zoomDepth);
    final double pixelX = this.centerX.getValue() + rotatedX * viewWidth;
    final double pixelY = this.centerY.getValue() + rotatedY * viewWidth;
    final double morph = this.juliaMorph.getValue();
    final double exponent = this.power.getValue();
    final double cx = lerp(pixelX, this.juliaRe.getValue(), morph);
    final double cy = lerp(pixelY, this.juliaIm.getValue(), morph);
    double zx = pixelX * morph;
    double zy = pixelY * morph;
    double radiusSquared = 0;
    int iteration = 0;
    final int maxIterations = this.iterations.getValuei()
      + (int) Math.round(Math.max(0, zoomDepth) * 9);

    if (Math.abs(exponent - 2) < .0001) {
      // The default quadratic path avoids trigonometry in the inner loop.
      for (; iteration < maxIterations; ++iteration) {
        final double zx2 = zx * zx;
        final double zy2 = zy * zy;
        radiusSquared = zx2 + zy2;
        if (radiusSquared > 256) {
          break;
        }
        zy = 2 * zx * zy + cy;
        zx = zx2 - zy2 + cx;
      }
    } else {
      // Polar complex power lets non-integer exponents morph continuously.
      for (; iteration < maxIterations; ++iteration) {
        radiusSquared = zx * zx + zy * zy;
        if (radiusSquared > 256) {
          break;
        }
        final double radiusPower = Math.pow(radiusSquared, exponent * .5);
        final double argument = Math.atan2(zy, zx) * exponent;
        zx = radiusPower * Math.cos(argument) + cx;
        zy = radiusPower * Math.sin(argument) + cy;
      }
    }

    if (iteration >= maxIterations) {
      return -1;
    }
    return iteration + 1
      - Math.log(Math.log(Math.sqrt(radiusSquared))) / Math.log(exponent);
  }

  private double intensityForEscape(double smoothIteration) {
    if (smoothIteration < 0) {
      return 0;
    }
    final double band = .72 + .28 * Math.cos(TAU * smoothIteration * this.bands.getValue());
    return clamp(this.level.getValue() * band);
  }

  private static double smoothstep(double edge0, double edge1, double value) {
    final double t = clamp((value - edge0) / (edge1 - edge0));
    return t * t * (3 - 2 * t);
  }

  private static double wrap(double value, double period) {
    return value - Math.floor(value / period) * period;
  }

  private static double wrapAngle(double value) {
    return value - Math.floor((value + Math.PI) / TAU) * TAU;
  }

  private static double clamp(double value) {
    return (value < 0) ? 0 : (value > 1) ? 1 : value;
  }

  private static double clamp(double value, double min, double max) {
    return (value < min) ? min : (value > max) ? max : value;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
