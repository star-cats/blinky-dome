package com.starcats.blinkydome;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

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
 * A Voronoi tessellation whose cells drift, and whose boundaries morph between
 * straight-edged polygons and rounded bubbles. Ported from Scripts/Voronoi.js.
 *
 * Both looks come out of one field rather than two renderers. Every LED gets a
 * cell coordinate q that is 0 at its site and 1 on the cell boundary, and
 * there are two natural ways to build one:
 *
 *   polygonal   the factor by which the cell must be scaled about its site to
 *               land on this LED, so level sets are scaled copies of the cell
 *               and every edge of every one of them is straight
 *   bubbled     F1 / F2, the ratio of the distances to the nearest and second
 *               nearest sites, whose level sets are Apollonian circles
 *
 * The important part is that both are exactly 1 on the same set: the true
 * Voronoi skeleton. So Shape can crossfade between them freely. The boundary
 * never moves or blurs as it morphs, only the shape of the level sets inside
 * it changes, and cells go from hard-edged polygons to round bubbles without
 * ever passing through a stage where the tessellation looks broken. The morph
 * holds at every Edge setting, not just next to the boundary.
 *
 * The polygonal side is worth stating exactly, because the obvious version of
 * it is wrong. Normalizing the true distance-to-boundary as F1 / (F1 + dB)
 * also runs 0 to 1 and is also exactly 1 on the skeleton, but its level sets
 * are parabolas — it is the focus-and-directrix construction — so cells only
 * look straight-edged very close to the boundary and bulge everywhere else.
 * Scaling the cell's own half-planes instead is both correct and cheaper: the
 * scale factor against one rival falls out of a dot product, with no square
 * root anywhere in the loop.
 *
 * The sites live on a torus and are rebinned by where they actually are, every
 * frame, rather than being looked up in the grid cell they were born in. That
 * one decision is what lets Intensity be large. A site tied to its home cell
 * has to stay inside it for a fixed 3x3 search to find it, which caps the
 * wander at half a cell; binning by current position instead decouples how far
 * a site may travel from how much it costs to find, so sites can cross several
 * cells, clump and leave voids while the search stays local. The rebin is a
 * counting sort over a few hundred sites — trivial next to a frame of LEDs.
 *
 * It also makes the search exactly correct instead of merely reliable, which
 * the home-cell version was not. A site outside the searched block of radius R
 * is more than R cells away, by construction: the LED is somewhere inside its
 * own bin and the site is beyond the block's edge. So if the second nearest
 * site found is within R, no unsearched site can beat it and the answer is
 * provably the true one. When that test fails — a sparse patch, a wide void —
 * the radius grows and the search repeats. One pass at R=1 almost always
 * suffices, because bins average one site each.
 *
 * Resting positions scatter well past their own cell, so the arrangement reads
 * as organic even when nothing is moving. The underlying grid only guarantees
 * roughly even coverage; it is not visible as a lattice.
 *
 * Motion is Perlin-driven, not orbital. Each site reads its offset from a
 * private path through the seamless noise texture, so sites wander, hesitate
 * and double back independently instead of all circling in lockstep. Sites are
 * computed once per frame, not per LED — a couple of hundred texture samples a
 * frame — which is what keeps the noise essentially free.
 *
 * Output is pure luminosity, as specified: one 0-1 whiteness per LED.
 *
 * The one deliberate difference from the script is how the texture is found.
 * The script walks up from the JVM's working directory guessing at where the
 * media folder might be, because a script has no handle on Chromatik itself. A
 * pattern does: lx.getMediaFile resolves against the real media root, whatever
 * it was set to.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Voronoi")
@LXComponent.Description("Drifting Voronoi cells that morph from polygons to bubbles")
public class VoronoiPattern extends LXPattern {

  private static final String DRIFT_PATH = "Images/Noise/Perlin_14-128x128.png";

  private static final int MIN_CELLS = 2;
  private static final int MAX_CELLS = 16;

  /**
   * Scatter of a site's resting position from its grid slot, in cells.
   *
   * Comfortably past half a cell, so resting sites cross into their neighbors'
   * territory and the arrangement has real clumps and voids in it rather than
   * reading as a jittered lattice. The grid is only there to keep coverage
   * roughly even across the torus.
   */
  private static final double REST_SPREAD = .85;

  /** How far a site can wander from rest at full Intensity, in cells. */
  private static final double WANDER_MAX = 2.5;

  /**
   * Ceiling on the search radius, in bins.
   *
   * The radius grows a ring at a time until the answer is provably settled;
   * this only exists so a pathological frame cannot spiral. It is generous
   * because overrunning it costs one LED a bigger scan, while hitting it too
   * early would put a wrong cell on the wall.
   */
  private static final int MAX_SEARCH = 6;

  /** Rivals kept per LED. More than the densest neighborhood can produce. */
  private static final int MAX_CANDIDATES = 256;

  public final CompoundParameter cells =
    new CompoundParameter("Cells", .4, 0, 1)
    .setDescription("How many cells across the pattern, 2 to 16");

  public final CompoundParameter shape =
    new CompoundParameter("Shape", .5, 0, 1)
    .setDescription("Boundary character: 0 is sharp and linear, 1 is smooth bubbles");

  public final CompoundParameter edge =
    new CompoundParameter("Edge", .72, 0, 1)
    .setDescription("Where the boundary sits within the cell");

  public final CompoundParameter soft =
    new CompoundParameter("Soft", .3, 0, 1)
    .setDescription("Softness of the boundary; low is a hard edge");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .4, 0, 1)
    .setDescription("How fast the sites wander");

  public final CompoundParameter intensity =
    new CompoundParameter("Intensity", .6, 0, 1)
    .setDescription("How far the sites wander; at full they cross several cells");

  public final CompoundParameter zoom =
    new CompoundParameter("Zoom", .5, 0, 1)
    .setDescription("Scale of the scene; center is 1x, ends are 1/4x and 4x");

  public final CompoundParameter rot =
    new CompoundParameter("Rotate", 0, 0, 1)
    .setDescription("Rotation of the scene, a full turn across the knob");

  public final CompoundParameter panx =
    new CompoundParameter("Pan X", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Horizontal translation; the scene wraps, so this runs forever");

  public final CompoundParameter pany =
    new CompoundParameter("Pan Y", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Vertical translation; the scene wraps, so this runs forever");

  public final CompoundParameter level =
    new CompoundParameter("Level", 1, 0, 1)
    .setDescription("Overall brightness");

  public final BooleanParameter seams =
    new BooleanParameter("Seams", false)
    .setDescription("Light the boundaries instead of the cell interiors");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Keep the cells round on a non-square model");

  /** The seamless noise texture, with the extremes signedNoise centers on. */
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

  private Texture driftImage = null;
  private boolean loadAttempted = false;

  /** Site positions on the torus, in cells, wrapped into 0..gridG. */
  private final double[] siteGX = new double[MAX_CELLS * MAX_CELLS];
  private final double[] siteGY = new double[MAX_CELLS * MAX_CELLS];

  // Sites sorted into bins by where they are now: binSites holds site indices
  // grouped by bin, and binStart[b]..binStart[b+1] is bin b's run within it.
  // Rebuilt every frame by counting sort, which needs no per-frame allocation.
  private final int[] siteBin = new int[MAX_CELLS * MAX_CELLS];
  private final int[] binStart = new int[MAX_CELLS * MAX_CELLS + 1];
  private final int[] binCursor = new int[MAX_CELLS * MAX_CELLS];
  private final int[] binSites = new int[MAX_CELLS * MAX_CELLS];

  // Per-frame values, resolved once per frame rather than per LED.
  private int gridG = 4;
  private double drift = 0;
  private double cosT = 1;
  private double sinT = 0;
  private double invZoom = 1;
  private double panWorldX = 0;
  private double panWorldY = 0;
  private double aspectX = 1;
  private double edgeAt = .72;
  private double softness = .2;

  // Scratch for the neighborhood search. The rival pass needs every candidate's
  // offset vector, and allocating an array per LED would be a few hundred
  // thousand short-lived arrays a second.
  private final double[] candX = new double[MAX_CANDIDATES];
  private final double[] candY = new double[MAX_CANDIDATES];

  public VoronoiPattern(LX lx) {
    super(lx);
    addParameter("cells", this.cells);
    addParameter("shape", this.shape);
    addParameter("edge", this.edge);
    addParameter("soft", this.soft);
    addParameter("speed", this.speed);
    addParameter("intensity", this.intensity);
    addParameter("zoom", this.zoom);
    addParameter("rot", this.rot);
    addParameter("panx", this.panx);
    addParameter("pany", this.pany);
    addParameter("level", this.level);
    addParameter("seams", this.seams);
    addParameter("autoAspect", this.autoAspect);
  }

  @Override
  protected void run(double deltaMs) {
    if (!this.loadAttempted) {
      this.loadAttempted = true;
      this.driftImage = loadTexture();
    }
    if (this.driftImage == null) {
      setColors(LXColor.hsb(0, 0, 0));
      return;
    }

    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs * .001, 0, .25) : 0;
    // Integrated rather than read off wall time, so changing Speed bends the
    // sites' paths from where they are instead of teleporting them.
    this.drift = wrap01(this.drift + dt * lerp(0, .3, this.speed.getValue()));

    this.gridG = clampInt(
      (int) Math.round(lerp(MIN_CELLS, MAX_CELLS, this.cells.getValue())),
      MIN_CELLS, MAX_CELLS);
    placeSites();

    double angle = this.rot.getValue() * Math.PI * 2;
    this.cosT = Math.cos(angle);
    this.sinT = Math.sin(angle);
    this.invZoom = 1 / Math.pow(2, (this.zoom.getValue() - .5) * 4);
    this.panWorldX = (this.panx.getValue() - .5) * 2;
    this.panWorldY = (this.pany.getValue() - .5) * 2;

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    // Kept just off 0 and 1 so a cell always has an interior and a boundary,
    // rather than collapsing to all-lit or all-dark at the ends of the knob.
    this.edgeAt = lerp(.06, .97, this.edge.getValue());
    this.softness = Math.max(lerp(.004, .55, this.soft.getValue()), 1e-4);

    draw();
  }

  /**
   * Walks every site to where it is this frame, then bins it by where it landed.
   *
   * Resting spots come from a hash, so they are irregular and hold still at
   * zero Intensity — an unjittered grid would give a degenerate diagram of
   * identical squares. The wander is added on top, read from the noise texture
   * along a path unique to each site, and is free to carry the site clear of
   * its grid slot.
   */
  private void placeSites() {
    double wander = this.intensity.getValue() * WANDER_MAX;
    int bins = this.gridG * this.gridG;

    for (int b = 0; b <= bins; ++b) {
      this.binStart[b] = 0;
    }

    for (int j = 0; j < this.gridG; ++j) {
      for (int i = 0; i < this.gridG; ++i) {
        int s = j * this.gridG + i;
        double restX = i + .5 + (hash01(i, j, 0) - .5) * 2 * REST_SPREAD;
        double restY = j + .5 + (hash01(i, j, 1) - .5) * 2 * REST_SPREAD;

        // Each site reads a different region of the texture and travels through
        // it on its own line, which decorrelates neighbors that would otherwise
        // sweep together — the noise is smooth, so nearby samples move alike.
        double u = hash01(i, j, 2);
        double v = hash01(i, j, 3);
        double gxs = wrapTorus(restX + signedNoise(u + this.drift, v) * wander);
        double gys = wrapTorus(restY + signedNoise(u + this.drift + .37, v + .5) * wander);

        this.siteGX[s] = gxs;
        this.siteGY[s] = gys;

        int bin = clampInt((int) Math.floor(gys), 0, this.gridG - 1) * this.gridG +
          clampInt((int) Math.floor(gxs), 0, this.gridG - 1);
        this.siteBin[s] = bin;
        // Counted one slot high, so the prefix sum below lands on bin starts.
        this.binStart[bin + 1] += 1;
      }
    }

    for (int k = 1; k <= bins; ++k) {
      this.binStart[k] += this.binStart[k - 1];
    }
    for (int c = 0; c < bins; ++c) {
      this.binCursor[c] = this.binStart[c];
    }
    for (int t = 0; t < bins; ++t) {
      this.binSites[this.binCursor[this.siteBin[t]]++] = t;
    }
  }

  /** Wraps a coordinate in cells onto the torus. */
  private double wrapTorus(double value) {
    return value - Math.floor(value / this.gridG) * this.gridG;
  }

  private void draw() {
    final int black = LXColor.hsb(0, 0, 0);
    final double shapeAmount = this.shape.getValue();
    final double lvl = this.level.getValue();
    final boolean seamsOn = this.seams.isOn();

    for (LXPoint point : this.model.points) {
      double sx = (point.xn - .5) * this.aspectX;
      double sy = (point.yn - .5);

      // Screen back to the torus: unrotate, unzoom, translate, then wrap.
      double wx = wrap01((this.cosT * sx + this.sinT * sy) * this.invZoom + this.panWorldX + .5);
      double wy = wrap01((-this.sinT * sx + this.cosT * sy) * this.invZoom + this.panWorldY + .5);

      // Everything below is in cells, so distances stay comparable as Cells
      // moves.
      double gx = wx * this.gridG;
      double gy = wy * this.gridG;
      int ci = clampInt((int) Math.floor(gx), 0, this.gridG - 1);
      int cj = clampInt((int) Math.floor(gy), 0, this.gridG - 1);

      // The search state is deliberately local. It is read and written a few
      // dozen times per LED, and hoisting it out so the ring scan could live in
      // its own function measured about three times slower in the script — the
      // scan is inlined below for the same reason.
      int count = 0;
      double f1sq = 1e18;
      double f2sq = 1e18;
      double r1x = 0;
      double r1y = 0;
      int nearest = -1;
      double rivalMax = 0;
      int rivalDone = 0;

      // Where the LED sits inside its own bin, which is what makes the block's
      // reach asymmetric and worth measuring rather than assuming.
      double lx = gx - ci;
      double ly = gy - cj;

      // Grow the block a ring at a time until the answer is settled. Two things
      // have to be out of reach of the sites nobody looked at:
      //
      //   the runner-up  nothing unseen within f2 of the LED, or the two
      //                  nearest sites are not the two nearest
      //   the rivals     a rival binds the cell only if it takes the boundary
      //                  in from where the seen rivals put it. Along the ray
      //                  from the site through the LED, that boundary is at y,
      //                  and a site pulls it closer exactly when it is nearer
      //                  to y than the site is. So the cell shape is settled
      //                  once no unseen site can be within f1/q of y.
      //
      // Both reduce to asking whether a disc is inside the searched block, and
      // the block is a square: testing against its four sides rather than its
      // inscribed circle is what keeps the radius at 2 instead of 3 or 4. The
      // rival test is the binding one, and it is not implied by the runner-up
      // test — a distant site can bound a cell long after the two nearest are
      // known. Skipping it leaves the far side of the block deciding cell
      // shape, which shows up as a discontinuity at the wrap.
      double polygonal = 0;
      double f1 = 0;
      for (int radius = 0; radius <= MAX_SEARCH; ++radius) {
        // One ring of bins, never a whole block: an expansion only pays for
        // what it has not already seen. Bins along a row are consecutive, so
        // the wrap is a compare-and-subtract carried along rather than a modulo
        // per bin.
        int rawJ = cj - radius;
        int binJ = wrapIndex(rawJ, this.gridG);

        for (int dj = -radius; dj <= radius; ++dj) {
          int offY = rawJ - binJ;
          int rowBase = binJ * this.gridG;
          boolean edgeRow = (dj == -radius || dj == radius);
          int rawI = ci - radius;
          int binI = wrapIndex(rawI, this.gridG);
          // Interior rows contribute only the ring's two ends; edge rows are
          // solid.
          int span = edgeRow ? radius + radius : 1;

          for (int di = 0; di <= span; ++di) {
            int bin = rowBase + binI;
            // Whole tiles of the torus, so a site read from a wrapped bin sits
            // at the unwrapped coordinate the LED actually sees it at.
            int offX = rawI - binI;

            for (int k = this.binStart[bin]; k < this.binStart[bin + 1]; ++k) {
              int s = this.binSites[k];
              double rx = this.siteGX[s] + offX - gx;
              double ry = this.siteGY[s] + offY - gy;
              if (count < MAX_CANDIDATES) {
                this.candX[count] = rx;
                this.candY[count] = ry;
              }

              double dsq = rx * rx + ry * ry;
              if (dsq < f1sq) {
                f2sq = f1sq;
                f1sq = dsq;
                r1x = rx;
                r1y = ry;
                nearest = (count < MAX_CANDIDATES) ? count : -1;
                // Every scale was measured against the old winner, so they all
                // go.
                rivalMax = 0;
                rivalDone = 0;
              } else if (dsq < f2sq) {
                f2sq = dsq;
              }
              ++count;
            }

            int stride = edgeRow ? 1 : radius + radius;
            rawI += stride;
            binI += stride;
            while (binI >= this.gridG) {
              binI -= this.gridG;
            }
          }

          ++rawJ;
          if (++binJ >= this.gridG) {
            binJ -= this.gridG;
          }
        }

        if (count < 2) {
          continue;
        }
        f1 = Math.sqrt(f1sq);
        int rivals = Math.min(count, MAX_CANDIDATES);
        polygonal = rivalScale(r1x, r1y, nearest, rivalDone, rivals, rivalMax);
        rivalMax = polygonal;
        rivalDone = rivals;

        if (!discInsideBlock(0, 0, Math.sqrt(f2sq), radius, lx, ly)) {
          continue;
        }
        if (f1 <= 0) {
          break;
        }
        // A scale of zero is not a settled answer, it is no answer: every rival
        // seen so far lies behind the site, so nothing bounds the cell yet.
        if (polygonal <= 0) {
          continue;
        }
        double reach = 1 / polygonal;
        if (discInsideBlock(
            r1x * (1 - reach), r1y * (1 - reach), f1 * reach, radius, lx, ly)) {
          break;
        }
      }

      if (count == 0) {
        this.colors[point.index] = black;
        continue;
      }

      // Two coordinates for the same cell, both 0 at the site and 1 on the
      // boundary: straight level sets, and circular ones. Shape crossfades them.
      double f2 = Math.sqrt(f2sq);
      double bubbled = (f2 > 1e-9) ? f1 / f2 : 0;
      double q = polygonal + (bubbled - polygonal) * shapeAmount;

      double value = clamp((this.edgeAt - q) / this.softness, 0, 1);
      if (seamsOn) {
        value = 1 - value;
      }

      this.colors[point.index] = LXColor.hsb(0, 0, value * lvl * 100);
    }
  }

  /**
   * Noise sample rescaled to a symmetric -1..1.
   *
   * The texture does not use the full 0-1 range — Perlin_14 spans about 0.19 to
   * 0.59 — so reading it as if it did would spend under half the wander budget
   * and, because that span sits below 0.5, would bias every site's drift toward
   * one corner. Centering on the texture's own midpoint fixes both.
   */
  private double signedNoise(double u, double v) {
    double g = (sampleGray(this.driftImage, u, v) - this.driftImage.center)
      / this.driftImage.halfSpan;
    return clamp(g, -1, 1);
  }

  /**
   * Is a disc, given relative to the LED, wholly inside the searched block?
   *
   * The block of radius R around bin (ci,cj) reaches R bins out plus whatever
   * is left of the LED's own bin, which is why the LED's position within it
   * matters.
   */
  private static boolean discInsideBlock(double cx, double cy, double r, int radius,
      double lx, double ly) {
    return cx - r >= -radius - lx &&
      cx + r <= radius + 1 - lx &&
      cy - r >= -radius - ly &&
      cy + r <= radius + 1 - ly;
  }

  /**
   * How far the cell has to be scaled about its site to reach this LED.
   *
   * Each rival contributes one half-plane, and the scale that puts the LED on
   * that half-plane is a ratio of dot products; the binding one is the largest.
   * The nearest site is skipped — it is the winner, not a rival.
   *
   * Candidates already folded in are not revisited, so a search that expands
   * three times still reads each candidate once. Finding a nearer site resets
   * the range, since every scale is measured against the current winner.
   */
  private double rivalScale(double r1x, double r1y, int nearest, int from, int to,
      double best) {
    for (int r = from; r < to; ++r) {
      if (r == nearest) {
        continue;
      }
      double ex = this.candX[r] - r1x;
      double ey = this.candY[r] - r1y;
      double spanSq = ex * ex + ey * ey;
      if (spanSq < 1e-12) {
        continue;
      }
      double scale = -2 * (r1x * ex + r1y * ey) / spanSq;
      if (scale > best) {
        best = scale;
      }
    }
    return best;
  }

  /** Deterministic, decorrelated 0-1 value for an integer cell and a salt. */
  private static double hash01(int i, int j, int salt) {
    double s = Math.sin(i * 127.1 + j * 311.7 + salt * 74.7) * 43758.5453;
    return s - Math.floor(s);
  }

  /** Bilinear grayscale sampling with seamless wrapping on both axes. */
  private static double sampleGray(Texture image, double u, double v) {
    u = wrap01(u);
    v = wrap01(v);
    double x = u * image.w;
    double y = v * image.h;
    int x0 = (int) Math.floor(x) % image.w;
    int y0 = (int) Math.floor(y) % image.h;
    int x1 = (x0 + 1) % image.w;
    int y1 = (y0 + 1) % image.h;
    double tx = x - Math.floor(x);
    double ty = y - Math.floor(y);
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
   * Loads the noise texture out of the media folder.
   *
   * A missing or unreadable file leaves the pattern black rather than throwing:
   * a pattern that cannot draw is a dark channel, and a pattern that throws
   * every frame is a wall of log spam.
   */
  private Texture loadTexture() {
    File file = this.lx.getMediaFile(DRIFT_PATH);
    BufferedImage buffered;
    try {
      buffered = ImageIO.read(file);
    } catch (Exception x) {
      LX.error(x, "Voronoi: could not read " + file);
      return null;
    }
    if (buffered == null) {
      LX.error("Voronoi: could not read " + file);
      return null;
    }
    int w = buffered.getWidth();
    int h = buffered.getHeight();
    int[] px = buffered.getRGB(0, 0, w, h, new int[w * h], 0, w);

    // The texture's own extremes, so signedNoise can center and scale on them
    // instead of assuming the file happens to span the full 0-1 range.
    double low = 1;
    double high = 0;
    for (int i = 0; i < w * h; ++i) {
      double g = grayOf(px[i]);
      if (g < low) {
        low = g;
      }
      if (g > high) {
        high = g;
      }
    }

    return new Texture(w, h, px, (low + high) * .5, Math.max((high - low) * .5, 1e-6));
  }

  private static int wrapIndex(int value, int n) {
    return ((value % n) + n) % n;
  }

  private static double wrap01(double value) {
    return value - Math.floor(value);
  }

  private static int clampInt(int value, int low, int high) {
    return (value < low) ? low : (value > high) ? high : value;
  }

  private static double clamp(double v, double low, double high) {
    return (v < low) ? low : (v > high) ? high : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
