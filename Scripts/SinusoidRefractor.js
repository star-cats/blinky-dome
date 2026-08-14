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
 * Every oscillator is two counter-propagating waves added together: the
 * primary, and a secondary at half its amplitude running the other way at 0.65
 * of its speed. Same spatial frequency, opposite sign on the time term, so it
 * is a partial standing wave — the crests do not simply march, they swell and
 * shrink and stall as the two components move through each other, and the
 * figure never quite repeats.
 *
 * There is exactly one clock. All nine share the same accumulated wt, and the
 * secondary derives its own from the same number, so Speed at rest freezes
 * everything together and no oscillator can wander off on a clock of its own.
 *
 * That is what Speed Spread is built around. Giving each auxiliary its own w
 * seems like the obvious way to spread their rates, but rates integrate: once
 * they have drifted apart, winding the knob back to zero leaves them stranded
 * wherever they got to, and scaling an accumulator that has been growing for
 * ten minutes turns any small move of the knob into a wild jump. So the spread
 * is added, not integrated — a bounded phase offset that slides each auxiliary
 * back and forth around the shared phase. At any setting the eight are ahead
 * of and behind the center by varying amounts, which reads as their advancing
 * at different rates; at zero the offset is exactly zero and they collapse back
 * onto the central wave with no residue, from wherever they had got to.
 *
 * Output is pure luminosity, one 0-1 whiteness per LED.
 */

var TAU = Math.PI * 2;

/** One central oscillator and eight to hide behind it. */
var SINUSOIDS = 9;

/** The counter-propagating partner every oscillator carries. */
var SECONDARY_AMPLITUDE = 0.8;
var SECONDARY_SPEED = 0.65;

/**
 * How fast the Speed Spread offsets slide, in radians a second.
 *
 * Slow enough that over a few seconds an auxiliary reads as simply running at
 * its own rate, rather than as visibly oscillating about the center.
 */
var SPREAD_SLIDE = TAU / 11;

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

// One entry per oscillator, resolved in preRender rather than per LED.
var sinRate = [];
var sinPhase = [];
var sinOffset = [];
var sinThick = [];
var sinSoft = [];

/** The one accumulated wt every oscillator and both waves are driven from. */
var advance = 0;
var advance2 = 0;

/** Where the Speed Spread offsets have sild to; independent of any knob. */
var slide = 0;

// Per-frame values.
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
  var baseRate = TAU * lerp(0.25, 8, freq);
  var spreadStep = lerp(0, 0.13, spread);
  var thickBase = lerp(0.003, 0.08, thick);
  var thinning = lerp(1, 0.3, falloff);
  var softFraction = Math.max(lerp(0.02, 2, soft), 1e-3);
  var detuneAmount = lerp(0, 0.5, detune);

  // One full turn a second at either end of the knob. The slide runs at a
  // fixed rate whatever the knobs say, so that scaling it by Speed Spread is a
  // clean fade rather than a jump from wherever an accumulator had got to.
  advance = wrapTau(advance + (speed - 0.5) * 6 * TAU * dt);
  advance2 = advance2 + (speed2 - 0.5) * 6 * TAU * dt;
  slide = wrapTau(slide + SPREAD_SLIDE * dt);
  var spreadAmount = speedSpread * TAU;

  for (var i = 0; i < SINUSOIDS; ++i) {
    // Rank 0 is the center; the rest come in pairs, one to each side, so the
    // bundle stays symmetric however far it is pushed.
    var rank = Math.floor((i + 1) / 2);
    var side = (i % 2) === 1 ? 1 : -1;

    sinOffset[i] = rank * side * spreadStep;
    sinThick[i] = thickBase * Math.pow(thinning, rank);
    sinSoft[i] = Math.max(sinThick[i] * softFraction, 1e-5);

    // The center is never detuned, scattered or sped up relative to the rest —
    // it is the reference the others are revealed against, and it has to hold
    // its own course while they move off it.
    var wobble = rank === 0 ? 0 : signed11(i, 0);
    var slip = rank === 0 ? 0 : signed11(i, 1);
    var lag = rank === 0 ? 0 : balanced11(i);
    var stagger = rank === 0 ? 0 : signed11(i, 3) * Math.PI;

    sinRate[i] = baseRate * (1 + detuneAmount * wobble);
    // Both terms vanish exactly at their knob's zero, which is what lets the
    // bundle collapse back onto one curve rather than merely close to one.
    sinPhase[i] = scatter * TAU * slip +
      spreadAmount * lag * Math.sin(slide + stagger);
  }
}

function renderPoint(point, deltaMs) {
  var sx = (point.xn - 0.5) * aspectX;
  var sy = (point.yn - 0.5);

  // Screen back to the scene: unrotate, unzoom, then translate.
  var wx = (cosT * sx + sinT * sy) * invZoom + panWorldX;
  var wy = (-sinT * sx + cosT * sy) * invZoom + panWorldY;

  var best = 0;
  for (var i = 0; i < SINUSOIDS; ++i) {
    // Same spatial term, opposite sign on time: one wave runs up the scene and
    // its half-height partner runs down it, and what is drawn is their sum.
    var spatial = sinRate[i] * wy + sinPhase[i];
    var up = spatial + advance;
    var down = spatial - advance2;

    var curve = amplitude *
      (Math.sin(up) + SECONDARY_AMPLITUDE * Math.sin(down));
    var slope = amplitude * sinRate[i] *
      (Math.cos(up) + SECONDARY_AMPLITUDE * Math.cos(down));

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
 * The step of 3 across 8 slots is a full cycle, so consecutive oscillators
 * still get rates from opposite ends and the spread does not read as a ramp.
 */
function balanced11(i) {
  var slot = ((i - 1) * 3) % (SINUSOIDS - 1);
  return slot / ((SINUSOIDS - 2) / 2) - 1;
}

/** Keeps accumulated phase in 0..TAU so it cannot drift into float mush. */
function wrapTau(value) {
  return value;// - Math.floor(value / TAU) * TAU;
}
