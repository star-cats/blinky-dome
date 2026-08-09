/**
 * The Burning Man figure, built as tubes in space and rendered by ray casting.
 *
 * The logo is two arcs and a diamond head. Rather than rasterize it, the three
 * strokes are round tubes following polylines in 3D, and every LED casts a ray
 * from the eye and asks how close that ray passes to the nearest tube axis. A
 * ray hits a tube exactly when that distance is under the tube's radius, so the
 * silhouette is exact at any LED density, the strokes have real round caps and
 * joins, and the edges anti-alias for free on a sparse fixture.
 *
 * Tubes rather than a flat cutout, and that is the whole design. An earlier
 * version put a 2D distance field on a plane and turned the plane, which is
 * cheaper and looks identical head on — but a plane has no thickness, so at
 * ninety degrees it is edge on and correctly invisible. Worse, the projection
 * degenerates there: with the plane edge on, every screen point inverts to the
 * same coordinate and the figure collapses to a line. Nothing can be recovered
 * from that, because the geometry really does have nothing to show. A tube has
 * a cross-section from every direction, so here the figure keeps its full stroke
 * width all the way round, and at ninety degrees it reads as the profile of a
 * thing rather than as a bug.
 *
 * Perspective comes free with the ray. The eye sits at 1/Persp on the z axis and
 * each ray runs from there through the LED's spot on the screen, so at high
 * Persp the near side of the figure genuinely spreads and the far side genuinely
 * compresses. At Persp zero the rays are parallel and the projection is a clean
 * orthographic one, rather than a division by an infinite eye distance.
 *
 * Two axes, each with a pose and a rate that stack: Angle is where the figure is
 * pointed and Spin is how fast it turns from there, about the vertical; Tilt is
 * how far it leans and Tilt Spin how fast it leans on, about the axis running
 * into the page. Set either speed to the middle and its knob alone poses the
 * figure. Nothing has momentum, so unlike the card every one of them is free to
 * be assigned at any time.
 *
 * The two axes read differently on purpose. Turning about the vertical is what
 * the perspective bites on — the figure genuinely comes at you and recedes.
 * Leaning about the view axis moves nothing toward or away from the eye, so it
 * stays a clean lean at any Persp, and at speed it cartwheels.
 */

// ------------------------------------------------------------------- geometry
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
// The three x numbers are not independent. WAIST_HALF_GAP is the design intent —
// how close the two limbs come at their tightest — and LEFT_CTRL_X is solved
// from it at load, so widening the waist cannot silently be undone by someone
// later nudging a tip. See solveControlX.

var LEFT_TIP_X = -0.62;
var LEFT_TIP_Y = 0.85;
var LEFT_FOOT_X = -0.41;
var LEFT_FOOT_Y = -0.90;
var LEFT_CTRL_Y = 0.0;

/**
 * Half the closest approach between the two limb centerlines.
 *
 * The artwork has these nearly touching — about 0.08 here — which at LED pitch
 * closes up entirely and reads as one solid X. Doubled, so the waist is a gap
 * the fixture can actually resolve. Lower it toward 0.08 to go back to the
 * printed proportions.
 */
var WAIST_HALF_GAP = 0.1608;

// The head, as a rhombus outline floating above the arms. Drawn as a closed
// four-segment loop, so it gets the same round joins the limbs get — which is
// what the artwork's diamond has at its corners.
var HEAD_Y = 0.715;
var HEAD_HALF_W = 0.158;
var HEAD_HALF_H = 0.198;

// Head stroke relative to the body stroke. The artwork draws the diamond a
// little finer than the limbs.
var HEAD_WEIGHT = 0.85;

// Segments per limb. The curve is flattened once at load, since the figure never
// changes shape — only its placement does — and a per-LED distance to twenty
// segments is far cheaper than a per-LED solve against the curve itself.
var CURVE_SEGMENTS = 20;

/**
 * Radius in figure units beyond which no part of the figure can reach; the arm
 * tip is the furthest thing out, at about 1.05. One cheap ray-to-sphere test
 * against this rejects most of the model before any segment is touched.
 */
var FIGURE_RADIUS = 1.1;

/** Half the figure's depth once turned, in figure units: the widest |x| it has. */
var FIGURE_HALF_DEPTH = 0.62;

knob("size", "Size", "Figure height as a fraction of the frame", 0.72);
knob("posx", "X", "Figure center, horizontal", 0.5);
knob("posy", "Y", "Figure center, vertical", 0.5);

knob("angle", "Angle", "Rotation about the vertical axis; 0.5 is face on", 0.5);
knob("spin", "Spin", "How fast it turns from that angle; 0.5 is still", 0.56);

knob("tilt", "Tilt", "Lean about the axis into the page; 0.5 is upright", 0.5);
knob("tiltSpin", "Tilt Spin", "How fast it leans from that tilt; 0.5 is still", 0.5);

knob("persp", "Persp", "Perspective strength; 0 is an orthographic projection", 0.5);

knob("thickness", "Thickness", "Stroke width, as a fraction of the figure's height", 0.42);
knob("soft", "Soft", "Edge softness — this is the anti-aliasing", 0.22);

knob("shade", "Shade", "How much the far side of the figure dims, for depth", 0.35);
knob("hue", "Hue", "Figure hue", 0.08);
knob("sat", "Saturation", "Figure saturation; zero is white", 0.0);
knob("level", "Level", "Overall brightness", 1);

toggle("autoAspect", "Aspect", "Correct for a non-square model", true);

// The figure's polylines in its own 2D coordinates, flattened once at load, and
// the same polylines transformed into world space once per frame. Stored as flat
// arrays of alternating coordinates rather than as point objects: these are
// walked once per LED, and the allocation-free form measurably matters there.
// One array per stroke, and deliberately not one array for all of them.
// probePolyline treats what it is handed as a single connected run of segments,
// so packing two limbs end to end quietly draws a tube from the foot of one to
// the raised hand of the other, straight across the body.
var leftFlat = [];
var rightFlat = [];
var headFlat = [];

var leftWorld = [];
var rightWorld = [];
var headWorld = [];

// Accumulated rotation from the two speed knobs, in degrees. Integrated rather
// than derived from the wall clock so that changing a speed changes the rate
// from where the figure is, instead of teleporting it to wherever a global clock
// says it should have been by now.
var spinDeg = 0;
var tiltDeg = 0;

// Per-frame values, so the trig and the knob mappings happen once a frame rather
// than once an LED.
var halfHeight = 0.36;
var bodyRadius = 0.027;
var headRadius = 0.023;
var softWorld = 0.008;
var boundingRadius = 0.4;
var depthSpan = 0.22;
var eyeZ = 2;
var orthographic = false;
var aspectX = 1;

function init() {
  var controlX = solveControlX(LEFT_TIP_X, LEFT_FOOT_X, WAIST_HALF_GAP);

  leftFlat = flattenCurve(
    LEFT_TIP_X, LEFT_TIP_Y,
    controlX, LEFT_CTRL_Y,
    LEFT_FOOT_X, LEFT_FOOT_Y
  );

  // The figure is bilaterally symmetric, so the right limb is the left one
  // mirrored rather than a second set of numbers to keep in step with it.
  rightFlat = [];
  for (var i = 0; i < leftFlat.length; i += 2) {
    rightFlat.push(-leftFlat[i]);
    rightFlat.push(leftFlat[i + 1]);
  }

  // Closed loop: the last point repeats the first, so the diamond has four
  // segments and no seam at the top.
  headFlat = [
    0, HEAD_Y + HEAD_HALF_H,
    HEAD_HALF_W, HEAD_Y,
    0, HEAD_Y - HEAD_HALF_H,
    -HEAD_HALF_W, HEAD_Y,
    0, HEAD_Y + HEAD_HALF_H
  ];

  leftWorld = new Array(leftFlat.length / 2 * 3);
  rightWorld = new Array(rightFlat.length / 2 * 3);
  headWorld = new Array(headFlat.length / 2 * 3);
}

/**
 * The control x that puts a limb's tightest point exactly WAIST_HALF_GAP from
 * the axis.
 *
 * A quadratic in t, x(t) = a + 2(c-a)t + (a-2c+b)t², has its extreme at
 * x = a - B²/(4A) with A = a-2c+b and B = 2(c-a). Setting that equal to the
 * target and solving for c gives a quadratic in c, whose positive root is the
 * control point on the far side that bows the curve inward.
 *
 * Solved rather than eyeballed because the waist is the one measurement in this
 * figure that has to be right: it is the negative space that makes the shape
 * read as a person rather than as a cross.
 */
function solveControlX(a, b, targetHalfGap) {
  // Target is a signed x on the left limb, which lives at negative x.
  var target = -Math.abs(targetHalfGap);

  // a - B²/(4A) = target reduces to (c - a)² = (a + b - 2c)(a - target), which
  // in c is c² + 2(k - a)c + (a² - (a + b)k) with k = a - target. The positive
  // root is the control point on the far side, the one that bows the curve in.
  var k = a - target;
  var qb = 2 * (k - a);
  var qc = a * a - (a + b) * k;

  var disc = qb * qb - 4 * qc;
  if (disc < 0) {
    // No control point can pull the curve that far in; fall back to the
    // midpoint construction, which at least lands the middle of the curve on
    // the target even if the true extreme sits a little inside it.
    return 2 * target - (a + b) / 2;
  }
  return (-qb + Math.sqrt(disc)) / 2;
}

/** Flatten a quadratic Bezier into a polyline of CURVE_SEGMENTS spans. */
function flattenCurve(x0, y0, cx, cy, x1, y1) {
  var points = [];
  for (var i = 0; i <= CURVE_SEGMENTS; ++i) {
    var t = i / CURVE_SEGMENTS;
    var s = 1 - t;
    points.push(s * s * x0 + 2 * s * t * cx + t * t * x1);
    points.push(s * s * y0 + 2 * s * t * cy + t * t * y1);
  }
  return points;
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;
  spinDeg += (spin - 0.5) * 2 * 180 * dt;
  tiltDeg += (tiltSpin - 0.5) * 2 * 180 * dt;
  // Wrapped rather than left to grow: these feed a cos/sin every frame forever,
  // and a number that climbs without bound eventually loses the precision that
  // makes a slow spin look smooth.
  spinDeg = spinDeg % 360;
  tiltDeg = tiltDeg % 360;

  var theta = ((angle - 0.5) * 360 + spinDeg) * Math.PI / 180;
  var cosT = Math.cos(theta);
  var sinT = Math.sin(theta);

  var phi = ((tilt - 0.5) * 360 + tiltDeg) * Math.PI / 180;
  var cosP = Math.cos(phi);
  var sinP = Math.sin(phi);

  halfHeight = lerp(0.1, 0.72, size);
  bodyRadius = lerp(0.012, 0.16, thickness) * halfHeight;
  headRadius = bodyRadius * HEAD_WEIGHT;
  // Floored, because this is a divisor and a zero would make the edge ramp a
  // step function of infinite slope — every LED fully on or fully off, which is
  // the one thing casting against a distance is here to avoid.
  softWorld = Math.max(0.003, soft * 0.12) * halfHeight;

  depthSpan = Math.max(1e-4, FIGURE_HALF_DEPTH * halfHeight);
  boundingRadius = FIGURE_RADIUS * halfHeight + bodyRadius + softWorld;

  // The eye sits at 1/perspK, so zero falls out as a parallel projection rather
  // than a division by infinity. It is also held clear of the figure's own
  // depth: a large figure at full Persp would otherwise put the eye inside the
  // geometry, where parts of the figure fall behind the viewer and simply stop
  // being drawn.
  var perspK = persp * 1.6;
  var maxK = 1 / (depthSpan * 1.8);
  if (perspK > maxK) {
    perspK = maxK;
  }
  orthographic = (perspK <= 1e-3);
  eyeZ = orthographic ? 0 : (1 / perspK);

  // Both rotations, applied once here rather than once per LED.
  transformPolylines(cosT, sinT, cosP, sinP);

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }
}

/** Each stroke into its own world-space array. */
function transformPolylines(cosT, sinT, cosP, sinP) {
  toWorld(leftWorld, leftFlat, cosT, sinT, cosP, sinP);
  toWorld(rightWorld, rightFlat, cosT, sinT, cosP, sinP);
  toWorld(headWorld, headFlat, cosT, sinT, cosP, sinP);
}

/**
 * A figure point into world space: turn about the vertical, then lean about the
 * axis running into the page.
 *
 * The order is what makes the two knobs independent to use. Angle turns the
 * figure on its own feet, and Tilt then leans that whole turned figure toward
 * one shoulder — so Tilt always means the same thing on screen no matter where
 * Angle has got to. Composed the other way round, leaning first and then
 * turning, the lean would rotate out of the picture plane as Angle advanced and
 * a tilted figure would appear to straighten up on its own.
 *
 * Depth is untouched by the lean, since rotating about the view axis moves
 * nothing toward or away from the eye. That is why the eye distance and the
 * depth shading below need no adjusting for it.
 */
function toWorld(out, flat, cosT, sinT, cosP, sinP) {
  var at = 0;
  for (var i = 0; i < flat.length; i += 2) {
    var x = flat[i] * halfHeight;
    var turnedX = x * cosT;
    var y = flat[i + 1] * halfHeight;
    out[at++] = turnedX * cosP - y * sinP;
    out[at++] = turnedX * sinP + y * cosP;
    out[at++] = x * sinT;
  }
}

/**
 * World z of the point on the axis that the last probe came closest to.
 *
 * Returned this way rather than in an object because probePolyline runs tens of
 * times per LED, and allocating a result for each would dominate the cost of the
 * arithmetic it exists to report.
 */
var probeZ = 0;

function renderPoint(point, deltaMs) {
  // Screen coordinates, centered on the figure and corrected so it is not
  // stretched by a model that is wider than it is tall.
  var sx = (point.xn - posx) * aspectX;
  var sy = point.yn - posy;

  // The ray for this LED. Under perspective every ray leaves the eye on the z
  // axis and passes through this point on the z=0 plane; orthographically they
  // all run straight back along -z from the point itself.
  var ox, oy, oz, rx, ry, rz;
  if (orthographic) {
    ox = sx;
    oy = sy;
    oz = boundingRadius + 1;
    rx = 0;
    ry = 0;
    rz = -1;
  } else {
    ox = 0;
    oy = 0;
    oz = eyeZ;
    rx = sx;
    ry = sy;
    rz = -eyeZ;
  }

  // One sphere test around the whole figure, which on a model bigger than the
  // logo rejects nearly every LED before a single segment is looked at. The
  // unclamped line distance is used deliberately: it can only ever be smaller
  // than the true ray distance, so this over-includes and never wrongly cuts.
  var cxr = oy * rz - oz * ry;
  var cyr = oz * rx - ox * rz;
  var czr = ox * ry - oy * rx;
  var rLenSq = rx * rx + ry * ry + rz * rz;
  if (rLenSq <= 0) {
    return rgb(0, 0, 0);
  }
  if ((cxr * cxr + cyr * cyr + czr * czr) > boundingRadius * boundingRadius * rLenSq) {
    return rgb(0, 0, 0);
  }

  // Probed one limb at a time. The two are never walked as a single polyline,
  // which would put a segment between them.
  var bodyDistance = probePolyline(ox, oy, oz, rx, ry, rz, leftWorld);
  var bodyZ = probeZ;
  var rightDistance = probePolyline(ox, oy, oz, rx, ry, rz, rightWorld);
  if (rightDistance < bodyDistance) {
    bodyDistance = rightDistance;
    bodyZ = probeZ;
  }

  var headDistance = probePolyline(ox, oy, oz, rx, ry, rz, headWorld);
  var headZ = probeZ;

  // Ramp rather than threshold. On a fixture whose columns are sparser than its
  // rows a hard edge lands between columns and disappears; a ramp puts a dim LED
  // either side of where the true edge fell.
  var bodyMask = clamp(1 - (bodyDistance - bodyRadius) / softWorld, 0, 1);
  var headMask = clamp(1 - (headDistance - headRadius) / softWorld, 0, 1);

  var mask = bodyMask;
  var hitZ = bodyZ;
  if (headMask > mask) {
    mask = headMask;
    hitZ = headZ;
  }
  if (mask <= 0) {
    return rgb(0, 0, 0);
  }

  // Depth shading, so the figure has volume as it turns. Tied to which part of
  // the figure the ray actually struck rather than to the overall angle, which
  // is what keeps a stroke coming toward the viewer brighter than the one going
  // away behind it — and which means no angle can dim the whole figure to
  // nothing the way a flat facing term would.
  var nearness = clamp(0.5 + hitZ / (2 * depthSpan), 0, 1);
  var shading = 1 - shade * (1 - nearness);

  return hsb(hue * 360, sat * 100, mask * shading * level * 100);
}

/**
 * Closest distance from a ray to a polyline, in world units.
 *
 * The polyline is a tube axis, so this is the whole visibility test: the ray
 * enters the tube exactly when this comes back under the tube's radius. Also
 * records the depth of the closest point, in probeZ.
 */
function probePolyline(ox, oy, oz, rx, ry, rz, pts) {
  var best = Infinity;
  var bestZ = 0;

  for (var i = 0; i + 5 < pts.length; i += 3) {
    var ax = pts[i], ay = pts[i + 1], az = pts[i + 2];
    var vx = pts[i + 3] - ax, vy = pts[i + 4] - ay, vz = pts[i + 5] - az;
    var wx = ox - ax, wy = oy - ay, wz = oz - az;

    var a = rx * rx + ry * ry + rz * rz;
    var b = rx * vx + ry * vy + rz * vz;
    var c = vx * vx + vy * vy + vz * vz;
    var d = rx * wx + ry * wy + rz * wz;
    var e = vx * wx + vy * wy + vz * wz;

    var den = a * c - b * b;
    var t, s;
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

    var qx = ax + s * vx;
    var qy = ay + s * vy;
    var qz = az + s * vz;
    var dx = (ox + t * rx) - qx;
    var dy = (oy + t * ry) - qy;
    var dz = (oz + t * rz) - qz;
    var distSq = dx * dx + dy * dy + dz * dz;

    if (distSq < best) {
      best = distSq;
      bestZ = qz;
    }
  }

  probeZ = bestZ;
  return Math.sqrt(best);
}
