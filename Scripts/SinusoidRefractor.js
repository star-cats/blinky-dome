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
 * The luminosity wave runs along the length, in scene units, so it stays put on
 * the curve as the wave steepens and travels at a constant rate regardless of
 * its own wavelength. Output is pure luminosity, one 0-1 whiteness per LED.
 */

var TAU = Math.PI * 2;

/** One central oscillator and eight to hide behind it. */
var SINUSOIDS = 9;

knob("amp", "Amplitude", "How far the central sinusoid swings", 0.3);
knob("freq", "Frequency", "Cycles down the height of the scene", 0.25);

knob("spread", "Spread", "Pushes the other eight out from the center in pairs", 0);
knob("detune", "Detune", "Walks the others' frequencies away from the center's", 0);
knob("scatter", "Scatter", "Breaks the others' phase lock; 0 is perfectly in sync", 0);

knob("thick", "Thickness", "Stroke width of the central sinusoid", 0.25);
knob("falloff", "Falloff", "How fast the outer strokes thin; low keeps them near-equal", 0.5);
knob("soft", "Soft", "Edge softness, as a fraction of each stroke's own width", 0.3);

knob("waveLen", "Wave", "Length of one dark-to-light ramp along the curve", 0.45);
knob("waveSpeed", "Wave Speed", "Travel along the curve; 0.5 is still, below runs down", 0.65);
knob("waveDepth", "Wave Depth", "How much the wave darkens the curve; 0 is off", 0.85);

knob("zoom", "Zoom", "Scale of the scene; center is 1x, ends are 1/4x and 4x", 0.5);
knob("rot", "Rotate", "Rotation of the scene, a full turn across the knob", 0);
knob("panx", "Pan X", "Horizontal translation", 0.5);
knob("pany", "Pan Y", "Vertical translation", 0.5);

knob("level", "Level", "Overall brightness", 1);

toggle("autoAspect", "Aspect", "Keep the scene square on a non-square model", true);

// One entry per oscillator, resolved in preRender rather than per LED.
var sinRate = [];
var sinPhase = [];
var sinOffset = [];
var sinThick = [];
var sinSoft = [];

// Per-frame values.
var amplitude = 0;
var cosT = 1;
var sinT = 0;
var invZoom = 1;
var panWorldX = 0;
var panWorldY = 0;
var aspectX = 1;
var waveLength = 1;
var wavePhase = 0;
var waveAmount = 1;

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
  var baseRate = TAU * lerp(0.25, 8, freq);
  var spreadStep = lerp(0, 0.13, spread);
  var thickBase = lerp(0.003, 0.08, thick);
  var thinning = lerp(1, 0.3, falloff);
  var softFraction = Math.max(lerp(0.02, 2, soft), 1e-3);
  var detuneAmount = lerp(0, 0.5, detune);

  for (var i = 0; i < SINUSOIDS; ++i) {
    // Rank 0 is the center; the rest come in pairs, one to each side, so the
    // bundle stays symmetric however far it is pushed.
    var rank = Math.floor((i + 1) / 2);
    var side = (i % 2) === 1 ? 1 : -1;

    sinOffset[i] = rank * side * spreadStep;
    sinThick[i] = thickBase * Math.pow(thinning, rank);
    sinSoft[i] = Math.max(sinThick[i] * softFraction, 1e-5);

    // The center is never detuned or scattered — it is the reference the others
    // are revealed against, and it has to hold still while they move.
    var wobble = rank === 0 ? 0 : signed11(i, 0);
    var slip = rank === 0 ? 0 : signed11(i, 1);
    sinRate[i] = baseRate * (1 + detuneAmount * wobble);
    sinPhase[i] = scatter * Math.PI * slip;
  }

  // Wavelength and phase both live in scene units, so the wave travels at a
  // fixed rate whatever its length, and changing its length does not fling it
  // down the curve. Kept wrapped so it cannot drift into float mush.
  waveLength = lerp(0.04, 4, waveLen);
  waveAmount = waveDepth;
  wavePhase += dt * (waveSpeed - 0.5) * 2 * 0.55;
  wavePhase -= Math.floor(wavePhase / waveLength) * waveLength;
}

function renderPoint(point, deltaMs) {
  var sx = (point.xn - 0.5) * aspectX;
  var sy = (point.yn - 0.5);

  // Screen back to the scene: unrotate, unzoom, then translate.
  var wx = (cosT * sx + sinT * sy) * invZoom + panWorldX;
  var wy = (-sinT * sx + cosT * sy) * invZoom + panWorldY;

  var best = 0;
  for (var i = 0; i < SINUSOIDS; ++i) {
    var arg = sinRate[i] * wy + sinPhase[i];
    var curve = amplitude * Math.sin(arg);
    var slope = amplitude * sinRate[i] * Math.cos(arg);

    // Perpendicular distance to the tangent, then measured against this
    // curve's own offset so a pushed-out copy is a true parallel of it.
    var away = (wx - curve) / Math.sqrt(1 + slope * slope) - sinOffset[i];
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

  if (best <= 0) {
    return hsb(0, 0, 0);
  }

  // A sawtooth in distance along the curve: a linear climb out of the dark,
  // then a hard reset. Depth mixes it against a plain unmodulated stroke.
  var ramp = wrap01((wy - wavePhase) / waveLength);
  var wave = 1 - waveAmount + waveAmount * ramp;

  return hsb(0, 0, best * wave * level * 100);
}

/** Deterministic, decorrelated -1..1 value for an oscillator and a salt. */
function signed11(i, salt) {
  var s = Math.sin(i * 127.1 + salt * 311.7) * 43758.5453;
  return (s - Math.floor(s)) * 2 - 1;
}

function wrap01(value) {
  return value - Math.floor(value);
}
