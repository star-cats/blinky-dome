package com.starcats.blinkydome;

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
 * A Hilbert curve you can fly over, with light running along it. Ported from
 * Scripts/HilbertCurve.js.
 *
 * The curve is a space-filling path through a 2^order x 2^order grid: it visits
 * every cell exactly once, entering and leaving through edge midpoints and
 * turning at cell centers. That makes it two useful things at once — a picture,
 * and a one-dimensional coordinate. Every point of the plane is near some cell,
 * every cell has an arc position along the path, so any LED near the drawn line
 * knows how far along the curve it sits. The traveling ramp is written in that
 * coordinate, which is why it follows the folds instead of sweeping across them.
 *
 * Rendering is a distance field, not a stroke. Each LED maps back through pan,
 * rotate and zoom into curve space, lands in a cell, and measures its distance
 * to the two half-segments that cell contributes (center to entry midpoint,
 * center to exit midpoint). A point near a cell edge can be closer to the
 * neighbor's segments, so the neighbor on that side is checked too — the
 * diagonal never wins, which keeps the inner loop at one to three cells.
 *
 * The curve is tabulated once per order and cached, so the per-LED work is a
 * lookup, a couple of clamped projections and a wrap. Sweeping the Order knob
 * costs nothing after the first visit to each order.
 *
 * Arc position is true arc length: cells are unit-spaced along the path, so a
 * constant Speed is constant travel, which is what makes the ramp read as
 * motion rather than as a slew. The ramp itself is a sawtooth in arc length —
 * climbing to white over Width, then a hard reset — repeated along the whole
 * curve. At Width 1 there is exactly one ramp wrapped over the entire path; at
 * small Width it is a train of them.
 *
 * The ramp is a gradient in color, not just in level. Whiteness runs 0 at the
 * tail to 1 at the leading edge, and drives saturation down as it drives
 * brightness up, so a trail goes from the tint at full saturation through to
 * white at its head rather than being one color repeatedly dimmed. The only
 * thing that is purely a brightness falloff is the band's soft edge, which
 * works across the line's thickness and is left alone by all of this.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Hilbert Curve")
@LXComponent.Description("A Hilbert curve with light running along it")
public class HilbertCurvePattern extends LXPattern {

  private static final int MAX_ORDER = 7;

  public final CompoundParameter order =
    new CompoundParameter("Order", .45, 0, 1)
    .setDescription("Curve order: 1 is a 2x2 grid, 7 is 128x128");

  public final CompoundParameter zoom =
    new CompoundParameter("Zoom", .5, 0, 1)
    .setDescription("Scale of the curve; center is 1x, ends are 1/8x and 8x");

  public final CompoundParameter rot =
    new CompoundParameter("Rotate", 0, 0, 1)
    .setDescription("Rotation of the curve, a full turn across the knob");

  public final CompoundParameter panx =
    new CompoundParameter("Pan X", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Horizontal position within the curve; center is centered");

  public final CompoundParameter pany =
    new CompoundParameter("Pan Y", .5, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Vertical position within the curve; center is centered");

  public final CompoundParameter band =
    new CompoundParameter("Band", .28, 0, 1)
    .setDescription("Thickness of the drawn line, in cells");

  public final CompoundParameter soft =
    new CompoundParameter("Soft", .2, 0, 1)
    .setDescription("Edge softness of the line — this is the anti-aliasing");

  public final CompoundParameter width =
    new CompoundParameter("Width", .25, 0, 1)
    .setDescription("Length of one black-to-white ramp, as a fraction of the curve");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .62, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Travel along the curve; 0.5 is still, below it runs backward");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", .55, 0, 1)
    .setDescription("Tint hue");

  public final CompoundParameter sat =
    new CompoundParameter("Saturation", .8, 0, 1)
    .setDescription("Saturation at the tail; the leading edge is always white");

  public final CompoundParameter level =
    new CompoundParameter("Level", 1, 0, 1)
    .setDescription("Overall brightness");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Keep the curve square on a non-square model");

  /** One order's tabulated curve. Built on first use and kept. */
  private static final class Table {
    final int n;
    final int count;
    final int[] cellArc;
    final int[] nextX;
    final int[] nextY;
    final int[] prevX;
    final int[] prevY;

    Table(int n, int count) {
      this.n = n;
      this.count = count;
      this.cellArc = new int[count];
      this.nextX = new int[count];
      this.nextY = new int[count];
      this.prevX = new int[count];
      this.prevY = new int[count];
    }
  }

  /** Curve tables, one per order, built on first use and kept. */
  private final Table[] tables = new Table[MAX_ORDER + 1];

  // Per-frame values, resolved once per frame rather than per LED.
  private Table curve = null;
  private int gridN = 1;
  private int cellSpan = 1;
  private double phase = 0;
  private double cosT = 1;
  private double sinT = 0;
  private double invZoom = 1;
  private double panWorldX = 0;
  private double panWorldY = 0;
  private double aspectX = 1;
  private double bandRadius = .25;
  private double softness = .05;
  private double rampWidth = .25;

  // Running best of the per-cell distance query. Returning a pair from
  // considerCell would allocate once per LED per cell tested.
  private double bestDist2 = 0;
  private double bestArc = 0;

  public HilbertCurvePattern(LX lx) {
    super(lx);
    addParameter("order", this.order);
    addParameter("zoom", this.zoom);
    addParameter("rot", this.rot);
    addParameter("panx", this.panx);
    addParameter("pany", this.pany);
    addParameter("band", this.band);
    addParameter("soft", this.soft);
    addParameter("width", this.width);
    addParameter("speed", this.speed);
    addParameter("hue", this.hue);
    addParameter("sat", this.sat);
    addParameter("level", this.level);
    addParameter("autoAspect", this.autoAspect);
  }

  @Override
  protected void run(double deltaMs) {
    int requested = clampInt(1 + (int) Math.floor(this.order.getValue() * MAX_ORDER), 1, MAX_ORDER);
    this.curve = table(requested);
    this.gridN = this.curve.n;
    this.cellSpan = this.curve.count - 1;

    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs * .001, 0, .25) : 0;
    // Phase is integrated rather than derived from wall time, so turning Speed
    // changes where the ramp goes next instead of teleporting it.
    this.phase = wrap01(this.phase + dt * (this.speed.getValue() - .5) * 2 * .9);

    double angle = this.rot.getValue() * Math.PI * 2;
    this.cosT = Math.cos(angle);
    this.sinT = Math.sin(angle);

    this.invZoom = 1 / Math.pow(2, (this.zoom.getValue() - .5) * 6);

    // Pan is in curve space, so its axes stay locked to the curve rather than
    // to the screen: Pan X always walks the same way through the pattern,
    // whatever the rotation is. Full deflection clears the frame at 1x zoom.
    this.panWorldX = (this.panx.getValue() - .5) * 2;
    this.panWorldY = (this.pany.getValue() - .5) * 2;

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    // Widths are in cells, so they hold their look relative to the curve as the
    // order changes, and thicken on screen as you zoom in.
    this.bandRadius = lerp(.03, .5, this.band.getValue());
    this.softness = Math.max(lerp(.004, .35, this.soft.getValue()), 1e-4);
    this.rampWidth = Math.max(lerp(.02, 1, this.width.getValue()), 1e-4);

    draw();
  }

  private void draw() {
    final int black = LXColor.rgb(0, 0, 0);
    final double hueDeg = this.hue.getValue() * 360;
    final double satAmount = this.sat.getValue();
    final double lvl = this.level.getValue();
    final double reach = .5 - this.bandRadius;

    for (LXPoint p : this.model.points) {
      double sx = (p.xn - .5) * this.aspectX;
      double sy = (p.yn - .5);

      // Screen back to curve space: unrotate, unzoom, then offset by the pan.
      double rx = (this.cosT * sx + this.sinT * sy) * this.invZoom + this.panWorldX;
      double ry = (-this.sinT * sx + this.cosT * sy) * this.invZoom + this.panWorldY;

      double u = rx + .5;
      double v = ry + .5;
      if (u < 0 || u >= 1 || v < 0 || v >= 1) {
        this.colors[p.index] = black;
        continue;
      }

      double gx = u * this.gridN;
      double gy = v * this.gridN;
      int ix = clampInt((int) Math.floor(gx), 0, this.gridN - 1);
      int iy = clampInt((int) Math.floor(gy), 0, this.gridN - 1);

      // Local coordinates about the cell center, each in [-0.5, 0.5].
      double lx = gx - (ix + .5);
      double ly = gy - (iy + .5);

      this.bestDist2 = 1e18;
      this.bestArc = 0;
      considerCell(ix, iy, lx, ly);

      // A neighbor is only worth testing when the point is within the band of
      // the shared edge — closer than that, nothing on the far side can be in
      // range. A diagonal neighbor can never beat both edge neighbors, so it is
      // skipped.
      if (lx > reach && ix + 1 < this.gridN) {
        considerCell(ix + 1, iy, lx - 1, ly);
      } else if (lx < -reach && ix > 0) {
        considerCell(ix - 1, iy, lx + 1, ly);
      }
      if (ly > reach && iy + 1 < this.gridN) {
        considerCell(ix, iy + 1, lx, ly - 1);
      } else if (ly < -reach && iy > 0) {
        considerCell(ix, iy - 1, lx, ly + 1);
      }

      double dist = Math.sqrt(this.bestDist2);
      double edge = clamp((this.bandRadius - dist) / this.softness, 0, 1);
      if (edge <= 0) {
        this.colors[p.index] = black;
        continue;
      }

      // Arc position normalized to the whole path, then a sawtooth in it: a
      // linear climb across Width, and a hard reset. This is whiteness, 0 at
      // the tail and 1 at the leading edge, and it colors the trail as well as
      // lighting it — saturation falls as brightness rises, so the tail is the
      // tint at full saturation and the head bleaches out to white. Only
      // `edge`, the softness across the line's thickness, is brightness-only.
      double along = (this.cellSpan > 0) ? this.bestArc / this.cellSpan : 0;
      double whiteness = wrap01((along - this.phase) / this.rampWidth);

      this.colors[p.index] = LXColor.hsb(
        hueDeg,
        satAmount * (1 - whiteness) * 100,
        whiteness * edge * lvl * 100
      );
    }
  }

  /**
   * Measures the curve inside one cell against the running best in bestDist2 /
   * bestArc: squared distance to the closest point, and its arc position.
   *
   * The cell contributes two half-segments, center to entry midpoint and center
   * to exit midpoint, each half a cell long and axis-aligned. Arc position is
   * measured in cells from the center of cell zero, so the cell's center is at
   * exactly its own index and the halves run half a unit either side.
   */
  private void considerCell(int ix, int iy, double lx, double ly) {
    int d = this.curve.cellArc[iy * this.gridN + ix];
    double best = 1e18;
    double arc = d;

    if (d < this.curve.count - 1) {
      double nx = this.curve.nextX[d];
      double ny = this.curve.nextY[d];
      double tn = clamp(lx * nx + ly * ny, 0, .5);
      double ex = lx - tn * nx;
      double ey = ly - tn * ny;
      best = ex * ex + ey * ey;
      arc = d + tn;
    }

    if (d > 0) {
      double px = this.curve.prevX[d];
      double py = this.curve.prevY[d];
      double tp = clamp(lx * px + ly * py, 0, .5);
      double fx = lx - tp * px;
      double fy = ly - tp * py;
      double dist2 = fx * fx + fy * fy;
      if (dist2 < best) {
        best = dist2;
        arc = d - tp;
      }
    }

    if (best < this.bestDist2) {
      this.bestDist2 = best;
      this.bestArc = arc;
    }
  }

  /** Builds — or returns the cached — tables for a Hilbert curve of this order. */
  private Table table(int curveOrder) {
    Table cached = this.tables[curveOrder];
    if (cached != null) {
      return cached;
    }

    int n = 1 << curveOrder;
    int count = n * n;
    Table t = new Table(n, count);
    int[] cellX = new int[count];
    int[] cellY = new int[count];

    for (int d = 0; d < count; ++d) {
      int xy = hilbertPoint(n, d);
      int px = xy >> 16;
      int py = xy & 0xffff;
      cellX[d] = px;
      cellY[d] = py;
      t.cellArc[py * n + px] = d;
    }

    // Successive cells are always edge-adjacent, so each direction is a unit
    // axis vector and the half-segments stay axis-aligned.
    for (int i = 0; i < count; ++i) {
      t.nextX[i] = (i + 1 < count) ? cellX[i + 1] - cellX[i] : 0;
      t.nextY[i] = (i + 1 < count) ? cellY[i + 1] - cellY[i] : 0;
      t.prevX[i] = (i > 0) ? cellX[i - 1] - cellX[i] : 0;
      t.prevY[i] = (i > 0) ? cellY[i - 1] - cellY[i] : 0;
    }

    this.tables[curveOrder] = t;
    return t;
  }

  /**
   * Grid cell visited at arc index d, packed as (x &lt;&lt; 16) | y.
   *
   * The standard construction: read d two bits at a time from the coarsest
   * quadrant down, and reflect the accumulated position at each level to match
   * the orientation that quadrant's sub-curve is drawn in.
   *
   * Packing the pair into an int is the direct equivalent of the script's
   * module-level pointX / pointY: it runs 16384 times per table build and
   * returning an object would litter the heap for no reason. Order 7 is 128
   * cells across, so both coordinates fit a short with room to spare.
   */
  private static int hilbertPoint(int n, int d) {
    int t = d;
    int x = 0;
    int y = 0;
    for (int s = 1; s < n; s *= 2) {
      int rx = 1 & (t / 2);
      int ry = 1 & (t ^ rx);
      if (ry == 0) {
        if (rx == 1) {
          x = s - 1 - x;
          y = s - 1 - y;
        }
        int swap = x;
        x = y;
        y = swap;
      }
      x += s * rx;
      y += s * ry;
      t = t / 4;
    }
    return (x << 16) | y;
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
