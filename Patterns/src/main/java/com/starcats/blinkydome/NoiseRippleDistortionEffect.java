package com.starcats.blinkydome;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.effect.LXEffect;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;

/**
 * Noise Ripple Distortion, ported from Scripts/NoiseRippleDistortion.js.
 *
 * Displaces whatever is underneath in X and Y with two seamless noise maps.
 * Perlin drives broad horizontal flow while Swirl supplies a more directional
 * vertical ripple. Their texture coordinates drift at different rates and in
 * different directions, which is what keeps the two axes from locking into one
 * diagonal translation. The noise repeats seamlessly; displaced samples clip at
 * the model boundaries.
 *
 * Sampling is off a snapshot taken before anything is written, and that is not
 * an optimization — it is the whole correctness argument for an effect that
 * reads its own output. Writing in point order into the live buffer would have
 * later points sampling the already-distorted results of earlier ones, so the
 * distortion would compound along whatever arbitrary order the model happens to
 * enumerate in.
 *
 * A model is not a grid, so "the color at (u,v)" needs defining. A lookup table
 * is built once per model: a 128x128 grid of nearest-point indices, found
 * through expanding bins, and then sampled bilinearly. Rebuilt only when the
 * model changes.
 *
 * The one deliberate difference from the script is how the textures are found.
 * The script walks up from the JVM's working directory guessing at where the
 * media folder might be, because a script has no handle on Chromatik itself. An
 * effect does: lx.getMediaFile resolves against the real media root.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Noise Ripple Distortion")
@LXComponent.Description("Displace the input with two drifting noise fields")
public class NoiseRippleDistortionEffect extends LXEffect {

  private static final String X_NOISE_PATH = "Images/Noise/Perlin_14-128x128.png";
  private static final String Y_NOISE_PATH = "Images/Noise/Swirl_03-128x128.png";

  /**
   * Resolution of the reusable XY-to-model-point lookup. Matching the source
   * textures keeps the resampling smooth without making model indexing costly.
   */
  private static final int LOOKUP_SIZE = 128;
  private static final int BIN_COUNT = 32;
  private static final double MAX_DISPLACEMENT = .28;
  /**
   * Keeps accumulated phases numerically bounded without interrupting any of
   * the fractional-rate texture trajectories. Both .31 and .43 produce whole-
   * number texture offsets over this interval, so the eventual wrap is seamless.
   */
  private static final double PHASE_WRAP = 10000;

  public final CompoundParameter speed =
    new CompoundParameter("Motion Speed", .35, 0, 1)
    .setDescription("How quickly the two distortion fields drift");

  public final CompoundParameter intensity =
    new CompoundParameter("Distortion", .4, 0, 1)
    .setDescription("Maximum edge-clipped X/Y displacement");

  /** A noise texture, with the extremes signedNoise centers and scales on. */
  private static final class Texture {
    final int w;
    final int h;
    final int[] px;
    final double center;
    final double halfSpan;

    Texture(int w, int h, int[] px, double center, double halfSpan) {
      this.w = w;
      this.h = h;
      this.px = px;
      this.center = center;
      this.halfSpan = halfSpan;
    }
  }

  private Texture xNoise = null;
  private Texture yNoise = null;
  private boolean loadAttempted = false;

  private double phaseX = 0;
  private double phaseY = 0;
  private double displacement = 0;

  private int[] sourceColors = null;
  private LXModel indexedModel = null;
  private int indexedPointCount = -1;
  private final int[] binHead = new int[BIN_COUNT * BIN_COUNT];
  private int[] pointNext = null;
  private int[] sourceLookup = null;
  private int nearestBestPoint = -1;
  private double nearestBestDistanceSq = Double.POSITIVE_INFINITY;

  public NoiseRippleDistortionEffect(LX lx) {
    super(lx);
    addParameter("speed", this.speed);
    addParameter("intensity", this.intensity);
  }

  @Override
  protected void run(double deltaMs, double enabledAmount) {
    if (!this.loadAttempted) {
      this.loadAttempted = true;
      this.xNoise = loadTexture(X_NOISE_PATH);
      this.yNoise = loadTexture(Y_NOISE_PATH);
    }

    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs * .001, 0, .25) : 0;
    double rate = lerp(0, .65, this.speed.getValue());

    // Deliberately different rates and directions keep the two axes from
    // locking into one diagonal translation.
    this.phaseX = wrap(this.phaseX + dt * rate, PHASE_WRAP);
    this.phaseY = wrap(this.phaseY - dt * rate * .63, PHASE_WRAP);
    this.displacement = this.intensity.getValue() * MAX_DISPLACEMENT;

    if (this.indexedModel != this.model
        || this.indexedPointCount != this.model.points.length) {
      buildSourceLookup();
    }

    if (this.xNoise == null || this.yNoise == null || this.sourceLookup == null
        || this.displacement <= 0 || enabledAmount <= 0) {
      return;
    }

    // The snapshot. Everything below reads this and never the live buffer.
    if (this.sourceColors == null || this.sourceColors.length != this.colors.length) {
      this.sourceColors = new int[this.colors.length];
    }
    System.arraycopy(this.colors, 0, this.sourceColors, 0, this.colors.length);

    double blend = clamp(enabledAmount, 0, 1);

    for (LXPoint point : this.model.points) {
      int inputColor = this.sourceColors[point.index];

      // Each map moves through both texture axes, but with different
      // trajectories. The maps are centered and normalized so Intensity has
      // equal authority over positive and negative displacement regardless of
      // each PNG's gray range.
      double dx = signedNoise(this.xNoise,
        point.xn * 1.7 + this.phaseX,
        point.yn * 1.7 + this.phaseX * .31);
      double dy = signedNoise(this.yNoise,
        point.xn * 1.35 + this.phaseY * .43,
        point.yn * 1.35 + this.phaseY);

      double sampleX = clamp(point.xn + dx * this.displacement, 0, 1);
      double sampleY = clamp(point.yn + dy * this.displacement, 0, 1);
      int distortedColor = sampleSource(sampleX, sampleY);
      this.colors[point.index] = LXColor.lerp(inputColor, distortedColor, blend);
    }
  }

  private int sampleSource(double u, double v) {
    double x = clamp(u, 0, 1) * (LOOKUP_SIZE - 1);
    double y = clamp(v, 0, 1) * (LOOKUP_SIZE - 1);
    int floorX = (int) Math.floor(x);
    int floorY = (int) Math.floor(y);
    int x0 = floorX;
    int y0 = floorY;
    int x1 = Math.min(x0 + 1, LOOKUP_SIZE - 1);
    int y1 = Math.min(y0 + 1, LOOKUP_SIZE - 1);
    double tx = x - floorX;
    double ty = y - floorY;

    int row0 = y0 * LOOKUP_SIZE;
    int row1 = y1 * LOOKUP_SIZE;
    int a = this.sourceColors[this.sourceLookup[row0 + x0]];
    int b = this.sourceColors[this.sourceLookup[row0 + x1]];
    int c = this.sourceColors[this.sourceLookup[row1 + x0]];
    int d = this.sourceColors[this.sourceLookup[row1 + x1]];
    return LXColor.lerp(
      LXColor.lerp(a, b, tx),
      LXColor.lerp(c, d, tx),
      ty
    );
  }

  /** Build an edge-clipped nearest-point lookup for arbitrary LX models. */
  private void buildSourceLookup() {
    this.indexedModel = this.model;
    this.indexedPointCount = this.model.points.length;

    if (this.model.points.length == 0) {
      this.sourceLookup = null;
      return;
    }

    this.sourceLookup = new int[LOOKUP_SIZE * LOOKUP_SIZE];
    if (this.pointNext == null || this.pointNext.length < this.model.points.length) {
      this.pointNext = new int[this.model.points.length];
    }

    for (int b = 0; b < BIN_COUNT * BIN_COUNT; ++b) {
      this.binHead[b] = -1;
    }

    for (int i = 0; i < this.model.points.length; ++i) {
      LXPoint point = this.model.points[i];
      int bx = Math.min(BIN_COUNT - 1, (int) Math.floor(clamp(point.xn, 0, 1) * BIN_COUNT));
      int by = Math.min(BIN_COUNT - 1, (int) Math.floor(clamp(point.yn, 0, 1) * BIN_COUNT));
      int bin = by * BIN_COUNT + bx;
      this.pointNext[i] = this.binHead[bin];
      this.binHead[bin] = i;
    }

    for (int y = 0; y < LOOKUP_SIZE; ++y) {
      double v = y / (double) (LOOKUP_SIZE - 1);
      for (int x = 0; x < LOOKUP_SIZE; ++x) {
        double u = x / (double) (LOOKUP_SIZE - 1);
        this.sourceLookup[y * LOOKUP_SIZE + x] = nearestPointIndex(u, v);
      }
    }
  }

  /** Find the nearest model point on a clipped XY plane using expanding bins. */
  private int nearestPointIndex(double u, double v) {
    int centerX = Math.min(BIN_COUNT - 1, (int) Math.floor(clamp(u, 0, 1) * BIN_COUNT));
    int centerY = Math.min(BIN_COUNT - 1, (int) Math.floor(clamp(v, 0, 1) * BIN_COUNT));
    this.nearestBestPoint = -1;
    this.nearestBestDistanceSq = Double.POSITIVE_INFINITY;

    for (int radius = 0; radius < BIN_COUNT; ++radius) {
      if (radius == 0) {
        inspectBin(centerY * BIN_COUNT + centerX, u, v);
      } else {
        for (int dx = -radius; dx <= radius; ++dx) {
          inspectBinAt(centerX + dx, centerY - radius, u, v);
          inspectBinAt(centerX + dx, centerY + radius, u, v);
        }
        for (int dy = -radius + 1; dy < radius; ++dy) {
          inspectBinAt(centerX - radius, centerY + dy, u, v);
          inspectBinAt(centerX + radius, centerY + dy, u, v);
        }
      }

      // Conservative lower bound: anything beyond this ring is at least
      // (radius-1) cells away, regardless of where u/v lie in the center cell.
      double unsearchedDistance = Math.max(0, radius - 1) / (double) BIN_COUNT;
      if (this.nearestBestPoint >= 0
          && unsearchedDistance * unsearchedDistance > this.nearestBestDistanceSq) {
        break;
      }
    }

    return (this.nearestBestPoint >= 0)
      ? this.model.points[this.nearestBestPoint].index
      : this.model.points[0].index;
  }

  private void inspectBinAt(int bx, int by, double u, double v) {
    if (bx >= 0 && bx < BIN_COUNT && by >= 0 && by < BIN_COUNT) {
      inspectBin(by * BIN_COUNT + bx, u, v);
    }
  }

  private void inspectBin(int bin, double u, double v) {
    for (int i = this.binHead[bin]; i >= 0; i = this.pointNext[i]) {
      LXPoint point = this.model.points[i];
      double dx = Math.abs(point.xn - u);
      double dy = Math.abs(point.yn - v);
      double distanceSq = dx * dx + dy * dy;
      if (distanceSq < this.nearestBestDistanceSq) {
        this.nearestBestDistanceSq = distanceSq;
        this.nearestBestPoint = i;
      }
    }
  }

  private static double signedNoise(Texture image, double u, double v) {
    double gray = sampleGray(image, u, v);
    return clamp((gray - image.center) / image.halfSpan, -1, 1);
  }

  /** Bilinear grayscale texture sampling with seamless wrapping on both axes. */
  private static double sampleGray(Texture image, double u, double v) {
    u = wrap01(u);
    v = wrap01(v);
    double x = u * image.w;
    double y = v * image.h;
    double floorX = Math.floor(x);
    double floorY = Math.floor(y);
    int x0 = (int) floorX % image.w;
    int y0 = (int) floorY % image.h;
    int x1 = (x0 + 1) % image.w;
    int y1 = (y0 + 1) % image.h;
    double tx = x - floorX;
    double ty = y - floorY;
    double a = grayOf(image.px[y0 * image.w + x0]);
    double b = grayOf(image.px[y0 * image.w + x1]);
    double c = grayOf(image.px[y1 * image.w + x0]);
    double d = grayOf(image.px[y1 * image.w + x1]);
    return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
  }

  private static double grayOf(int argb) {
    int r = (argb >> 16) & 0xff;
    int g = (argb >> 8) & 0xff;
    int b = argb & 0xff;
    return (r * .2126 + g * .7152 + b * .0722) / 255;
  }

  /**
   * Loads one noise texture out of the media folder.
   *
   * A missing or unreadable file leaves the effect a pass-through rather than
   * throwing: an effect that cannot distort should hand the input straight on,
   * and one that throws every frame is a wall of log spam.
   */
  private Texture loadTexture(String mediaPath) {
    File file = this.lx.getMediaFile(mediaPath);
    BufferedImage buffered;
    try {
      buffered = ImageIO.read(file);
    } catch (Exception x) {
      LX.error(x, "NoiseRippleDistortion: could not read " + file);
      return null;
    }
    if (buffered == null) {
      LX.error("NoiseRippleDistortion: could not read " + file);
      return null;
    }
    int w = buffered.getWidth();
    int h = buffered.getHeight();
    int[] px = buffered.getRGB(0, 0, w, h, new int[w * h], 0, w);
    double low = 1;
    double high = 0;

    for (int i = 0; i < w * h; ++i) {
      double gray = grayOf(px[i]);
      if (gray < low) {
        low = gray;
      }
      if (gray > high) {
        high = gray;
      }
    }

    return new Texture(w, h, px, (low + high) * .5, Math.max((high - low) * .5, 1e-6));
  }

  private static double wrap01(double value) {
    return value - Math.floor(value);
  }

  private static double wrap(double value, double period) {
    return value - Math.floor(value / period) * period;
  }

  private static double clamp(double v, double low, double high) {
    return (v < low) ? low : (v > high) ? high : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
