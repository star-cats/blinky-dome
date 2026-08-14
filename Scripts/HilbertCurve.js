/**
 * A Hilbert curve you can fly over, with light running along it.
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

var MAX_ORDER = 7;

knob("order", "Order", "Curve order: 1 is a 2x2 grid, 7 is 128x128", 0.45);

knob("zoom", "Zoom", "Scale of the curve; center is 1x, ends are 1/8x and 8x", 0.5);
knob("rot", "Rotate", "Rotation of the curve, a full turn across the knob", 0);
knob("panx", "Pan X", "Horizontal position within the curve; center is centered", 0.5);
knob("pany", "Pan Y", "Vertical position within the curve; center is centered", 0.5);

knob("band", "Band", "Thickness of the drawn line, in cells", 0.28);
knob("soft", "Soft", "Edge softness of the line — this is the anti-aliasing", 0.2);

knob("width", "Width", "Length of one black-to-white ramp, as a fraction of the curve", 0.25);
knob("speed", "Speed", "Travel along the curve; 0.5 is still, below it runs backward", 0.62);

knob("hue", "Hue", "Tint hue", 0.55);
knob("sat", "Saturation", "Saturation at the tail; the leading edge is always white", 0.8);
knob("level", "Level", "Overall brightness", 1);

toggle("autoAspect", "Aspect", "Keep the curve square on a non-square model", true);

/** Curve tables, one per order, built on first use and kept. */
var tables = [];

// Per-frame values, resolved once in preRender rather than per LED.
var curve = null;
var gridN = 1;
var cellSpan = 1;
var phase = 0;
var cosT = 1;
var sinT = 0;
var invZoom = 1;
var panWorldX = 0;
var panWorldY = 0;
var aspectX = 1;
var bandRadius = 0.25;
var softness = 0.05;
var rampWidth = 0.25;

// Running best of the per-cell distance query, and scratch for the curve
// tabulation. Returning objects from either would allocate once per LED per
// cell tested — a few hundred thousand short-lived objects a second.
var bestDist2 = 0;
var bestArc = 0;
var pointX = 0;
var pointY = 0;

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var requested = clampInt(1 + Math.floor(order * MAX_ORDER), 1, MAX_ORDER);
  curve = table(requested);
  gridN = curve.n;
  cellSpan = curve.count - 1;

  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;
  // Phase is integrated rather than derived from wall time, so turning Speed
  // changes where the ramp goes next instead of teleporting it.
  phase = wrap01(phase + dt * (speed - 0.5) * 2 * 0.9);

  var angle = rot * Math.PI * 2;
  cosT = Math.cos(angle);
  sinT = Math.sin(angle);

  invZoom = 1 / Math.pow(2, (zoom - 0.5) * 6);

  // Pan is in curve space, so its axes stay locked to the curve rather than to
  // the screen: Pan X always walks the same way through the pattern, whatever
  // the rotation is. Full deflection clears the frame at 1x zoom.
  panWorldX = (panx - 0.5) * 2;
  panWorldY = (pany - 0.5) * 2;

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  // Widths are in cells, so they hold their look relative to the curve as the
  // order changes, and thicken on screen as you zoom in.
  bandRadius = lerp(0.03, 0.5, band);
  softness = Math.max(lerp(0.004, 0.35, soft), 1e-4);
  rampWidth = Math.max(lerp(0.02, 1, width), 1e-4);
}

function renderPoint(point, deltaMs) {
  if (curve == null) {
    return rgb(0, 0, 0);
  }

  var sx = (point.xn - 0.5) * aspectX;
  var sy = (point.yn - 0.5);

  // Screen back to curve space: unrotate, unzoom, then offset by the pan.
  var rx = (cosT * sx + sinT * sy) * invZoom + panWorldX;
  var ry = (-sinT * sx + cosT * sy) * invZoom + panWorldY;

  var u = rx + 0.5;
  var v = ry + 0.5;
  if (u < 0 || u >= 1 || v < 0 || v >= 1) {
    return rgb(0, 0, 0);
  }

  var gx = u * gridN;
  var gy = v * gridN;
  var ix = clampInt(Math.floor(gx), 0, gridN - 1);
  var iy = clampInt(Math.floor(gy), 0, gridN - 1);

  // Local coordinates about the cell center, each in [-0.5, 0.5].
  var lx = gx - (ix + 0.5);
  var ly = gy - (iy + 0.5);

  bestDist2 = 1e18;
  bestArc = 0;
  considerCell(ix, iy, lx, ly);

  // A neighbor is only worth testing when the point is within the band of the
  // shared edge — closer than that, nothing on the far side can be in range.
  // A diagonal neighbor can never beat both edge neighbors, so it is skipped.
  var reach = 0.5 - bandRadius;
  if (lx > reach && ix + 1 < gridN) {
    considerCell(ix + 1, iy, lx - 1, ly);
  } else if (lx < -reach && ix > 0) {
    considerCell(ix - 1, iy, lx + 1, ly);
  }
  if (ly > reach && iy + 1 < gridN) {
    considerCell(ix, iy + 1, lx, ly - 1);
  } else if (ly < -reach && iy > 0) {
    considerCell(ix, iy - 1, lx, ly + 1);
  }

  var dist = Math.sqrt(bestDist2);
  var edge = clamp((bandRadius - dist) / softness, 0, 1);
  if (edge <= 0) {
    return rgb(0, 0, 0);
  }

  // Arc position normalized to the whole path, then a sawtooth in it: a linear
  // climb across Width, and a hard reset. This is whiteness, 0 at the tail and
  // 1 at the leading edge, and it colors the trail as well as lighting it —
  // saturation falls as brightness rises, so the tail is the tint at full
  // saturation and the head bleaches out to white. Only `edge`, the softness
  // across the line's thickness, is a brightness-only term.
  var along = cellSpan > 0 ? bestArc / cellSpan : 0;
  var whiteness = wrap01((along - phase) / rampWidth);

  return hsb(
    hue * 360,
    sat * (1 - whiteness) * 100,
    whiteness * edge * level * 100
  );
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
function considerCell(ix, iy, lx, ly) {
  var d = curve.cellArc[iy * gridN + ix];
  var best = 1e18;
  var arc = d;

  if (d < curve.count - 1) {
    var nx = curve.nextX[d];
    var ny = curve.nextY[d];
    var tn = clamp(lx * nx + ly * ny, 0, 0.5);
    var ex = lx - tn * nx;
    var ey = ly - tn * ny;
    best = ex * ex + ey * ey;
    arc = d + tn;
  }

  if (d > 0) {
    var px = curve.prevX[d];
    var py = curve.prevY[d];
    var tp = clamp(lx * px + ly * py, 0, 0.5);
    var fx = lx - tp * px;
    var fy = ly - tp * py;
    var dist2 = fx * fx + fy * fy;
    if (dist2 < best) {
      best = dist2;
      arc = d - tp;
    }
  }

  if (best < bestDist2) {
    bestDist2 = best;
    bestArc = arc;
  }
}

/** Builds — or returns the cached — tables for a Hilbert curve of this order. */
function table(curveOrder) {
  var cached = tables[curveOrder];
  if (cached) {
    return cached;
  }

  var n = 1 << curveOrder;
  var count = n * n;
  var cellX = [];
  var cellY = [];
  var cellArc = [];
  var nextX = [];
  var nextY = [];
  var prevX = [];
  var prevY = [];

  for (var d = 0; d < count; ++d) {
    hilbertPoint(n, d);
    cellX[d] = pointX;
    cellY[d] = pointY;
    cellArc[pointY * n + pointX] = d;
  }

  // Successive cells are always edge-adjacent, so each direction is a unit
  // axis vector and the half-segments stay axis-aligned.
  for (var i = 0; i < count; ++i) {
    nextX[i] = i + 1 < count ? cellX[i + 1] - cellX[i] : 0;
    nextY[i] = i + 1 < count ? cellY[i + 1] - cellY[i] : 0;
    prevX[i] = i > 0 ? cellX[i - 1] - cellX[i] : 0;
    prevY[i] = i > 0 ? cellY[i - 1] - cellY[i] : 0;
  }

  cached = {
    n: n,
    count: count,
    cellArc: cellArc,
    nextX: nextX,
    nextY: nextY,
    prevX: prevX,
    prevY: prevY
  };
  tables[curveOrder] = cached;
  return cached;
}

/**
 * Grid cell visited at arc index d, left in pointX / pointY.
 *
 * The standard construction: read d two bits at a time from the coarsest
 * quadrant down, and reflect the accumulated position at each level to match
 * the orientation that quadrant's sub-curve is drawn in.
 */
function hilbertPoint(n, d) {
  var t = d;
  var x = 0;
  var y = 0;
  for (var s = 1; s < n; s *= 2) {
    var rx = 1 & Math.floor(t / 2);
    var ry = 1 & (t ^ rx);
    if (ry === 0) {
      if (rx === 1) {
        x = s - 1 - x;
        y = s - 1 - y;
      }
      var swap = x;
      x = y;
      y = swap;
    }
    x += s * rx;
    y += s * ry;
    t = Math.floor(t / 4);
  }
  pointX = x;
  pointY = y;
}

function wrap01(value) {
  return value - Math.floor(value);
}

function clampInt(value, low, high) {
  return value < low ? low : (value > high ? high : value);
}
