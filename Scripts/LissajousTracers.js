/**
 * Three independently moving Lissajous tracers.
 *
 * Each head follows independently integrated X and Y oscillator phases:
 *
 *   phaseX += Kx * speed * dt
 *   phaseY += Ky * speed * dt
 *   x = R * sin(phaseX)
 *   y = R * sin(phaseY)
 *
 * and carries a finite piece of that curve behind it. Speed advances each
 * tracer's own clock, R sets its radius, and integer Kx / Ky values determine
 * the closed Lissajous figure. Integrating each oscillator is important: a K
 * change alters its velocity without multiplying its entire history and
 * teleporting the tracer head. The three clocks start at different phases so
 * identical settings still produce three visible tracers rather than one.
 *
 * A trail is sampled into a polyline once per frame. While finding the nearest
 * point on that polyline, the stroke radius is interpolated from the shared
 * Thickness at the head to exactly zero at the oldest point. Luma and alpha
 * fade together along that same age coordinate, from 1 at the live tip to 0 at
 * the tail, including transparent black away from every trace.
 */

var TAU = Math.PI * 2;
var TRACERS = 3;
var SEGMENTS = 96;
var POINTS_PER_TRACER = SEGMENTS + 1;
var PHASE_RESET = 10000 * TAU;

knob("speed1", "Speed 1", "Tracer 1 speed; 0 is still, 1 is six-sevenths of a cycle per second", 0.2);
knob("r1", "R 1", "Tracer 1 radius", 0.88);
knob("kx1", "Kx 1", "Tracer 1 horizontal frequency, from 1 to 6", 0.4);
knob("ky1", "Ky 1", "Tracer 1 vertical frequency, from 1 to 6", 0.2);

knob("speed2", "Speed 2", "Tracer 2 speed; 0 is still, 1 is six-sevenths of a cycle per second", 0.14);
knob("r2", "R 2", "Tracer 2 radius", 0.67);
knob("kx2", "Kx 2", "Tracer 2 horizontal frequency, from 1 to 6", 0.8);
knob("ky2", "Ky 2", "Tracer 2 vertical frequency, from 1 to 6", 0.6);

knob("speed3", "Speed 3", "Tracer 3 speed; 0 is still, 1 is six-sevenths of a cycle per second", 0.25);
knob("r3", "R 3", "Tracer 3 radius", 0.5);
knob("kx3", "Kx 3", "Tracer 3 horizontal frequency, from 1 to 6", 0.2);
knob("ky3", "Ky 3", "Tracer 3 vertical frequency, from 1 to 6", 0.4);

knob("thickness", "Thickness", "Shared width at each tracer head", 0.3);
knob("traceLength", "Tracer Length", "Shared trail length; maximum is one complete parameter cycle", 0.55);

// Per-axis oscillator clocks. They are kept independent so changing one K does
// not move either coordinate immediately. The offsets keep equal parameter
// sets visually distinct.
var phaseX = [Math.PI / 2, Math.PI / 2 + TAU / 3, Math.PI / 2 + 2 * TAU / 3];
var phaseY = [0, TAU / 3, 2 * TAU / 3];

// Flat per-frame polyline tables. Keeping the sine work out of renderPoint is
// important: that function runs once for every LED.
var pathX = [];
var pathY = [];

var headRadius = 0.02;
var edgeSoftness = 0.0025;
var aspectX = 1;

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dt = isFinite(deltaMs) ? clampValue(deltaMs / 1000, 0, 0.25) : 0;

  // Radius and thickness are measured relative to model height. Correcting X
  // by the model aspect ratio keeps both quantities circular on wide models.
  aspectX = 1;
  if (model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  headRadius = lerpValue(0.0015, 0.0825, thickness);
  var phaseLength = TAU * lerpValue(0.03 / 7, 1.5 / 7, traceLength);

  buildTracer(0, speed1, r1, kx1, ky1, phaseLength, dt);
  buildTracer(1, speed2, r2, kx2, ky2, phaseLength, dt);
  buildTracer(2, speed3, r3, kx3, ky3, phaseLength, dt);
}

function buildTracer(index, speedKnob, radiusKnob, kxKnob, kyKnob, phaseLength, dt) {
  var radius = lerpValue(0.02, 0.48, radiusKnob);
  var kx = 1 + Math.floor(clampValue(kxKnob, 0, 1) * 5.999999);
  var ky = 1 + Math.floor(clampValue(kyKnob, 0, 1) * 5.999999);
  var base = index * POINTS_PER_TRACER;

  // Speed maps linearly from a true stop to 6/7 base cycles per second.
  // Each axis accumulates its own K-scaled advance, so changing K affects only
  // motion from this frame onward instead of reinterpreting elapsed time.
  var advance = clampValue(speedKnob, 0, 1) * (6 / 7) * TAU * dt;
  phaseX[index] += kx * advance;
  phaseY[index] += ky * advance;

  // Doubles can carry these phases for a very long time. Reduce them only
  // after 10,000 oscillator cycles, preserving the remainder so the reset is
  // exactly invisible to sin(). PHASE_RESET is an integer multiple of TAU.
  if (phaseX[index] >= PHASE_RESET) {
    phaseX[index] -= Math.floor(phaseX[index] / PHASE_RESET) * PHASE_RESET;
  }
  if (phaseY[index] >= PHASE_RESET) {
    phaseY[index] -= Math.floor(phaseY[index] / PHASE_RESET) * PHASE_RESET;
  }

  for (var i = 0; i <= SEGMENTS; ++i) {
    var age = i / SEGMENTS;
    var back = phaseLength * age;
    pathX[base + i] = radius * Math.sin(phaseX[index] - kx * back);
    pathY[base + i] = radius * Math.sin(phaseY[index] - ky * back);
  }
}

function renderPoint(point, deltaMs) {
  var x = (point.xn - 0.5) * aspectX;
  var y = point.yn - 0.5;
  var best = 0;

  for (var tracer = 0; tracer < TRACERS; ++tracer) {
    var base = tracer * POINTS_PER_TRACER;

    for (var i = 0; i < SEGMENTS; ++i) {
      var ax = pathX[base + i];
      var ay = pathY[base + i];
      var bx = pathX[base + i + 1];
      var by = pathY[base + i + 1];

      // A cheap segment box rejects almost all of the trail before projection.
      // The head radius is the largest possible width, so this cannot discard
      // a point that the tapered segment would cover.
      var reach = headRadius + edgeSoftness;
      if (x < Math.min(ax, bx) - reach || x > Math.max(ax, bx) + reach ||
          y < Math.min(ay, by) - reach || y > Math.max(ay, by) + reach) {
        continue;
      }

      var dx = bx - ax;
      var dy = by - ay;
      var length2 = dx * dx + dy * dy;
      var u = 0;
      if (length2 > 1e-12) {
        u = ((x - ax) * dx + (y - ay) * dy) / length2;
        u = clampValue(u, 0, 1);
      }

      var px = ax + dx * u;
      var py = ay + dy * u;
      var ex = x - px;
      var ey = y - py;
      var distance = Math.sqrt(ex * ex + ey * ey);

      // age is zero at the live head and one at the maximum trail length.
      // Consequently this radius reaches exactly zero at the final endpoint.
      var age = (i + u) / SEGMENTS;
      var radius = headRadius * (1 - age);
      var coverage = (radius - distance) / edgeSoftness + 0.5;
      coverage = clampValue(coverage, 0, 1);
      // A zero-radius tail must also have zero alpha. This factor only affects
      // the final sub-pixel part of the taper, where a fully covered core can
      // no longer physically fit inside the anti-aliased stroke.
      coverage *= clampValue(radius / (edgeSoftness * 0.5), 0, 1);
      // Luma and alpha share this linear head-to-tail envelope. Multiplying by
      // edge coverage keeps the antialiasing identical in both channels too.
      var value = coverage * (1 - age);
      if (value > best) {
        best = value;
      }
    }
  }

  var value = Math.round(best * 255);
  return rgba(value, value, value, value);
}

function clampValue(value, low, high) {
  return Math.max(low, Math.min(high, value));
}

function lerpValue(a, b, amount) {
  return a + (b - a) * amount;
}
