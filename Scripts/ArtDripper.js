/**
 * Art Dripper
 *
 * Freezes what is coming into it and throws wet blobs at the top of the picture,
 * steadily and forever, letting each one run down. Every few seconds the paint
 * is wiped and a new snapshot is taken, but the throwing never stops.
 *
 * Drips arrive at a rate rather than in batches, which is what makes the wipe
 * and the emission independent: a drip in flight when the canvas is cleared
 * keeps going and keeps painting, so the picture empties and immediately starts
 * refilling instead of stuttering to a halt and restarting.
 *
 * The wipe is not decoration. Paint only ever accumulates — a texel's coverage
 * climbs toward 1 and never falls — so under continuous emission the canvas
 * saturates within a few seconds and the whole thing settles into a static
 * smear. Cycle is what keeps it alive, and it doubles as the refresh on the
 * snapshot the heads are drawing their color from.
 *
 * A drip is one blur head: a gaussian disc with a position, a velocity and a
 * color it is carrying. Three things happen to it as it falls.
 *
 *   it slows        velocity decays exponentially, v = v0 * exp(-t/tau), so the
 *                   head arrives fast, loses its momentum and asymptotically
 *                   stops. Total travel is exactly v0*tau, which is why Length
 *                   sets the distance and Decay only sets how abruptly it gets
 *                   there — a drip cannot overshoot the length it was given.
 *
 *   it picks up     the head samples the frozen snapshot underneath it and eases
 *                   its own color toward what it finds, so a drip crossing a
 *                   red field turns red and carries some of that red on into the
 *                   next field. The easing is per unit of distance travelled
 *                   rather than per frame, which keeps the pickup rate a
 *                   property of the path and not of the engine's frame rate —
 *                   and it means a halting head stops re-coloring itself, since
 *                   it has stopped covering ground.
 *
 *   it lays down    what it deposits is alpha, and the alpha is its speed. A
 *                   fast head grabs the texels it crosses; a slow one barely
 *                   tints them. So a drip is opaque where it was thrown and
 *                   fades out as it runs down, which is the whole shape of the
 *                   stroke.
 *
 * That last one is worth being explicit about because it is backwards from real
 * paint, where the slow end is where pigment pools and goes darkest. It is what
 * was asked for and it is the better-looking of the two here: it puts the mark
 * at the top where the blob landed and lets the tail dissolve, rather than
 * hanging a blot on the bottom of every run.
 *
 * Paint accumulates on a fixed-resolution canvas in normalized space, not on the
 * model's points. A drip has to be able to cross the picture smoothly whatever
 * the fixture looks like, and depositing straight onto points would make the
 * stroke's shape a function of where the LEDs happen to be. The canvas is
 * sampled bilinearly at render, so the model can be as sparse or as lumpy as it
 * likes and the drip still reads as a drip.
 *
 * Color is stored premultiplied — the accumulators hold color*coverage rather
 * than color — because that is the form in which repeated source-over deposits
 * compose correctly. Storing straight color would need a divide per deposit and
 * would drift wherever coverage was near zero.
 *
 * The snapshot is what the heads read, not what the effect shows: by default the
 * picture underneath goes on living and the paint runs over it. Freeze turns the
 * base into the snapshot too, which stops the whole image dead between splats
 * and is the more literal reading of "freeze".
 */

var ImageIO = Java.type("javax.imageio.ImageIO");
var IntArray = Java.type("int[]");
var FloatArray = Java.type("float[]");

/**
 * Resolution of both the paint canvas and the XY-to-model-point lookup.
 *
 * They share a grid deliberately: the head samples the snapshot and deposits
 * paint at the same place every step, and one resolution means one set of
 * coordinates rather than two that have to be kept in agreement.
 */
var GRID = 128;
var BIN_COUNT = 32;

/** Drips emitted per second, at either end of the Drip Rate knob. */
var RATE_MIN = 0.5;
var RATE_MAX = 16;

/**
 * Live drips the pool can hold.
 *
 * A drip occupies a slot for as long as it is falling plus the linger, which at
 * the slowest Decay and longest Length runs to several seconds. The ceiling is
 * sized so the top of the rate knob still fits at ordinary settings; past that
 * emitDrip finds nothing free and simply skips, so an extreme corner costs a
 * dropped drip rather than an evicted one that was still visibly running.
 */
var MAX_HEADS = 128;

/** How long a halted drip keeps its slot before it is reclaimed, in ms. */
var LINGER_MS = 1000;

/** A burst this size is the most a stalled frame may release at once. */
var EMIT_CATCHUP = 8;

/** Head radius, in frame heights, at either end of the Size knob. */
var RADIUS_MIN = 0.02;
var RADIUS_MAX = 0.16;

/** How far a drip runs before halting, in frame heights, across Length. */
var LENGTH_MIN = 0.10;
var LENGTH_MAX = 0.90;

/** The exponential's time constant, in seconds, across Decay. */
var TAU_MIN = 0.15;
var TAU_MAX = 1.60;

/**
 * The speed that counts as full adoption, in frame heights per second.
 *
 * Absolute rather than per-head, so that "high velocity means high adoption" is
 * a fact about the picture and not about each drip's own scale — a slow drip is
 * meant to read as a weak one next to a fast one, which it would not if every
 * head normalized against its own launch speed.
 */
var VELOCITY_REF = 1.5;

/**
 * Below this a head has stopped in any sense that matters.
 *
 * Two percent of a frame height per second is already imperceptible, and it is
 * deliberately not smaller: the exponential takes a logarithm of this to reach
 * zero, so dropping it an order of magnitude adds seconds to every drip's life
 * for motion nobody can see — and those seconds are slots the pool has to hold.
 */
var HALT_VELOCITY = 0.02;

/** Per-head spread on radius, length and tau, so a splat is not a set of clones. */
var VARIANCE = 0.4;

/** Gaussian sigma as a fraction of the head's radius, across Softness. */
var SIGMA_MIN = 0.18;
var SIGMA_MAX = 0.62;

/** Cap on sub-steps within one frame, so a stall cannot cost an unbounded loop. */
var MAX_SUBSTEPS = 12;

/** Fraction of a radius a head may move between deposits before it is split. */
var STEP_FRACTION = 0.35;

knob("rate", "Drip Rate", "Drips thrown per second, from 0.5 to 16", 0.3);
knob("size", "Size", "Radius of a drip's blur head", 0.35);
knob("soft", "Softness", "Width of the gaussian inside that radius", 0.5);

knob("length", "Length", "How far a drip runs before it halts", 0.45);
knob("decay", "Decay", "How abruptly it slows; low is a hard stop, high is a long coast", 0.4);

knob("flow", "Flow", "How much paint a drip lays down at speed", 0.8);
knob("pickup", "Pickup", "How quickly a head takes on the color it is crossing", 0.5);

knob("cycle", "Cycle", "Seconds before the paint is wiped and re-snapshotted, 1 to 8", 0.286);

knob("freeze", "Freeze", "Holds the snapshot as the base instead of the live input", 0);
knob("amount", "Amount", "Overall opacity of the paint", 1);

toggle("autoAspect", "Aspect", "Keep drip heads round on a non-square model", true);

trigger("splat", "Splat", "Wipe the paint and re-snapshot now", onSplat);

// ------------------------------------------------------------------ the canvas
//
// Premultiplied: canR/G/B hold color*coverage in 0..255*coverage, canA holds
// coverage in 0..1. Java float arrays rather than JS arrays — this is a hundred
// thousand cells written every frame and boxing them is not affordable.

var canR = null;
var canG = null;
var canB = null;
var canA = null;

// ---------------------------------------------------------------- the snapshot

var sourceColors = null;
var indexedModel = null;
var indexedPointCount = -1;
var binHead = [];
var pointNext = [];
var sourceLookup = null;
var nearestBestPoint = -1;
var nearestBestDistanceSq = Infinity;

// -------------------------------------------------------------------- the head
//
// A fixed pool of slots rather than a list, so emitting and retiring drips
// allocates nothing however long the effect runs. headActive says the slot is
// in use; headMoving says the drip in it is still falling. Parallel arrays for
// the same reason the canvas is a Java array: these are walked several times a
// frame and the flat form keeps the loop free of property lookups.

var headActive = [];
var headMoving = [];
var headLinger = [];
var headX = [];
var headY = [];
var headV0 = [];
var headTau = [];
var headT = [];
var headRadius = [];
var headR = [];
var headG = [];
var headB = [];
var liveHeads = 0;

// --------------------------------------------------------------- per-frame and
// cycle state

var cycleMs = 0;
var emitAccumulator = 0;
var pendingSplat = false;
var seeded = false;
var aspectX = 1;
var sigmaFraction = 0.4;
var pickupLength = 0.2;
var flowScale = 1;

// Scratch for the one snapshot sample a head takes per step. Returning an array
// would allocate a few thousand of them a second.
var outR = 0;
var outG = 0;
var outB = 0;

function onSplat() {
  // Spent in preRender so every state change happens in one place.
  pendingSplat = true;
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dtMs = isFinite(deltaMs) ? clamp(deltaMs, 0, 250) : 0;

  if (canR == null) {
    canR = new FloatArray(GRID * GRID);
    canG = new FloatArray(GRID * GRID);
    canB = new FloatArray(GRID * GRID);
    canA = new FloatArray(GRID * GRID);
  }

  if (sourceColors == null || sourceColors.length != colors.length) {
    sourceColors = new IntArray(colors.length);
  }

  if (indexedModel !== model || indexedPointCount != model.points.length) {
    buildSourceLookup(model);
    seeded = false;
  }

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  sigmaFraction = lerp(SIGMA_MIN, SIGMA_MAX, soft);
  // A pickup length in frame heights: the distance over which a head closes
  // most of the gap to whatever it is crossing. Short means it takes on local
  // color almost at once; long means it carries the field it started in.
  pickupLength = lerp(0.6, 0.02, pickup);
  flowScale = flow;

  cycleMs += dtMs;
  var cycleLen = lerp(1000, 8000, cycle);

  if (!seeded || pendingSplat || cycleMs >= cycleLen) {
    pendingSplat = false;
    wipeAndSnapshot(colors);
  }

  // Emission is a steady rate, independent of the wipe: a drip already falling
  // when the canvas is cleared keeps falling and keeps painting.
  emitAccumulator += (dtMs / 1000) * lerp(RATE_MIN, RATE_MAX, clamp(rate, 0, 1));
  if (emitAccumulator > EMIT_CATCHUP) {
    emitAccumulator = EMIT_CATCHUP;
  }
  while (emitAccumulator >= 1) {
    emitAccumulator -= 1;
    emitDrip();
  }

  advanceHeads(dtMs / 1000, dtMs);
}

/**
 * Wipe the paint and freeze a new snapshot.
 *
 * Drips already in flight are left alone. They belong to the emitter, not to the
 * cycle, and killing them here would put a gap in a stream that is meant to be
 * continuous.
 */
function wipeAndSnapshot(colors) {
  seeded = true;
  cycleMs = 0;

  for (var i = 0; i < colors.length; ++i) {
    sourceColors[i] = colors[i];
  }

  var cells = GRID * GRID;
  for (var c = 0; c < cells; ++c) {
    canR[c] = 0;
    canG[c] = 0;
    canB[c] = 0;
    canA[c] = 0;
  }
}

/**
 * Throw one drip, if there is a slot for it.
 *
 * A full pool drops the drip rather than evicting somebody: the oldest live head
 * is the one furthest down the picture and still laying paint, and taking its
 * slot would cut a stroke off in the middle of itself. Dropping one costs a drip
 * nobody was waiting for.
 */
function emitDrip() {
  if (sourceLookup == null) {
    return;
  }

  var k = -1;
  for (var i = 0; i < MAX_HEADS; ++i) {
    if (!headActive[i]) {
      k = i;
      break;
    }
  }
  if (k < 0) {
    return;
  }

  headX[k] = Math.random();
  // The upper half only: a drip has to have somewhere to run to.
  headY[k] = 0.5 + Math.random() * 0.5;

  headRadius[k] = lerp(RADIUS_MIN, RADIUS_MAX, size) * vary();
  var travel = lerp(LENGTH_MIN, LENGTH_MAX, length) * vary();
  var tau = lerp(TAU_MIN, TAU_MAX, decay) * vary();

  headTau[k] = tau;
  // Travel is v0*tau for this curve, so solving for v0 makes Length mean the
  // distance rather than merely correlating with it.
  headV0[k] = travel / tau;
  headT[k] = 0;

  headActive[k] = true;
  headMoving[k] = true;
  headLinger[k] = LINGER_MS;

  // The blob arrives already carrying the color it landed on, and marks the spot
  // at full strength: this is the splat, not part of the run.
  var radius = headRadius[k];
  sampleSnapshot(headX[k], headY[k]);
  headR[k] = outR;
  headG[k] = outG;
  headB[k] = outB;
  deposit(headX[k], headY[k], radius,
    Math.max(radius * sigmaFraction, 1e-4),
    outR, outG, outB, clamp(flowScale, 0, 1));
}

/** A per-head multiplier around 1, so a splat is a spread and not a set of clones. */
function vary() {
  return 1 + (Math.random() * 2 - 1) * VARIANCE;
}

function advanceHeads(dt, dtMs) {
  if (sourceLookup == null) {
    return;
  }

  liveHeads = 0;

  for (var k = 0; k < MAX_HEADS; ++k) {
    if (!headActive[k]) {
      continue;
    }
    ++liveHeads;

    // A halted drip keeps its slot for a moment before it is reclaimed. It
    // deposits nothing while it waits — adoption is its speed, and its speed is
    // gone — so this is bookkeeping rather than anything you can see.
    if (!headMoving[k]) {
      headLinger[k] -= dtMs;
      if (headLinger[k] <= 0) {
        headActive[k] = false;
      }
      continue;
    }

    var radius = headRadius[k];
    var sigma = Math.max(radius * sigmaFraction, 1e-4);

    var v0 = headV0[k];
    var tau = headTau[k];
    var t = headT[k];

    // How far this head travels this frame, in closed form, so the sub-step
    // count can be chosen before any of it is walked.
    var frameTravel = v0 * tau * (Math.exp(-t / tau) - Math.exp(-(t + dt) / tau));
    var steps = Math.ceil(frameTravel / Math.max(radius * STEP_FRACTION, 1e-5));
    if (steps < 1) {
      steps = 1;
    } else if (steps > MAX_SUBSTEPS) {
      steps = MAX_SUBSTEPS;
    }
    var dtSub = dt / steps;

    for (var s = 0; s < steps; ++s) {
      var v = v0 * Math.exp(-t / tau);
      if (v < HALT_VELOCITY) {
        // Stopped where it stands. The linger starts now.
        headMoving[k] = false;
        break;
      }

      var moved = v * dtSub;
      // Down the picture is toward yn = 0.
      headY[k] -= moved;
      t += dtSub;

      if (headY[k] < -radius) {
        // Gone off the bottom rather than stopped, so there is nothing to
        // linger over — the slot goes back immediately.
        headActive[k] = false;
        break;
      }

      // Pick up the color being crossed. Eased per unit of distance rather than
      // per frame, so the rate belongs to the path and not to the frame rate.
      sampleSnapshot(headX[k], headY[k]);
      var mix = 1 - Math.exp(-moved / pickupLength);
      headR[k] += (outR - headR[k]) * mix;
      headG[k] += (outG - headG[k]) * mix;
      headB[k] += (outB - headB[k]) * mix;

      // Alpha is speed. This is the stroke's whole falloff.
      var adoption = clamp(v / VELOCITY_REF, 0, 1) * flowScale;
      if (adoption > 0) {
        deposit(headX[k], headY[k], radius, sigma,
          headR[k], headG[k], headB[k], adoption);
      }
    }

    headT[k] = t;
  }
}

/**
 * Lay one gaussian stamp of paint onto the canvas.
 *
 * Source-over, premultiplied: coverage moves toward 1 and the color accumulator
 * moves toward the head's color, both weighted by the same per-cell alpha. The
 * radius is a hard cutoff on a curve that is already near zero there, which
 * keeps the cost bounded without a visible edge.
 */
function deposit(cx, cy, radius, sigma, r, g, b, alpha) {
  // Radius is in frame heights; on a wide model that is fewer units of xn than
  // of yn, which is what keeps the head round on the fixture rather than on the
  // normalized square.
  var radiusX = radius / aspectX;

  var x0 = Math.floor((cx - radiusX) * GRID);
  var x1 = Math.ceil((cx + radiusX) * GRID);
  var y0 = Math.floor((cy - radius) * GRID);
  var y1 = Math.ceil((cy + radius) * GRID);

  if (x0 < 0) { x0 = 0; }
  if (y0 < 0) { y0 = 0; }
  if (x1 > GRID - 1) { x1 = GRID - 1; }
  if (y1 > GRID - 1) { y1 = GRID - 1; }

  var inv2Sigma2 = 1 / (2 * sigma * sigma);
  var radius2 = radius * radius;

  for (var y = y0; y <= y1; ++y) {
    var dy = (y + 0.5) / GRID - cy;
    for (var x = x0; x <= x1; ++x) {
      // Measured in frame heights on both axes, so the falloff is circular on
      // the fixture.
      var dx = ((x + 0.5) / GRID - cx) * aspectX;
      var d2 = dx * dx + dy * dy;
      if (d2 > radius2) {
        continue;
      }

      var a = alpha * Math.exp(-d2 * inv2Sigma2);
      if (a <= 0) {
        continue;
      }
      if (a > 1) {
        a = 1;
      }

      var i = y * GRID + x;
      var keep = 1 - a;
      canR[i] = canR[i] * keep + r * a;
      canG[i] = canG[i] * keep + g * a;
      canB[i] = canB[i] * keep + b * a;
      canA[i] = canA[i] * keep + a;
    }
  }
}

function renderPoint(point, deltaMs, enabledAmount, inputColor) {
  if (canA == null || enabledAmount <= 0) {
    return inputColor;
  }

  // The base the paint runs over. Live by default; Freeze holds the snapshot
  // instead, which stops the whole picture between splats.
  var base = inputColor;
  if (freeze > 0 && sourceColors != null) {
    base = LXColor.lerp(inputColor, sourceColors[point.index], clamp(freeze, 0, 1));
  }

  var coverage = sampleCanvas(point.xn, point.yn);
  if (coverage <= 0) {
    return LXColor.lerp(inputColor, base, clamp(enabledAmount, 0, 1));
  }

  // Un-premultiply to recover the paint's own color, then lay it over the base
  // by the coverage that accumulated there.
  var paint = rgb(
    clampByte(outR / coverage),
    clampByte(outG / coverage),
    clampByte(outB / coverage)
  );

  var over = LXColor.lerp(base, paint, clamp(coverage * amount, 0, 1));
  return LXColor.lerp(inputColor, over, clamp(enabledAmount, 0, 1));
}

/**
 * Bilinear sample of the canvas, leaving premultiplied color in outR/outG/outB
 * and returning the coverage.
 *
 * Clamped rather than wrapped: paint that ran off the bottom has left, and
 * wrapping it back in at the top would put a drip where nothing was thrown.
 */
function sampleCanvas(u, v) {
  var x = clamp(u, 0, 1) * (GRID - 1);
  var y = clamp(v, 0, 1) * (GRID - 1);
  var fx = Math.floor(x);
  var fy = Math.floor(y);
  var x1 = fx + 1 < GRID ? fx + 1 : GRID - 1;
  var y1 = fy + 1 < GRID ? fy + 1 : GRID - 1;
  var tx = x - fx;
  var ty = y - fy;

  var i00 = fy * GRID + fx;
  var i01 = fy * GRID + x1;
  var i10 = y1 * GRID + fx;
  var i11 = y1 * GRID + x1;

  var w00 = (1 - tx) * (1 - ty);
  var w01 = tx * (1 - ty);
  var w10 = (1 - tx) * ty;
  var w11 = tx * ty;

  outR = canR[i00] * w00 + canR[i01] * w01 + canR[i10] * w10 + canR[i11] * w11;
  outG = canG[i00] * w00 + canG[i01] * w01 + canG[i10] * w10 + canG[i11] * w11;
  outB = canB[i00] * w00 + canB[i01] * w01 + canB[i10] * w10 + canB[i11] * w11;
  return canA[i00] * w00 + canA[i01] * w01 + canA[i10] * w10 + canA[i11] * w11;
}

/**
 * Bilinear sample of the frozen snapshot, into outR/outG/outB as 0-255 floats.
 *
 * Goes through the nearest-point lookup rather than the model directly, which is
 * what lets a head read a color at any (u, v) on a model that is neither a grid
 * nor even necessarily filled.
 */
function sampleSnapshot(u, v) {
  var x = clamp(u, 0, 1) * (GRID - 1);
  var y = clamp(v, 0, 1) * (GRID - 1);
  var fx = Math.floor(x);
  var fy = Math.floor(y);
  var x1 = fx + 1 < GRID ? fx + 1 : GRID - 1;
  var y1 = fy + 1 < GRID ? fy + 1 : GRID - 1;
  var tx = x - fx;
  var ty = y - fy;

  var a = sourceColors[sourceLookup[fy * GRID + fx]];
  var b = sourceColors[sourceLookup[fy * GRID + x1]];
  var c = sourceColors[sourceLookup[y1 * GRID + fx]];
  var d = sourceColors[sourceLookup[y1 * GRID + x1]];

  var w00 = (1 - tx) * (1 - ty);
  var w01 = tx * (1 - ty);
  var w10 = (1 - tx) * ty;
  var w11 = tx * ty;

  outR = ((a >> 16) & 0xff) * w00 + ((b >> 16) & 0xff) * w01 +
    ((c >> 16) & 0xff) * w10 + ((d >> 16) & 0xff) * w11;
  outG = ((a >> 8) & 0xff) * w00 + ((b >> 8) & 0xff) * w01 +
    ((c >> 8) & 0xff) * w10 + ((d >> 8) & 0xff) * w11;
  outB = (a & 0xff) * w00 + (b & 0xff) * w01 +
    (c & 0xff) * w10 + (d & 0xff) * w11;
}

/** Build an edge-clipped nearest-point lookup for arbitrary LX models. */
function buildSourceLookup(model) {
  indexedModel = model;
  indexedPointCount = model.points.length;

  if (model.points.length == 0) {
    sourceLookup = null;
    return;
  }

  sourceLookup = new IntArray(GRID * GRID);

  var binTotal = BIN_COUNT * BIN_COUNT;
  for (var b = 0; b < binTotal; ++b) {
    binHead[b] = -1;
  }

  for (var i = 0; i < model.points.length; ++i) {
    var point = model.points[i];
    var bx = Math.min(BIN_COUNT - 1, Math.floor(clamp(point.xn, 0, 1) * BIN_COUNT));
    var by = Math.min(BIN_COUNT - 1, Math.floor(clamp(point.yn, 0, 1) * BIN_COUNT));
    var bin = by * BIN_COUNT + bx;
    pointNext[i] = binHead[bin];
    binHead[bin] = i;
  }

  for (var y = 0; y < GRID; ++y) {
    var v = y / (GRID - 1);
    for (var x = 0; x < GRID; ++x) {
      var u = x / (GRID - 1);
      sourceLookup[y * GRID + x] = nearestPointIndex(model, u, v);
    }
  }
}

/** Find the nearest model point on a clipped XY plane using expanding bins. */
function nearestPointIndex(model, u, v) {
  var centerX = Math.min(BIN_COUNT - 1, Math.floor(clamp(u, 0, 1) * BIN_COUNT));
  var centerY = Math.min(BIN_COUNT - 1, Math.floor(clamp(v, 0, 1) * BIN_COUNT));
  nearestBestPoint = -1;
  nearestBestDistanceSq = Infinity;

  for (var radius = 0; radius < BIN_COUNT; ++radius) {
    if (radius == 0) {
      inspectBin(model, centerY * BIN_COUNT + centerX, u, v);
    } else {
      for (var dx = -radius; dx <= radius; ++dx) {
        inspectBinAt(model, centerX + dx, centerY - radius, u, v);
        inspectBinAt(model, centerX + dx, centerY + radius, u, v);
      }
      for (var dy = -radius + 1; dy < radius; ++dy) {
        inspectBinAt(model, centerX - radius, centerY + dy, u, v);
        inspectBinAt(model, centerX + radius, centerY + dy, u, v);
      }
    }

    // Conservative lower bound: anything beyond this ring is at least
    // (radius-1) cells away, regardless of where u/v lie in the center cell.
    var unsearchedDistance = Math.max(0, radius - 1) / BIN_COUNT;
    if (nearestBestPoint >= 0 &&
        unsearchedDistance * unsearchedDistance > nearestBestDistanceSq) {
      break;
    }
  }

  return nearestBestPoint >= 0 ? model.points[nearestBestPoint].index : model.points[0].index;
}

function inspectBinAt(model, bx, by, u, v) {
  if (bx >= 0 && bx < BIN_COUNT && by >= 0 && by < BIN_COUNT) {
    inspectBin(model, by * BIN_COUNT + bx, u, v);
  }
}

function inspectBin(model, bin, u, v) {
  for (var i = binHead[bin]; i >= 0; i = pointNext[i]) {
    var point = model.points[i];
    var dx = Math.abs(point.xn - u);
    var dy = Math.abs(point.yn - v);
    var distanceSq = dx * dx + dy * dy;
    if (distanceSq < nearestBestDistanceSq) {
      nearestBestDistanceSq = distanceSq;
      nearestBestPoint = i;
    }
  }
}

function clampByte(value) {
  var v = value | 0;
  return v < 0 ? 0 : (v > 255 ? 255 : v);
}
