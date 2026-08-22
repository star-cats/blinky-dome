/**
 * Kaleidoscope Postprocess
 *
 * Folds whatever is already on the screen through a mirror group. Each output
 * point maps to some *other* point of the current frame, so this adds no color
 * of its own — it only decides where each pixel reads from.
 *
 * Three tessellations, all driven by the same Symmetry count:
 *   Wedge  — the classic tube. N mirrored wedges around a center.
 *   Tile   — a mirrored grid, N copies of the frame across. Wallpaper.
 *   Spiral — the wedge fold in log-polar space, so the seams wind inward.
 *
 * Both sampling axes have a manual Phase and a bipolar Speed. What the axes
 * mean follows the tessellation — angle/radius for Wedge, u/v for Tile,
 * angle/zoom for Spiral — but in every case they slide the source texture
 * underneath a fixed set of mirrors, which is the motion a real kaleidoscope
 * makes when you turn the object cell rather than the tube.
 */

var IntArray = Java.type("int[]");

var TAU = Math.PI * 2;

// Resolution of the reusable XY-to-model-point lookup, and the bin grid used to
// build it. Matched to NoiseRippleDistortion, which resamples the same way.
var LOOKUP_SIZE = 128;
var BIN_COUNT = 32;

// Radial repeat of the Spiral fold: one band is e^1.6, about a 5x zoom, and the
// twist turns the seams by one full wedge across that band.
var LOG_PERIOD = 1.6;
var SPIRAL_TWIST = 1.0;
// Log-radius runs to negative infinity at the center, so the twist would wind
// and the bands cycle infinitely fast in the last points before the middle.
// Softening the log by a fraction of the frame makes it linear near zero
// instead, which bounds that rate. 0.04 measured out as the floor of the
// artifact: below it the divergence returns, above it the whole spiral
// compresses and the seams get steep again.
//
// It does not remove the center singularity, and nothing can: a spiral's sample
// radius never reaches zero, so the middle point maps to a whole ring and has no
// single angle. That is inherent to a log-polar fold. It costs the couple of
// LEDs at dead center, and only in this mode.
var SPIRAL_SOFTEN = 0.04;

var MAX_RATE = 0.35;
var MAX_ZOOM = 4;

// Phases wrap at 2, not 1, because every axis that lands in reflectRange is
// mirrored: it runs out and back, so its period is two spans and wrapping at one
// would flip the mirror parity and pop. The angular axes only need 1 (a turn of
// TAU is a turn of nothing), and 2 is a whole number of those too, so one period
// keeps every consumer seamless. Nothing here needs a periodic reset for
// floating point — the accumulator stays inside [0,2) forever, and each fold is
// exactly invariant under a step of 2, so the wrap is silent rather than merely
// rare.
var PHASE_PERIOD = 2;

var WEDGE = 0;
var TILE = 1;
var SPIRAL = 2;

// knobi builds a 0-based discrete parameter, so a range of 11 is what makes the
// displayed number the actual fold count. The 0 slot is the one wart; it reads
// as 1, which is a single mirror rather than no effect at all.
knobi("symmetry", "Symmetry", "Mirror folds, 1-10 (0 reads as 1)", 1, 8);
knobi("tess", "Tess", "Tessellation: 0 wedge, 1 tile, 2 spiral", 0, 3);

knob("zoom", "Zoom", "Scale of the source read, 0.25x to 4x", 0.5);
knob("roll", "Roll", "Rotate the mirrors themselves, sweeping the seams", 0);

knob("phaseX", "Phase X", "Manual offset on the first sampling axis", 0);
knob("speedX", "Speed X", "Drift rate of the first axis, 0.5 is stopped", 0.5);
knob("phaseY", "Phase Y", "Manual offset on the second sampling axis", 0);
knob("speedY", "Speed Y", "Drift rate of the second axis, 0.5 is stopped", 0.5);

toggle("autoAspect", "Aspect", "Correct for a non-square model", true);

var driftX = 0;
var driftY = 0;
var shiftX = 0;
var shiftY = 0;

var aspectX = 1;
var sceneR = 1;
var folds = 1;
var wedgeWidth = TAU;
var rollCos = 1;
var rollSin = 0;
var srcScale = 1;

var sourceColors = null;
var indexedModel = null;
var indexedPointCount = -1;
var binHead = [];
var pointNext = [];
var sourceLookup = null;
var nearestBestPoint = -1;
var nearestBestDistanceSq = Infinity;

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;

  // Phases stay inside one period, so they never lose precision to a growing
  // exponent — and since every fold is invariant across that period, the wrap
  // itself is invisible. See PHASE_PERIOD.
  driftX = wrap(driftX + dt * (speedX - 0.5) * 2 * MAX_RATE, PHASE_PERIOD);
  driftY = wrap(driftY + dt * (speedY - 0.5) * 2 * MAX_RATE, PHASE_PERIOD);
  shiftX = wrap(driftX + phaseX, PHASE_PERIOD);
  shiftY = wrap(driftY + phaseY, PHASE_PERIOD);

  folds = Math.max(1, symmetry);
  wedgeWidth = TAU / folds;

  // Rolling the mirrors is the same as counter-rolling every point, and the
  // angle is constant for the frame, so it resolves to one sin/cos here rather
  // than a pair per point.
  rollCos = Math.cos(-roll * TAU);
  rollSin = Math.sin(-roll * TAU);

  // Zoom is exponential so 0.5 is unity and the two halves of the knob travel
  // the same factor in each direction.
  srcScale = 1 / Math.pow(MAX_ZOOM, (zoom - 0.5) * 2);

  updateFrame(model);

  if (sourceColors == null || sourceColors.length != colors.length) {
    sourceColors = new IntArray(colors.length);
  }
  for (var i = 0; i < colors.length; ++i) {
    sourceColors[i] = colors[i];
  }

  // Keyed on the model alone: the lookup is built from raw xn/yn, so aspect and
  // every other control change without invalidating it.
  if (indexedModel !== model || indexedPointCount != model.points.length) {
    buildSourceLookup(model);
  }
}

function renderPoint(point, deltaMs, enabledAmount, inputColor) {
  if (sourceLookup == null || enabledAmount <= 0) {
    return inputColor;
  }

  // Centered, aspect-corrected scene coordinates. One unit is the same distance
  // on either axis, which is what keeps a wedge angular and a tile square on an
  // oblong model.
  var sx = (point.xn - 0.5) * aspectX * srcScale;
  var sy = (point.yn - 0.5) * srcScale;

  // Counter-rolling here, before the fold, means all three tessellations pick
  // up Roll for free.
  var rx = sx * rollCos - sy * rollSin;
  var ry = sx * rollSin + sy * rollCos;

  var folded;
  if (tess == TILE) {
    folded = foldTile(rx, ry);
  } else if (tess == SPIRAL) {
    folded = foldSpiral(rx, ry);
  } else {
    folded = foldWedge(rx, ry);
  }

  var kaleidoColor = sampleSource(
    mirror01(folded.x / aspectX + 0.5),
    mirror01(folded.y + 0.5)
  );
  return LXColor.lerp(inputColor, kaleidoColor, clamp(enabledAmount, 0, 1));
}

// Reused rather than reallocated: renderPoint runs per point per frame, and a
// fresh object each time is the kind of garbage Nashorn makes us pay for.
var foldResult = { x: 0, y: 0 };

/**
 * The classic tube. Fold the angle into one wedge with a reflection, leaving
 * the radius alone, so the frame's content repeats around the center.
 */
function foldWedge(x, y) {
  var r = Math.sqrt(x * x + y * y);
  var a = reflectInto(Math.atan2(y, x), wedgeWidth) + shiftX * TAU;

  // Reflected into the frame's disc rather than pushed off it. An open-ended
  // push has no period, so the phase wrap had nowhere seamless to land and the
  // read snapped back by a full radius once a cycle. Folding gives the radial
  // axis a period of two spans, matching every other axis, and it costs nothing
  // at rest: r never exceeds sceneR at unit zoom, so this is the identity until
  // the shift actually moves.
  var rs = reflectRange(r + shiftY * sceneR, 0, sceneR);
  foldResult.x = rs * Math.cos(a);
  foldResult.y = rs * Math.sin(a);
  return foldResult;
}

/**
 * Mirrored grid. N copies of the frame across, every other one flipped, which
 * is what makes the copies join instead of tiling with a visible cut.
 */
function foldTile(x, y) {
  var halfW = aspectX * 0.5;
  foldResult.x = reflectRange(x * folds + shiftX * aspectX, -halfW, halfW);
  foldResult.y = reflectRange(y * folds + shiftY, -0.5, 0.5);
  return foldResult;
}

/**
 * The wedge fold done in log-polar space. Coupling log-radius into the angle
 * bends the straight seams into spiral arms, and drifting log-radius reads as a
 * continuous zoom rather than a slide.
 */
function foldSpiral(x, y) {
  var r = Math.max(Math.sqrt(x * x + y * y), 1e-5);
  var lr = Math.log((r + SPIRAL_SOFTEN * sceneR) / sceneR) / LOG_PERIOD;

  var theta = Math.atan2(y, x) + lr * SPIRAL_TWIST * wedgeWidth;
  var a = reflectInto(theta, wedgeWidth) + shiftX * TAU;

  // Reflect into a single band of [-1,0] e-folds so the read stays inside the
  // frame's disc and reverses at the band edges instead of jumping.
  var band = reflectRange(lr + shiftY, -1, 0);
  var rs = sceneR * Math.exp(band * LOG_PERIOD);

  foldResult.x = rs * Math.cos(a);
  foldResult.y = rs * Math.sin(a);
  return foldResult;
}

/**
 * Fold a value into [0, period/2] by reflecting every other period. This is the
 * mirror itself: N of these around a circle give N wedges, each holding a
 * reflected pair, for 2N segments in the round.
 */
function reflectInto(value, period) {
  var m = wrap(value, period);
  return m > period * 0.5 ? period - m : m;
}

/** Reflect a value into [min, max], continuous at both ends. */
function reflectRange(value, min, max) {
  var span = max - min;
  return min + mirror01((value - min) / span) * span;
}

/** The frame the fold works against, in aspect-corrected scene units. */
function updateFrame(model) {
  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }
  // Half-diagonal: the radius that still reaches the frame's corners, so a
  // full-radius read covers everything rather than stopping at the short edge.
  sceneR = Math.sqrt(aspectX * aspectX + 1) * 0.5;
}

/**
 * Snapshot sampling is essential for an effect: the default script runner
 * overwrites colors in point order, so reading the live array would make later
 * points sample already-folded output from earlier points.
 */
function sampleSource(u, v) {
  var x = clamp(u, 0, 1) * (LOOKUP_SIZE - 1);
  var y = clamp(v, 0, 1) * (LOOKUP_SIZE - 1);
  var floorX = Math.floor(x);
  var floorY = Math.floor(y);
  var x0 = floorX;
  var y0 = floorY;
  var x1 = Math.min(x0 + 1, LOOKUP_SIZE - 1);
  var y1 = Math.min(y0 + 1, LOOKUP_SIZE - 1);
  var tx = x - floorX;
  var ty = y - floorY;

  var row0 = y0 * LOOKUP_SIZE;
  var row1 = y1 * LOOKUP_SIZE;
  var a = sourceColors[sourceLookup[row0 + x0]];
  var b = sourceColors[sourceLookup[row0 + x1]];
  var c = sourceColors[sourceLookup[row1 + x0]];
  var d = sourceColors[sourceLookup[row1 + x1]];
  return LXColor.lerp(
    LXColor.lerp(a, b, tx),
    LXColor.lerp(c, d, tx),
    ty
  );
}

/** Build an edge-clipped nearest-point lookup for arbitrary LX models. */
function buildSourceLookup(model) {
  indexedModel = model;
  indexedPointCount = model.points.length;

  if (model.points.length == 0) {
    sourceLookup = null;
    return;
  }
  sourceLookup = new IntArray(LOOKUP_SIZE * LOOKUP_SIZE);

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

  for (var y = 0; y < LOOKUP_SIZE; ++y) {
    var v = y / (LOOKUP_SIZE - 1);
    for (var x = 0; x < LOOKUP_SIZE; ++x) {
      var u = x / (LOOKUP_SIZE - 1);
      sourceLookup[y * LOOKUP_SIZE + x] = nearestPointIndex(model, u, v);
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

function mirror01(value) {
  var mirrored = wrap(value, 2);
  return mirrored <= 1 ? mirrored : 2 - mirrored;
}

function wrap(value, period) {
  return value - Math.floor(value / period) * period;
}
