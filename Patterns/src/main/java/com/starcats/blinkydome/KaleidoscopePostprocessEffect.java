package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.effect.LXEffect;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;

/**
 * Kaleidoscope Postprocess, ported from Scripts/KaleidoscopePostprocess.js.
 *
 * Folds whatever is already on the screen through a mirror group. Each output
 * point maps to some <em>other</em> point of the current frame, so this adds no
 * color of its own — it only decides where each pixel reads from.
 *
 * Three tessellations, all driven by the same Symmetry count:
 * <ul>
 * <li>Wedge — the classic tube. N mirrored wedges around a center.
 * <li>Tile — a mirrored grid, N copies of the frame across. Wallpaper.
 * <li>Spiral — the wedge fold in log-polar space, so the seams wind inward.
 * </ul>
 *
 * Both sampling axes have a manual Phase and a bipolar Speed. What the axes mean
 * follows the tessellation — angle/radius for Wedge, u/v for Tile, angle/zoom
 * for Spiral — but in every case they slide the source texture underneath a
 * fixed set of mirrors, which is the motion a real kaleidoscope makes when you
 * turn the object cell rather than the tube.
 *
 * Sampling is off a snapshot taken before anything is written, and that is not
 * an optimization — it is the whole correctness argument for an effect that
 * reads its own output. Writing in point order into the live buffer would have
 * later points folding the already-folded results of earlier ones, so the
 * symmetry would compound along whatever arbitrary order the model enumerates
 * in. The nearest-point lookup that makes "the color at (u,v)" meaningful on a
 * model that is not a grid is the same one NoiseRippleDistortionEffect builds.
 *
 * Two deliberate differences from the script, both because Java's parameters can
 * say things the script API cannot. Symmetry is a real minimum-1 discrete
 * parameter rather than a 0-based one standing in for it, so the displayed
 * number is the fold count with no dead slot. Tessellation is a named option
 * list rather than 0/1/2.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Kaleidoscope Postprocess")
@LXComponent.Description("Fold the input through a mirror group")
public class KaleidoscopePostprocessEffect extends LXEffect {

  /**
   * Resolution of the reusable XY-to-model-point lookup, and the bin grid used
   * to build it. Matched to NoiseRippleDistortionEffect, which resamples the
   * same way.
   */
  private static final int LOOKUP_SIZE = 128;
  private static final int BIN_COUNT = 32;

  private static final double TAU = Math.PI * 2;

  /**
   * Radial repeat of the Spiral fold: one band is e^1.6, about a 5x zoom, and
   * the twist turns the seams by one full wedge across that band.
   */
  private static final double LOG_PERIOD = 1.6;
  private static final double SPIRAL_TWIST = 1.;
  /**
   * Log-radius runs to negative infinity at the center, so the twist would wind
   * and the bands cycle infinitely fast in the last points before the middle.
   * Softening the log by a fraction of the frame makes it linear near zero
   * instead, which bounds that rate. .04 measured out as the floor of the
   * artifact: below it the divergence returns, above it the whole spiral
   * compresses and the seams get steep again.
   *
   * It does not remove the center singularity, and nothing can: a spiral's
   * sample radius never reaches zero, so the middle point maps to a whole ring
   * and has no single angle. That is inherent to a log-polar fold. It costs the
   * couple of LEDs at dead center, and only in this mode.
   */
  private static final double SPIRAL_SOFTEN = .04;

  private static final double MAX_RATE = .35;
  private static final double MAX_ZOOM = 4;

  /**
   * Phases wrap at 2, not 1, because every axis that lands in reflectRange is
   * mirrored: it runs out and back, so its period is two spans and wrapping at
   * one would flip the mirror parity and pop. The angular axes only need 1 (a
   * turn of TAU is a turn of nothing), and 2 is a whole number of those too, so
   * one period keeps every consumer seamless. Nothing here needs a periodic
   * reset for floating point — the accumulator stays inside [0,2) forever, and
   * each fold is exactly invariant under a step of 2, so the wrap is silent
   * rather than merely rare.
   */
  private static final double PHASE_PERIOD = 2;

  private static final int WEDGE = 0;
  private static final int TILE = 1;
  private static final int SPIRAL = 2;

  private static final String[] TESSELLATIONS = { "Wedge", "Tile", "Spiral" };

  /**
   * A true 1-based range, which the script could not express: knobi builds a
   * 0-based parameter, so the script spends its 0 slot reading as 1.
   */
  public final DiscreteParameter symmetry =
    new DiscreteParameter("Symmetry", 1, 1, 8)
    .setDescription("Mirror folds around the center");

  public final DiscreteParameter tess =
    new DiscreteParameter("Tess", TESSELLATIONS)
    .setDescription("Tessellation: wedge, tile or spiral");

  public final CompoundParameter zoom =
    new CompoundParameter("Zoom", .5, 0, 1)
    .setDescription("Scale of the source read, 0.25x to 4x");

  public final CompoundParameter roll =
    new CompoundParameter("Roll", 0, 0, 1)
    .setDescription("Rotate the mirrors themselves, sweeping the seams");

  public final CompoundParameter phaseX =
    new CompoundParameter("Phase X", 0, 0, 1)
    .setDescription("Manual offset on the first sampling axis");

  public final CompoundParameter speedX =
    new CompoundParameter("Speed X", .5, 0, 1)
    .setDescription("Drift rate of the first axis, 0.5 is stopped");

  public final CompoundParameter phaseY =
    new CompoundParameter("Phase Y", 0, 0, 1)
    .setDescription("Manual offset on the second sampling axis");

  public final CompoundParameter speedY =
    new CompoundParameter("Speed Y", .5, 0, 1)
    .setDescription("Drift rate of the second axis, 0.5 is stopped");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Correct for a non-square model");

  private double driftX = 0;
  private double driftY = 0;
  private double shiftX = 0;
  private double shiftY = 0;

  private double aspectX = 1;
  private double sceneR = 1;
  private int folds = 1;
  private double wedgeWidth = TAU;
  private double rollCos = 1;
  private double rollSin = 0;
  private double srcScale = 1;

  /**
   * Where the fold methods leave their result. Fields rather than a returned
   * pair because this runs per point per frame; the script needed the same
   * trick to keep Nashorn from allocating one object per point.
   */
  private double foldX = 0;
  private double foldY = 0;

  private int[] sourceColors = null;
  private LXModel indexedModel = null;
  private int indexedPointCount = -1;
  private final int[] binHead = new int[BIN_COUNT * BIN_COUNT];
  private int[] pointNext = null;
  private int[] sourceLookup = null;
  private int nearestBestPoint = -1;
  private double nearestBestDistanceSq = Double.POSITIVE_INFINITY;

  public KaleidoscopePostprocessEffect(LX lx) {
    super(lx);
    addParameter("symmetry", this.symmetry);
    addParameter("tess", this.tess);
    addParameter("zoom", this.zoom);
    addParameter("roll", this.roll);
    addParameter("phaseX", this.phaseX);
    addParameter("speedX", this.speedX);
    addParameter("phaseY", this.phaseY);
    addParameter("speedY", this.speedY);
    addParameter("autoAspect", this.autoAspect);
  }

  @Override
  protected void run(double deltaMs, double enabledAmount) {
    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs * .001, 0, .25) : 0;

    // Advanced before the early return below, so a disabled effect keeps its
    // place in the drift rather than jumping when it comes back.
    //
    // Phases stay inside one period, so they never lose precision to a growing
    // exponent — and since every fold is invariant across that period, the wrap
    // itself is invisible. See PHASE_PERIOD.
    this.driftX = wrap(this.driftX + dt * (this.speedX.getValue() - .5) * 2 * MAX_RATE, PHASE_PERIOD);
    this.driftY = wrap(this.driftY + dt * (this.speedY.getValue() - .5) * 2 * MAX_RATE, PHASE_PERIOD);
    this.shiftX = wrap(this.driftX + this.phaseX.getValue(), PHASE_PERIOD);
    this.shiftY = wrap(this.driftY + this.phaseY.getValue(), PHASE_PERIOD);

    this.folds = this.symmetry.getValuei();
    this.wedgeWidth = TAU / this.folds;

    // Rolling the mirrors is the same as counter-rolling every point, and the
    // angle is constant for the frame, so it resolves to one sin/cos here
    // rather than a pair per point.
    double rollAngle = -this.roll.getValue() * TAU;
    this.rollCos = Math.cos(rollAngle);
    this.rollSin = Math.sin(rollAngle);

    // Zoom is exponential so 0.5 is unity and the two halves of the knob travel
    // the same factor in each direction.
    this.srcScale = 1 / Math.pow(MAX_ZOOM, (this.zoom.getValue() - .5) * 2);

    updateFrame();

    // Keyed on the model alone: the lookup is built from raw xn/yn, so aspect
    // and every other control change without invalidating it.
    if (this.indexedModel != this.model
        || this.indexedPointCount != this.model.points.length) {
      buildSourceLookup();
    }

    if (this.sourceLookup == null || enabledAmount <= 0) {
      return;
    }

    // The snapshot. Everything below reads this and never the live buffer.
    if (this.sourceColors == null || this.sourceColors.length != this.colors.length) {
      this.sourceColors = new int[this.colors.length];
    }
    System.arraycopy(this.colors, 0, this.sourceColors, 0, this.colors.length);

    int mode = this.tess.getValuei();
    double blend = clamp(enabledAmount, 0, 1);

    for (LXPoint point : this.model.points) {
      // Centered, aspect-corrected scene coordinates. One unit is the same
      // distance on either axis, which is what keeps a wedge angular and a tile
      // square on an oblong model.
      double sx = (point.xn - .5) * this.aspectX * this.srcScale;
      double sy = (point.yn - .5) * this.srcScale;

      // Counter-rolling here, before the fold, means all three tessellations
      // pick up Roll for free.
      double rx = sx * this.rollCos - sy * this.rollSin;
      double ry = sx * this.rollSin + sy * this.rollCos;

      if (mode == TILE) {
        foldTile(rx, ry);
      } else if (mode == SPIRAL) {
        foldSpiral(rx, ry);
      } else {
        foldWedge(rx, ry);
      }

      int kaleidoColor = sampleSource(
        mirror01(this.foldX / this.aspectX + .5),
        mirror01(this.foldY + .5)
      );
      this.colors[point.index] =
        LXColor.lerp(this.sourceColors[point.index], kaleidoColor, blend);
    }
  }

  /**
   * The classic tube. Fold the angle into one wedge with a reflection, leaving
   * the radius alone, so the frame's content repeats around the center.
   */
  private void foldWedge(double x, double y) {
    double r = Math.sqrt(x * x + y * y);
    double a = reflectInto(Math.atan2(y, x), this.wedgeWidth) + this.shiftX * TAU;

    // Reflected into the frame's disc rather than pushed off it. An open-ended
    // push has no period, so the phase wrap had nowhere seamless to land and
    // the read snapped back by a full radius once a cycle. Folding gives the
    // radial axis a period of two spans, matching every other axis, and it
    // costs nothing at rest: r never exceeds sceneR at unit zoom, so this is
    // the identity until the shift actually moves.
    double rs = reflectRange(r + this.shiftY * this.sceneR, 0, this.sceneR);
    this.foldX = rs * Math.cos(a);
    this.foldY = rs * Math.sin(a);
  }

  /**
   * Mirrored grid. N copies of the frame across, every other one flipped, which
   * is what makes the copies join instead of tiling with a visible cut.
   */
  private void foldTile(double x, double y) {
    double halfW = this.aspectX * .5;
    this.foldX = reflectRange(x * this.folds + this.shiftX * this.aspectX, -halfW, halfW);
    this.foldY = reflectRange(y * this.folds + this.shiftY, -.5, .5);
  }

  /**
   * The wedge fold done in log-polar space. Coupling log-radius into the angle
   * bends the straight seams into spiral arms, and drifting log-radius reads as
   * a continuous zoom rather than a slide.
   */
  private void foldSpiral(double x, double y) {
    double r = Math.max(Math.sqrt(x * x + y * y), 1e-5);
    double lr = Math.log((r + SPIRAL_SOFTEN * this.sceneR) / this.sceneR) / LOG_PERIOD;

    double theta = Math.atan2(y, x) + lr * SPIRAL_TWIST * this.wedgeWidth;
    double a = reflectInto(theta, this.wedgeWidth) + this.shiftX * TAU;

    // Reflect into a single band of [-1,0] e-folds so the read stays inside the
    // frame's disc and reverses at the band edges instead of jumping.
    double band = reflectRange(lr + this.shiftY, -1, 0);
    double rs = this.sceneR * Math.exp(band * LOG_PERIOD);

    this.foldX = rs * Math.cos(a);
    this.foldY = rs * Math.sin(a);
  }

  /**
   * Fold a value into [0, period/2] by reflecting every other period. This is
   * the mirror itself: N of these around a circle give N wedges, each holding a
   * reflected pair, for 2N segments in the round.
   */
  private static double reflectInto(double value, double period) {
    double m = wrap(value, period);
    return (m > period * .5) ? period - m : m;
  }

  /** Reflect a value into [min, max], continuous at both ends. */
  private static double reflectRange(double value, double min, double max) {
    double span = max - min;
    return min + mirror01((value - min) / span) * span;
  }

  /** The frame the fold works against, in aspect-corrected scene units. */
  private void updateFrame() {
    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      // Widened before dividing, not after: xRange and yRange are floats, so
      // the bare quotient would round to float precision and only then become a
      // double. Everything downstream of aspect is double, and the script does
      // this division in double too.
      this.aspectX = (double) this.model.xRange / (double) this.model.yRange;
    }
    // Half-diagonal: the radius that still reaches the frame's corners, so a
    // full-radius read covers everything rather than stopping at the short edge.
    this.sceneR = Math.sqrt(this.aspectX * this.aspectX + 1) * .5;
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

  private static double mirror01(double value) {
    double mirrored = wrap(value, 2);
    return (mirrored <= 1) ? mirrored : 2 - mirrored;
  }

  private static double wrap(double value, double period) {
    return value - Math.floor(value / period) * period;
  }

  private static double clamp(double v, double low, double high) {
    return (v < low) ? low : (v > high) ? high : v;
  }
}
