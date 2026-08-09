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
 * The Burning Man figure, built as tubes in space and rendered by ray casting.
 * Ported from Scripts/BurningMan.js.
 *
 * The logo is two arcs and a diamond head. Rather than rasterize it, the three
 * strokes are round tubes following polylines in 3D, and every LED casts a ray
 * from the eye and asks how close that ray passes to the nearest tube axis. A ray
 * hits a tube exactly when that distance is under the tube's radius, so the
 * silhouette is exact at any LED density, the strokes have real round caps and
 * joins, and the edges anti-alias for free on a sparse fixture.
 *
 * Tubes rather than a flat cutout, and that is the whole design. An earlier
 * version put a 2D distance field on a plane and turned the plane, which is
 * cheaper and looks identical head on — but a plane has no thickness, so at
 * ninety degrees it is edge on and correctly invisible. Worse, the projection
 * degenerates there: with the plane edge on, every screen point inverts to the
 * same coordinate and the figure collapses to a line. Nothing can be recovered
 * from that, because the geometry really does have nothing to show. A tube has a
 * cross-section from every direction, so here the figure keeps its full stroke
 * width all the way round, and at ninety degrees it reads as the profile of a
 * thing rather than as a bug.
 *
 * Perspective comes free with the ray. The eye sits at 1/Persp on the z axis and
 * each ray runs from there through the LED's spot on the screen, so at high Persp
 * the near side of the figure genuinely spreads and the far side genuinely
 * compresses. At Persp zero the rays are parallel and the projection is a clean
 * orthographic one, rather than a division by an infinite eye distance.
 *
 * Two axes, each with a pose and a rate that stack: Angle is where the figure is
 * pointed and Spin is how fast it turns from there, about the vertical; Tilt is
 * how far it leans and Tilt Spin how fast it leans on, about the axis running
 * into the page. Leave a rate at zero and its angle alone poses the figure.
 * Nothing has momentum, so unlike {@link PlayingCardPattern} every one of them is
 * free to be assigned at any time.
 *
 * The two axes read differently on purpose. Turning about the vertical is what
 * the perspective bites on — the figure genuinely comes at you and recedes.
 * Leaning about the view axis moves nothing toward or away from the eye, so it
 * stays a clean lean at any Persp, and at speed it cartwheels.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Burning Man")
@LXComponent.Description("The Burning Man figure as 3D tubes, turning in perspective")
public class BurningManPattern extends LXPattern {

  // -------------------------------------------------------------------- geometry
  //
  // The figure in its own coordinates: origin at the middle, +y up, and the whole
  // thing about two units tall so the numbers read as fractions of half its
  // height. These are centerlines — the drawn stroke is Thickness wide around
  // them — which is why the arm tips sit a little inside the top of the artwork.
  //
  // Each limb is one quadratic Bezier. The control point is well outside the
  // figure on the *opposite* side, which is what pulls the middle of the curve in
  // to make the waist while leaving the ends splayed.
  //
  // The three x numbers are not independent. WAIST_HALF_GAP is the design intent
  // — how close the two limbs come at their tightest — and the control point is
  // solved from it at construction, so widening the waist cannot silently be
  // undone by someone later nudging a tip. See solveControlX.

  private static final double LEFT_TIP_X = -.62;
  private static final double LEFT_TIP_Y = .85;
  private static final double LEFT_FOOT_X = -.41;
  private static final double LEFT_FOOT_Y = -.90;
  private static final double LEFT_CTRL_Y = 0;

  /**
   * Half the closest approach between the two limb centerlines.
   *
   * The artwork has these nearly touching — about .08 here — which at LED pitch
   * closes up entirely and reads as one solid X. Doubled, so the waist is a gap
   * the fixture can actually resolve. Lower it toward .08 to go back to the
   * printed proportions.
   */
  private static final double WAIST_HALF_GAP = .1608;

  // The head, as a rhombus outline floating above the arms. Drawn as a closed
  // four-segment loop, so it gets the same round joins the limbs get — which is
  // what the artwork's diamond has at its corners.
  private static final double HEAD_Y = .715;
  private static final double HEAD_HALF_W = .158;
  private static final double HEAD_HALF_H = .198;

  /** Head stroke relative to the body stroke; the artwork draws it a little finer. */
  private static final double HEAD_WEIGHT = .85;

  /**
   * Segments per limb. The curve is flattened once at construction, since the
   * figure never changes shape — only its placement does — and a per-LED distance
   * to twenty segments is far cheaper than a per-LED solve against the curve.
   */
  private static final int CURVE_SEGMENTS = 20;

  /**
   * Radius in figure units beyond which no part of the figure can reach; the arm
   * tip is the furthest thing out, at about 1.05. One cheap ray-to-sphere test
   * against this rejects most of the model before any segment is touched.
   */
  private static final double FIGURE_RADIUS = 1.1;

  /** Half the figure's depth once turned, in figure units: the widest |x| it has. */
  private static final double FIGURE_HALF_DEPTH = .62;

  // ------------------------------------------------------------------ parameters

  public final CompoundParameter size =
    new CompoundParameter("Size", .72, 0, 1)
    .setDescription("Figure height as a fraction of the frame");

  public final CompoundParameter posX =
    new CompoundParameter("X", .5, 0, 1)
    .setDescription("Figure center, horizontal");

  public final CompoundParameter posY =
    new CompoundParameter("Y", .5, 0, 1)
    .setDescription("Figure center, vertical");

  public final CompoundParameter angle =
    new CompoundParameter("Angle", 0, -180, 180)
    .setUnits(LXParameter.Units.DEGREES)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Rotation about the vertical axis; 0 is face on");

  public final CompoundParameter spin =
    new CompoundParameter("Spin", 21.6, -180, 180)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("How fast it turns from that angle, in degrees per second");

  public final CompoundParameter tilt =
    new CompoundParameter("Tilt", 0, -180, 180)
    .setUnits(LXParameter.Units.DEGREES)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Lean about the axis into the page; 0 is upright");

  public final CompoundParameter tiltSpin =
    new CompoundParameter("Tilt Spin", 0, -180, 180)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("How fast it leans from that tilt, in degrees per second");

  public final CompoundParameter persp =
    new CompoundParameter("Persp", .5, 0, 1)
    .setDescription("Perspective strength; 0 is an orthographic projection");

  public final CompoundParameter thickness =
    new CompoundParameter("Thick", .42, 0, 1)
    .setDescription("Stroke width, as a fraction of the figure's height");

  public final CompoundParameter soft =
    new CompoundParameter("Soft", .22, 0, 1)
    .setDescription("Edge softness -- this is the anti-aliasing");

  public final CompoundParameter shade =
    new CompoundParameter("Shade", .35, 0, 1)
    .setDescription("How much the far side of the figure dims, for depth");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 30, 0, 360)
    .setUnits(LXParameter.Units.DEGREES)
    .setDescription("Figure hue");

  public final CompoundParameter saturation =
    new CompoundParameter("Sat", 0, 0, 100)
    .setUnits(LXParameter.Units.PERCENT)
    .setDescription("Figure saturation; at 0 it is white and the hue does nothing");

  public final CompoundParameter level =
    new CompoundParameter("Level", 100, 0, 100)
    .setUnits(LXParameter.Units.PERCENT)
    .setDescription("Overall brightness");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Correct for a non-square model");

  // ----------------------------------------------------------------- the strokes
  //
  // The figure's polylines in its own 2D coordinates, flattened once at
  // construction, and the same polylines transformed into world space once per
  // frame. Flat arrays of alternating coordinates rather than point objects:
  // these are walked once per LED, and the allocation-free form matters there.
  //
  // One array per stroke, and deliberately not one array for all of them.
  // probePolyline treats what it is handed as a single connected run of segments,
  // so packing two limbs end to end quietly draws a tube from the foot of one to
  // the raised hand of the other, straight across the body.

  private final double[] leftFlat;
  private final double[] rightFlat;
  private final double[] headFlat;

  private final double[] leftWorld;
  private final double[] rightWorld;
  private final double[] headWorld;

  /**
   * Accumulated rotation from the two speed knobs, in degrees. Integrated rather
   * than derived from the wall clock so that changing a speed changes the rate
   * from where the figure is, instead of teleporting it to wherever a global clock
   * says it should have been by now.
   */
  private double spinDeg = 0;
  private double tiltDeg = 0;

  // Per-frame values, so the trig and the knob mappings happen once a frame rather
  // than once an LED.
  private double halfHeight = .36;
  private double bodyRadius = .027;
  private double headRadius = .023;
  private double softWorld = .008;
  private double boundingRadius = .4;
  private double depthSpan = .22;
  private double eyeZ = 2;
  private boolean orthographic = false;
  private double aspectX = 1;

  /**
   * World z of the point on the axis that the last probe came closest to.
   *
   * Carried in a field rather than returned in an object because probePolyline
   * runs three times per LED, and allocating a result for each would dominate the
   * cost of the arithmetic it exists to report.
   */
  private double probeZ = 0;

  public BurningManPattern(LX lx) {
    super(lx);
    addParameter("size", this.size);
    addParameter("posX", this.posX);
    addParameter("posY", this.posY);
    addParameter("angle", this.angle);
    addParameter("spin", this.spin);
    addParameter("tilt", this.tilt);
    addParameter("tiltSpin", this.tiltSpin);
    addParameter("persp", this.persp);
    addParameter("thickness", this.thickness);
    addParameter("soft", this.soft);
    addParameter("shade", this.shade);
    addParameter("hue", this.hue);
    addParameter("saturation", this.saturation);
    addParameter("level", this.level);
    addParameter("autoAspect", this.autoAspect);

    double controlX = solveControlX(LEFT_TIP_X, LEFT_FOOT_X, WAIST_HALF_GAP);
    this.leftFlat = flattenCurve(
      LEFT_TIP_X, LEFT_TIP_Y, controlX, LEFT_CTRL_Y, LEFT_FOOT_X, LEFT_FOOT_Y);

    // The figure is bilaterally symmetric, so the right limb is the left one
    // mirrored rather than a second set of numbers to keep in step with it.
    this.rightFlat = new double[this.leftFlat.length];
    for (int i = 0; i < this.leftFlat.length; i += 2) {
      this.rightFlat[i] = -this.leftFlat[i];
      this.rightFlat[i + 1] = this.leftFlat[i + 1];
    }

    // Closed loop: the last point repeats the first, so the diamond has four
    // segments and no seam at the top.
    this.headFlat = new double[] {
      0, HEAD_Y + HEAD_HALF_H,
      HEAD_HALF_W, HEAD_Y,
      0, HEAD_Y - HEAD_HALF_H,
      -HEAD_HALF_W, HEAD_Y,
      0, HEAD_Y + HEAD_HALF_H
    };

    this.leftWorld = new double[this.leftFlat.length / 2 * 3];
    this.rightWorld = new double[this.rightFlat.length / 2 * 3];
    this.headWorld = new double[this.headFlat.length / 2 * 3];
  }

  /**
   * The control x that puts a limb's tightest point exactly WAIST_HALF_GAP from
   * the axis.
   *
   * A quadratic in t, x(t) = a + 2(c-a)t + (a-2c+b)t², has its extreme at
   * x = a - B²/(4A) with A = a-2c+b and B = 2(c-a). Setting that equal to the
   * target reduces to (c - a)² = (a + b - 2c)(a - target), a quadratic in c whose
   * positive root is the control point on the far side that bows the curve inward.
   *
   * Solved rather than eyeballed because the waist is the one measurement in this
   * figure that has to be right: it is the negative space that makes the shape
   * read as a person rather than as a cross. Note the extreme does not sit at the
   * midpoint of the curve -- it lands near t = .58 here -- so placing the control
   * point by eye against the halfway point misses it.
   */
  private static double solveControlX(double a, double b, double targetHalfGap) {
    // Target is a signed x on the left limb, which lives at negative x.
    double target = -Math.abs(targetHalfGap);

    double k = a - target;
    double qb = 2 * (k - a);
    double qc = a * a - (a + b) * k;

    double disc = qb * qb - 4 * qc;
    if (disc < 0) {
      // No control point can pull the curve that far in; fall back to the
      // midpoint construction, which at least lands the middle of the curve on
      // the target even if the true extreme sits a little inside it.
      return 2 * target - (a + b) / 2;
    }
    return (-qb + Math.sqrt(disc)) / 2;
  }

  /** Flatten a quadratic Bezier into a polyline of CURVE_SEGMENTS spans. */
  private static double[] flattenCurve(double x0, double y0,
      double cx, double cy, double x1, double y1) {
    double[] points = new double[(CURVE_SEGMENTS + 1) * 2];
    for (int i = 0; i <= CURVE_SEGMENTS; ++i) {
      double t = (double) i / CURVE_SEGMENTS;
      double s = 1 - t;
      points[i * 2] = s * s * x0 + 2 * s * t * cx + t * t * x1;
      points[i * 2 + 1] = s * s * y0 + 2 * s * t * cy + t * t * y1;
    }
    return points;
  }

  @Override
  protected void run(double deltaMs) {
    layout(deltaMs);
    draw();
  }

  private void layout(double deltaMs) {
    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, .25) : 0;
    this.spinDeg += this.spin.getValue() * dt;
    this.tiltDeg += this.tiltSpin.getValue() * dt;
    // Wrapped rather than left to grow: these feed a cos/sin every frame forever,
    // and a number that climbs without bound eventually loses the precision that
    // makes a slow spin look smooth.
    this.spinDeg %= 360;
    this.tiltDeg %= 360;

    double theta = Math.toRadians(this.angle.getValue() + this.spinDeg);
    double cosT = Math.cos(theta);
    double sinT = Math.sin(theta);

    double phi = Math.toRadians(this.tilt.getValue() + this.tiltDeg);
    double cosP = Math.cos(phi);
    double sinP = Math.sin(phi);

    this.halfHeight = lerp(.1, .72, this.size.getValue());
    this.bodyRadius = lerp(.012, .16, this.thickness.getValue()) * this.halfHeight;
    this.headRadius = this.bodyRadius * HEAD_WEIGHT;
    // Floored, because this is a divisor and a zero would make the edge ramp a
    // step function of infinite slope -- every LED fully on or fully off, which is
    // the one thing casting against a distance is here to avoid.
    this.softWorld = Math.max(.003, this.soft.getValue() * .12) * this.halfHeight;

    this.depthSpan = Math.max(1e-4, FIGURE_HALF_DEPTH * this.halfHeight);
    this.boundingRadius = FIGURE_RADIUS * this.halfHeight + this.bodyRadius + this.softWorld;

    // The eye sits at 1/perspK, so zero falls out as a parallel projection rather
    // than a division by infinity. It is also held clear of the figure's own
    // depth: a large figure at full Persp would otherwise put the eye inside the
    // geometry, where parts of the figure fall behind the viewer and simply stop
    // being drawn.
    double perspK = this.persp.getValue() * 1.6;
    double maxK = 1 / (this.depthSpan * 1.8);
    if (perspK > maxK) {
      perspK = maxK;
    }
    this.orthographic = (perspK <= 1e-3);
    this.eyeZ = this.orthographic ? 0 : (1 / perspK);

    // Both rotations, applied once here rather than once per LED.
    toWorld(this.leftWorld, this.leftFlat, cosT, sinT, cosP, sinP);
    toWorld(this.rightWorld, this.rightFlat, cosT, sinT, cosP, sinP);
    toWorld(this.headWorld, this.headFlat, cosT, sinT, cosP, sinP);

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }
  }

  /**
   * A figure point into world space: turn about the vertical, then lean about the
   * axis running into the page.
   *
   * The order is what makes the two knobs independent to use. Angle turns the
   * figure on its own feet, and Tilt then leans that whole turned figure toward
   * one shoulder -- so Tilt always means the same thing on screen no matter where
   * Angle has got to. Composed the other way round, leaning first and then
   * turning, the lean would rotate out of the picture plane as Angle advanced and
   * a tilted figure would appear to straighten up on its own.
   *
   * Depth is untouched by the lean, since rotating about the view axis moves
   * nothing toward or away from the eye. That is why the eye distance and the
   * depth shading need no adjusting for it.
   */
  private void toWorld(double[] out, double[] flat,
      double cosT, double sinT, double cosP, double sinP) {
    int at = 0;
    for (int i = 0; i < flat.length; i += 2) {
      double x = flat[i] * this.halfHeight;
      double turnedX = x * cosT;
      double y = flat[i + 1] * this.halfHeight;
      out[at++] = turnedX * cosP - y * sinP;
      out[at++] = turnedX * sinP + y * cosP;
      out[at++] = x * sinT;
    }
  }

  private void draw() {
    final double px = this.posX.getValue();
    final double py = this.posY.getValue();
    final double shadeAmount = this.shade.getValue();
    final double h = this.hue.getValue();
    final double sat = this.saturation.getValue();
    final double lvl = this.level.getValue() / 100;
    final double boundSq = this.boundingRadius * this.boundingRadius;

    for (LXPoint p : this.model.points) {
      // Screen coordinates, centered on the figure and corrected so it is not
      // stretched by a model that is wider than it is tall.
      double sx = (p.xn - px) * this.aspectX;
      double sy = p.yn - py;

      // The ray for this LED. Under perspective every ray leaves the eye on the z
      // axis and passes through this point on the z=0 plane; orthographically they
      // all run straight back along -z from the point itself.
      double ox, oy, oz, rx, ry, rz;
      if (this.orthographic) {
        ox = sx;
        oy = sy;
        oz = this.boundingRadius + 1;
        rx = 0;
        ry = 0;
        rz = -1;
      } else {
        ox = 0;
        oy = 0;
        oz = this.eyeZ;
        rx = sx;
        ry = sy;
        rz = -this.eyeZ;
      }

      // One sphere test around the whole figure, which on a model bigger than the
      // logo rejects nearly every LED before a single segment is looked at. The
      // unclamped line distance is used deliberately: it can only ever be smaller
      // than the true ray distance, so this over-includes and never wrongly cuts.
      double cxr = oy * rz - oz * ry;
      double cyr = oz * rx - ox * rz;
      double czr = ox * ry - oy * rx;
      double rLenSq = rx * rx + ry * ry + rz * rz;
      if (rLenSq <= 0
        || (cxr * cxr + cyr * cyr + czr * czr) > boundSq * rLenSq) {
        this.colors[p.index] = LXColor.BLACK;
        continue;
      }

      // Probed one limb at a time. The two are never walked as a single polyline,
      // which would put a segment between them.
      double bodyDistance = probePolyline(ox, oy, oz, rx, ry, rz, this.leftWorld);
      double bodyZ = this.probeZ;
      double rightDistance = probePolyline(ox, oy, oz, rx, ry, rz, this.rightWorld);
      if (rightDistance < bodyDistance) {
        bodyDistance = rightDistance;
        bodyZ = this.probeZ;
      }

      double headDistance = probePolyline(ox, oy, oz, rx, ry, rz, this.headWorld);
      double headZ = this.probeZ;

      // Ramp rather than threshold. On a fixture whose columns are sparser than
      // its rows a hard edge lands between columns and disappears; a ramp puts a
      // dim LED either side of where the true edge fell.
      double mask = clamp(1 - (bodyDistance - this.bodyRadius) / this.softWorld, 0, 1);
      double hitZ = bodyZ;
      double headMask = clamp(1 - (headDistance - this.headRadius) / this.softWorld, 0, 1);
      if (headMask > mask) {
        mask = headMask;
        hitZ = headZ;
      }
      if (mask <= 0) {
        this.colors[p.index] = LXColor.BLACK;
        continue;
      }

      // Depth shading, so the figure has volume as it turns. Tied to which part of
      // the figure the ray actually struck rather than to the overall angle, which
      // is what keeps a stroke coming toward the viewer brighter than the one
      // going away behind it -- and which means no angle can dim the whole figure
      // to nothing the way a flat facing term would.
      double nearness = clamp(.5 + hitZ / (2 * this.depthSpan), 0, 1);
      double shading = 1 - shadeAmount * (1 - nearness);

      this.colors[p.index] = LXColor.hsb(h, sat, mask * shading * lvl * 100);
    }
  }

  /**
   * Closest distance from a ray to a polyline, in world units.
   *
   * The polyline is a tube axis, so this is the whole visibility test: the ray
   * enters the tube exactly when this comes back under the tube's radius. Also
   * records the depth of the closest point, in {@link #probeZ}.
   */
  private double probePolyline(double ox, double oy, double oz,
      double rx, double ry, double rz, double[] pts) {
    double best = Double.MAX_VALUE;
    double bestZ = 0;

    // The ray direction is the same for every segment, so its own dot product
    // comes out of the loop.
    final double a = rx * rx + ry * ry + rz * rz;

    for (int i = 0; i + 5 < pts.length; i += 3) {
      double ax = pts[i], ay = pts[i + 1], az = pts[i + 2];
      double vx = pts[i + 3] - ax, vy = pts[i + 4] - ay, vz = pts[i + 5] - az;
      double wx = ox - ax, wy = oy - ay, wz = oz - az;

      double b = rx * vx + ry * vy + rz * vz;
      double c = vx * vx + vy * vy + vz * vz;
      double d = rx * wx + ry * wy + rz * wz;
      double e = vx * wx + vy * wy + vz * wz;

      double den = a * c - b * b;
      double t, s;
      if (den > 1e-12) {
        t = (b * e - c * d) / den;
        s = (a * e - b * d) / den;
      } else {
        // Ray parallel to the segment: any point on the ray is as good, so take
        // its origin and slide along the segment from there.
        t = 0;
        s = (c > 0) ? (e / c) : 0;
      }

      // Clamp onto the segment, re-solve the ray for that point, clamp the ray to
      // the half in front of the eye, then re-solve the segment once more. For a
      // problem this shape that lands exactly on the constrained optimum.
      s = clamp(s, 0, 1);
      t = (a > 0) ? ((s * b - d) / a) : 0;
      if (t < 0) {
        t = 0;
        s = (c > 0) ? clamp(e / c, 0, 1) : 0;
      }

      double qx = ax + s * vx;
      double qy = ay + s * vy;
      double qz = az + s * vz;
      double dx = (ox + t * rx) - qx;
      double dy = (oy + t * ry) - qy;
      double dz = (oz + t * rz) - qz;
      double distSq = dx * dx + dy * dy + dz * dz;

      if (distSq < best) {
        best = distSq;
        bestZ = qz;
      }
    }

    this.probeZ = bestZ;
    return Math.sqrt(best);
  }

  private static double clamp(double v, double min, double max) {
    return (v < min) ? min : (v > max) ? max : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
