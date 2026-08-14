/**
 * A vertical sinusoid with eight more hidden inside it, which fan out, detune
 * and scatter as you open the controls.
 *
 * All nine oscillators are always being drawn. At rest they are the same curve
 * exactly — same amplitude, same frequency, same phase, same place — and they
 * are drawn thinnest-last onto the thickest, so the outer eight sit entirely
 * within the central one's stroke and nothing betrays them. Spread pushes them
 * off it in alternating pairs, Detune walks their frequencies apart so they
 * open like a fan down the length, and Scatter breaks their phase lock. Any of
 * the three reveals the same eight curves in a different way, and backing all
 * three off collapses them back into one line rather than into a smear.
 *
 * Thickness falls geometrically with rank, which is what makes the hiding work:
 * a curve that is thinner than the one in front of it cannot peek out from
 * behind it. It also means a spread bundle reads with a clear center of mass
 * instead of nine equal ribbons.
 *
 * Distance to a sinusoid has no closed form, so each curve uses the linearized
 * distance: the horizontal gap to the curve, divided by the local slope's
 * hypotenuse. That is the perpendicular distance to the curve's tangent line,
 * which is what keeps a stroke from fattening up as the wave steepens — the
 * naive horizontal gap would draw a line that bulges at every zero crossing.
 * It is a first-order approximation and it tightens as strokes get thinner.
 *
 * Curves are combined by taking the brightest rather than by adding, so
 * crossings stay the same brightness as the strokes that make them instead of
 * blowing out to white.
 *
 * Every oscillator is two counter-propagating waves added together, each with
 * its own speed, so what is drawn is a partial standing wave — the crests do
 * not simply march, they swell and shrink and stall as the two components move
 * through each other, and the figure never quite repeats.
 *
 * Every oscillator also runs its own two clocks, integrating its own rates
 * every frame, at full deviation, always — whatever the knobs say. Nothing is
 * ever scaled on its way into an accumulator.
 *
 * The knobs are pure linear interpolants, applied at the last moment, in the
 * render loop. For each oscillator there are two versions of every quantity:
 * the fully deviated one and the central one. Spread, Detune, Scatter and
 * Speed Spread each just crossfade between them:
 *
 *   rate     = lerp(center rate,     own detuned rate,  Detune)
 *   phase    = lerp(0,               own scatter phase, Scatter)
 *   advance  = lerp(center advance,  own advance,       Speed Spread)
 *   offset   = lerp(0,               own offset,        Spread)
 *
 * At zero every one of those returns the center's own value identically, so the
 * bundle is not approximately collapsed, it is the same nine copies of one
 * curve. There is no state to unwind, because no knob ever wrote to state.
 *
 * This is also why nothing here is wrapped into 0..TAU. A wrapped accumulator
 * cannot be interpolated — the moment one oscillator's phase wraps past its
 * neighbour's, the lerp between them runs backwards through the whole cycle
 * instead of along the short way, and the bundle tears. Doubles hold radians
 * for weeks at these rates; a wrap would buy nothing and cost the interpolant.
 *
 * Output is pure luminosity, one 0-1 whiteness per LED.
 */

var TAU = Math.PI * 2;

/** One central oscillator and eight to hide behind it. */
var SINUSOIDS = 9;

/** The counter-propagating partner every oscillator carries. */
var SECONDARY_AMPLITUDE = 0.8;

/** Deviations at full knob: rate difference in rad/s, and the rest as factors. */
var SPEED_SPREAD_RATE = TAU;
var DETUNE_MAX = 0.5;
var SCATTER_MAX = TAU;
var SPREAD_MAX = 0.13;

knob("amp", "Amplitude", "How far the central sinusoid swings", 0.3);
knob("freq", "Frequency", "Cycles down the height of the scene", 0.25);

knob("spread", "Spread", "Pushes the other eight out from the center in pairs", 0);
knob("detune", "Detune", "Walks the others' frequencies away from the center's", 0);
knob("scatter", "Scatter", "Breaks the others' phase lock; 0 is perfectly in sync", 0);

knob("thick", "Thickness", "Stroke width of the central sinusoid", 0.25);
knob("falloff", "Falloff", "How fast the outer strokes thin; low keeps them near-equal", 0.5);
knob("soft", "Soft", "Edge softness, as a fraction of each stroke's own width", 0.3);

knob("speed", "Speed", "How fast every sinusoid advances; 0.5 is still", 0.5);
knob("speed2", "Speed2", "How fast countermotion is", 0.5);
knob("speedSpread", "Speed Spread", "Spreads the advance rate across the other eight", 0);

knob("zoom", "Zoom", "Scale of the scene; center is 1x, ends are 1/4x and 4x", 0.5);
knob("rot", "Rotate", "Rotation of the scene, a full turn across the knob", 0);
knob("panx", "Pan X", "Horizontal translation", 0.5);
knob("pany", "Pan Y", "Vertical translation", 0.5);

knob("level", "Level", "Overall brightness", 1);

toggle("autoAspect", "Aspect", "Keep the scene square on a non-square model", true);

// One entry per oscillator, resolved in preRender rather than per LED. Every
// one of these is the FULLY deviated version; the knobs interpolate towards
// them from the center's values, in renderPoint.
var sinRate = [];
var sinPhase = [];
var sinOffset = [];
var sinThick = [];
var sinSoft = [];

// Each oscillator's own two clocks, integrated at its own full-deviation rates
// every frame regardless of any knob. Index 0 is the center, and doubles as the
// aligned reference the others are interpolated back towards.
var sinAdvance = [];
var sinAdvance2 = [];
for (var n = 0; n < SINUSOIDS; ++n) {
  sinAdvance[n] = 0;
  sinAdvance2[n] = 0;
}

// Per-frame values.
var centerRate = 0;
var amplitude = 0;
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

  amplitude = lerp(0, 0.5, amp);
  centerRate = TAU * lerp(0.25, 8, freq);
  var thickBase = lerp(0.003, 0.08, thick);
  var thinning = lerp(1, 0.3, falloff);
  var softFraction = Math.max(lerp(0.02, 2, soft), 1e-3);

  // The center's two rates. Every oscillator's own rates are these plus its own
  // full-deviation offset, and every clock runs at its own rate always — no
  // knob is allowed near an accumulator, or winding it back could not undo it.
  var centerOmega = (speed - 0.5) * 6 * TAU;
  var centerOmega2 = (speed2 - 0.5) * 6 * TAU;

  for (var i = 0; i < SINUSOIDS; ++i) {
    // Rank 0 is the center; the rest come in pairs, one to each side, so the
    // bundle stays symmetric however far it is pushed.
    var rank = Math.floor((i + 1) / 2);
    var side = (i % 2) === 1 ? 1 : -1;

    sinThick[i] = thickBase * Math.pow(thinning, rank);
    sinSoft[i] = Math.max(sinThick[i] * softFraction, 1e-5);

    // The center deviates from itself by nothing, on every axis, so it is
    // unmoved by all four knobs and the others always have something to
    // return to.
    var wobble = rank === 0 ? 0 : signed11(i, 0);
    var slip = rank === 0 ? 0 : signed11(i, 1);
    var lag = rank === 0 ? 0 : balanced11(i, 3);
    var lag2 = rank === 0 ? 0 : balanced11(i, 5);

    sinOffset[i] = rank * side * SPREAD_MAX;
    sinRate[i] = centerRate * (1 + DETUNE_MAX * wobble);
    sinPhase[i] = SCATTER_MAX * slip;

    sinAdvance[i] += (centerOmega + SPEED_SPREAD_RATE * lag) * dt;
    sinAdvance2[i] += (centerOmega2 + SPEED_SPREAD_RATE * lag2) * dt;
  }
}

function renderPoint(point, deltaMs) {
  var sx = (point.xn - 0.5) * aspectX;
  var sy = (point.yn - 0.5);

  // Screen back to the scene: unrotate, unzoom, then translate.
  var wx = (cosT * sx + sinT * sy) * invZoom + panWorldX;
  var wy = (-sinT * sx + cosT * sy) * invZoom + panWorldY;

  // The center's own state is the far end of every interpolation below.
  var homeRate = centerRate;
  var homeAdvance = sinAdvance[0];
  var homeAdvance2 = sinAdvance2[0];

  var best = 0;
  for (var i = 0; i < SINUSOIDS; ++i) {
    // Every knob is a straight lerp from the center's value to this
    // oscillator's fully deviated one. At zero each returns the center's value
    // exactly, which is what makes the bundle collapse rather than nearly so.
    var rate = homeRate + (sinRate[i] - homeRate) * detune;
    var phase = sinPhase[i] * scatter;
    var adv = homeAdvance + (sinAdvance[i] - homeAdvance) * speedSpread;
    var adv2 = homeAdvance2 + (sinAdvance2[i] - homeAdvance2) * speedSpread;
    var offset = sinOffset[i] * spread;

    // Same spatial term, opposite sign on time: one wave runs up the scene and
    // its partner runs down it, and what is drawn is their sum.
    var spatial = rate * wy + phase;
    var up = spatial + adv;
    var down = spatial - adv2;

    var curve = amplitude *
      (Math.sin(up) + SECONDARY_AMPLITUDE * Math.sin(down));
    var slope = amplitude * rate *
      (Math.cos(up) + SECONDARY_AMPLITUDE * Math.cos(down));

    // Perpendicular distance to the tangent, then measured against this
    // curve's own offset so a pushed-out copy is a true parallel of it.
    var away = (wx - curve) / Math.sqrt(1 + slope * slope) - offset;
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
