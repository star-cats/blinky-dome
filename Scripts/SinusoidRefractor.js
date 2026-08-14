/**
 * A vertical sinusoid with eight more hidden inside it, which fan out, detune
 * and scatter as Spread opens.
 *
 * Everything is written in the wave's own frame, where a sinusoid is just
 *
 *   y = a1*sin(k1*x + phase1) + a2*sin(k2*x + phase2)
 *
 * and nothing else. There is no vertical special case and no rotation inside
 * that function, because the LED's coordinates are transformed by the inverse
 * of the scene rotation before it is ever called — the scene turns by turning
 * the points the other way, and what the curve sees is always a plain x running
 * along it and a plain y across it. A vertical sinusoid is that same canonical
 * curve looked at sideways, which is the last quarter turn of the transform.
 *
 * Two waves, not one: the second runs the other way, so what is drawn is a
 * partial standing wave whose crests swell and stall rather than simply march.
 *
 * Each oscillator owns its parameters, and its own two clocks, which integrate
 * its own rates every frame at full deviation, always. Detune sets its k,
 * Scatter its phases, Speed Spread its rates. None of them is scaled on its way
 * into anything — they define what this oscillator IS.
 *
 * Spread alone decides how much of that is seen. Every oscillator evaluates
 * twice per LED, once with the central oscillator's parameters and once with
 * its own, and Spread interpolates linearly between the two displacements and
 * between zero and its own lateral offset. At Spread 0 every one of the nine
 * evaluates to the central curve exactly — not nearly, identically — whatever
 * the other knobs say and however long they have been running. There is nothing
 * to unwind, because the deviation was never accumulated into anything: it is
 * recomputed from scratch every frame and multiplied by the knob at the end.
 *
 * Nothing is wrapped into 0..TAU. A wrapped phase cannot be interpolated — the
 * moment one oscillator wraps past another the lerp runs backwards through the
 * whole cycle — and doubles hold radians for weeks at these rates anyway.
 *
 * Thickness falls geometrically with rank, which is what makes the hiding work:
 * a curve thinner than the one in front of it cannot peek out from behind it.
 *
 * Distance to a sinusoid has no closed form, so each curve uses the linearized
 * distance: the gap in y, divided by the local slope's hypotenuse. That is the
 * perpendicular distance to the tangent, which keeps a stroke from fattening up
 * where the wave steepens. Curves are combined by taking the brightest, so
 * crossings stay stroke-brightness instead of blowing out.
 *
 * Output is pure luminosity, one 0-1 whiteness per LED.
 */

var TAU = Math.PI * 2;

/** One central oscillator and eight to hide behind it. */
var SINUSOIDS = 9;

/** The counter-running partner every oscillator carries, relative to a1. */
var SECONDARY_AMPLITUDE = 0.8;

/** Deviation of an outermost oscillator at full knob. */
var SPEED_SPREAD_RATE = TAU;
var DETUNE_MAX = 0.5;
var SCATTER_MAX = TAU;
var SPREAD_MAX = 0.13;

knob("amp", "Amplitude", "How far the central sinusoid swings", 0.3);
knob("freq", "Frequency", "Cycles down the height of the scene", 0.25);

knob("spread", "Spread", "Reveals the other eight; 0 collapses them onto the center", 0);
knob("detune", "Detune", "How far the others' frequencies sit from the center's", 0);
knob("scatter", "Scatter", "How far the others' phases sit from the center's", 0);
knob("speedSpread", "Speed Spread", "How far the others' advance rates sit from the center's", 0);

knob("thick", "Thickness", "Stroke width of the central sinusoid", 0.25);
knob("falloff", "Falloff", "How fast the outer strokes thin; low keeps them near-equal", 0.5);
knob("soft", "Soft", "Edge softness, as a fraction of each stroke's own width", 0.3);

knob("speed", "Speed", "How fast the wave advances; 0.5 is still", 0.5);
knob("speed2", "Speed2", "How fast the counter-running wave advances; 0.5 is still", 0.5);

knob("zoom", "Zoom", "Scale of the scene; center is 1x, ends are 1/4x and 4x", 0.5);
knob("rot", "Rotate", "Rotation of the scene, a full turn across the knob", 0);
knob("panx", "Pan X", "Horizontal translation", 0.5);
knob("pany", "Pan Y", "Vertical translation", 0.5);

knob("level", "Level", "Overall brightness", 1);

toggle("autoAspect", "Aspect", "Keep the scene square on a non-square model", true);

// One oscillator's worth of curve parameters per entry, at full deviation.
// Index 0 is the central oscillator, which deviates from itself by nothing and
// is therefore also the reference every other one interpolates back towards.
var sinK = [];
var sinPhase1 = [];
var sinPhase2 = [];
var sinOffset = [];
var sinThick = [];
var sinSoft = [];

// Each oscillator's own two clocks, integrated at its own rates every frame
// whatever the knobs say. Never scaled, never wrapped.
var sinAdvance1 = [];
var sinAdvance2 = [];
for (var n = 0; n < SINUSOIDS; ++n) {
  sinAdvance1[n] = 0;
  sinAdvance2[n] = 0;
}

// Per-frame values.
var amp1 = 0;
var amp2 = 0;
var cosT = 1;
var sinT = 0;
var invZoom = 1;
var panWorldX = 0;
var panWorldY = 0;
var aspectX = 1;

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;

  var angle = rot * TAU;
  cosT = Math.cos(angle);
  sinT = Math.sin(angle);
  invZoom = 1 / Math.pow(2, (zoom - 0.5) * 4);
  panWorldX = (panx - 0.5) * 2;
  panWorldY = (pany - 0.5) * 2;

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  amp1 = lerp(0, 0.5, amp);
  amp2 = amp1 * SECONDARY_AMPLITUDE;

  var centerK = TAU * lerp(0.25, 8, freq);
  var centerOmega1 = (speed - 0.5) * 6 * TAU;
  var centerOmega2 = (speed2 - 0.5) * 6 * TAU;

  var thickBase = lerp(0.003, 0.08, thick);
  var thinning = lerp(1, 0.3, falloff);
  var softFraction = Math.max(lerp(0.02, 2, soft), 1e-3);

  for (var i = 0; i < SINUSOIDS; ++i) {
    // Rank 0 is the center; the rest come in pairs, one to each side, so the
    // bundle stays symmetric however far it is pushed.
    var rank = Math.floor((i + 1) / 2);
    var side = (i % 2) === 1 ? 1 : -1;

    sinThick[i] = thickBase * Math.pow(thinning, rank);
    sinSoft[i] = Math.max(sinThick[i] * softFraction, 1e-5);

    // The center deviates from itself by nothing on every axis, so it is fixed
    // under all four knobs and the others always have something to return to.
    var wobble = rank === 0 ? 0 : signed11(i, 0);
    var slip = rank === 0 ? 0 : signed11(i, 1);
    var lag1 = rank === 0 ? 0 : balanced11(i, 3);
    var lag2 = rank === 0 ? 0 : balanced11(i, 5);

    sinAdvance1[i] += (centerOmega1 + SPEED_SPREAD_RATE * lag1) * dt;
    sinAdvance2[i] += (centerOmega2 + SPEED_SPREAD_RATE * lag2) * dt;

    sinK[i] = centerK * (1 + DETUNE_MAX * detune * wobble);
    sinOffset[i] = rank * side * SPREAD_MAX;
    // The second wave's phase runs down by the same convention the first runs
    // up, which is what makes the pair counter-propagate.
    sinPhase1[i] = sinAdvance1[i] + SCATTER_MAX * scatter * slip;
    sinPhase2[i] = -sinAdvance2[i] + SCATTER_MAX * scatter * slip;
  }
}

/** y = a1*sin(k1*x + phase1) + a2*sin(k2*x + phase2). Nothing more. */
function waveY(x, a1, k1, phase1, a2, k2, phase2) {
  return a1 * Math.sin(k1 * x + phase1) + a2 * Math.sin(k2 * x + phase2);
}

/** Its slope, dy/dx, at the same x. */
function waveSlope(x, a1, k1, phase1, a2, k2, phase2) {
  return a1 * k1 * Math.cos(k1 * x + phase1) + a2 * k2 * Math.cos(k2 * x + phase2);
}

function renderPoint(point, deltaMs) {
  var sx = (point.xn - 0.5) * aspectX;
  var sy = (point.yn - 0.5);

  // Undo the scene transform: rotate the point by the inverse of the scene's
  // rotation, unzoom it, and shift it by the pan.
  var rx = (cosT * sx + sinT * sy) * invZoom + panWorldX;
  var ry = (-sinT * sx + cosT * sy) * invZoom + panWorldY;

  // Then the last quarter turn into the wave's own frame: x runs along the
  // curve, y across it. This is the only thing making the sinusoid vertical.
  var x = ry;
  var y = rx;

  // The central oscillator's curve, which every oscillator is interpolated
  // back towards, and which is the same for all nine.
  var centerY = waveY(x, amp1, sinK[0], sinPhase1[0], amp2, sinK[0], sinPhase2[0]);
  var centerSlope = waveSlope(x, amp1, sinK[0], sinPhase1[0], amp2, sinK[0], sinPhase2[0]);

  var best = 0;
  for (var i = 0; i < SINUSOIDS; ++i) {
    // This oscillator as it would be if it were fully revealed.
    var targetY = waveY(x, amp1, sinK[i], sinPhase1[i], amp2, sinK[i], sinPhase2[i]) +
      sinOffset[i];
    var targetSlope = waveSlope(x, amp1, sinK[i], sinPhase1[i], amp2, sinK[i], sinPhase2[i]);

    // Spread is the whole reveal: a straight lerp from the center's curve to
    // this one's. At 0 it is the center's curve exactly, for every oscillator.
    var curveY = centerY + (targetY - centerY) * spread;
    var slope = centerSlope + (targetSlope - centerSlope) * spread;

    // Perpendicular distance to the tangent rather than the gap in y, so the
    // stroke keeps its width where the wave steepens.
    var away = (y - curveY) / Math.sqrt(1 + slope * slope);
    if (away < 0) {
      away = -away;
    }

    var edge = sinThick[i] + sinSoft[i];
    if (away >= edge) {
      continue;
    }
    var value = (edge - away) / sinSoft[i];
    if (value > 1) {
      value = 1;
    }
    if (value > best) {
      best = value;
    }
  }

  return hsb(0, 0, best * level * 100);
}

/** Deterministic, decorrelated -1..1 value for an oscillator and a salt. */
function signed11(i, salt) {
  var s = Math.sin(i * 127.1 + salt * 311.7) * 43758.5453;
  return (s - Math.floor(s)) * 2 - 1;
}

/**
 * Deterministic -1..1 value for one of the eight, from a set that is balanced
 * by construction: evenly spaced across the range, summing to zero.
 *
 * The hash used for detune and scatter is fine for an offset that only has to
 * look irregular, but it cannot be trusted to straddle zero over a sample of
 * eight — for these indices it lands in -0.03..0.71, all but one on the same
 * side. That is invisible in a phase offset and ruinous in a rate: the whole
 * bundle would creep one way instead of splitting around a stationary center.
 * The step walks the slots in a full cycle, so consecutive oscillators get
 * rates from opposite ends and the spread does not read as a ramp. Any step
 * coprime with the eight slots works, which is how the two waves get different
 * orderings from the same balanced set.
 */
function balanced11(i, step) {
  var slot = ((i - 1) * step) % (SINUSOIDS - 1);
  return slot / ((SINUSOIDS - 2) / 2) - 1;
}
