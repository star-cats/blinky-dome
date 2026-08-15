/**
 * Lines march in on the beat, then the whole picture slides over and leaves a
 * strip of black behind. Repeat forever, the axis turning a quarter turn each
 * time.
 *
 * A phase is four beats by default. On the first three a line enters from one
 * edge of the frame and extends across it, one per beat. On the fourth the
 * entire scene translates back toward that same edge, far enough to bring the
 * "division line" — the far edge of what has been drawn — in to the middle of
 * the frame. Everything the scene had is carried along with it, and the strip
 * uncovered behind it is black. The next phase draws into that strip, on the
 * perpendicular axis, growing away from a perpendicular edge; then it shifts,
 * and so on. Lines enter downward and the picture slides up; then lines enter
 * rightward and the picture slides left; then up-or-down again, chosen by coin
 * flip, forever.
 *
 * There is no canvas and nothing is ever erased. The scene is a list of
 * rectangles in a world that extends past the frame, plus a camera offset, and a
 * shift is nothing but the camera moving. That is what makes the black strip
 * free: a line covers the frame edge to edge, so moving the camera by the part
 * of the frame beyond the split leaves it covering exactly up to the split, with
 * the rest bare. Nothing has to be clipped for the strip to come out clean, and
 * nothing has to be redrawn for content four phases old to still be sitting
 * where it was left.
 *
 * That also means the strip a phase draws into is precisely the strip the last
 * shift bared, and the shift after next maps its far edge back onto the new
 * division — so old lines land flush against it instead of straying into the
 * black. The invariant holds without a single clip anywhere in the renderer.
 *
 * The four states — top, bottom, left, right — are one piece of code. A state is
 * an axis and a sign, and everything else is written against a coordinate `t`
 * that runs 0 at the edge the lines come from to 1 at the edge they reach. Lines
 * grow t upward, the camera moves t downward, the strip uncovered is t from the
 * split to 1, and the next phase's lines are placed randomly inside it. Which
 * literal direction any of that is depends only on which pair the state names.
 *
 * All of that lives on a square laid over the frame, which exists to make the
 * world isotropic — a shift, a rotation and a line's thickness all have to mean
 * the same thing along either axis, and the frame itself need not be square.
 * Splits, placements and widths are specified against the frame and converted
 * through it, so "50%" is half of what you can actually see, and a beat of
 * extending is one traverse of it.
 *
 * The composition is therefore exactly frame-sized, and turning it swings its
 * corners in: past a few degrees of spin the outer corners of the frame fall
 * outside the picture and stay black. That is the honest reading of a design
 * whose whole vocabulary — top, bottom, left, right, half way up — is written
 * against the edges of a frame, and on a fixture that is not a filled rectangle
 * anyway it costs nothing.
 */

var TAU = Math.PI * 2;

var TOP = 0;
var BOTTOM = 1;
var LEFT = 2;
var RIGHT = 3;

/**
 * A state is an axis and a direction, and nothing else. `axis` is 0 for the
 * horizontal one and 1 for the vertical; `flip` says the lines grow toward the
 * low end of it rather than the high end. Every difference between the four
 * states is one of these two lookups.
 */
var STATE_AXIS = [1, 1, 0, 0];
var STATE_FLIP = [true, false, false, true];

/** Most lines a single phase can draw, and so the longest a phase can be. */
var LINES_MAX = 5;

/**
 * Lines kept alive.
 *
 * Nothing here is ever erased, so the count is set by how fast the shifts carry
 * lines out: a phase adds three, and each shift takes out about half of the
 * family lying across it. That is a stable balance rather than a slow fill —
 * over two hours the number on frame stays around thirty and wanders between
 * twenty and fifty, with brief peaks near a hundred and fifty.
 *
 * The retained count includes what is held just off frame against a reversal, so
 * this ceiling has to clear the peak by a wide margin: once it binds, the
 * overflow below starts evicting lines that are still visible, and they blink
 * out where you can see them. Measured worst case is under 180.
 */
var MAX_LINES = 256;

/** Line width as a fraction of the frame's height, at either end of the knob. */
var THICK_MIN = 0.004;
var THICK_MAX = 0.12;

/**
 * How far past the frame a line has to be carried before it is forgotten.
 *
 * Not zero, because shifts alternate axes but not directions: two phases apart,
 * the picture can slide back the way it came, and something that had just left
 * the frame ought to return rather than having quietly ceased to exist. One
 * shift is at most 0.92 of a frame span, so this covers any single reversal;
 * nothing that needs two of them in a row is still worth carrying.
 */
var PRUNE_MARGIN = 0.7;

/** Slack on the visibility test, so a line's soft edge is never clipped. */
var EDGE_MARGIN = 0.05;

knob("bpm", "BPM", "Tempo everything is cut to; 0.5 is 120", 0.5);
knob("rate", "Rate", "How much quicker than a beat a line extends or the scene shifts; 0 is exactly one beat, 1 is three times", 0);
knob("count", "Count", "Lines drawn per phase, before the shift", 0.5);

knob("split", "Split", "Where the division lands, as a fraction of the frame", 0.5);
knob("spread", "Spread", "How far the split wanders from that, a quarter of the frame either way at full", 0.4);

knob("minThick", "Min", "Thinnest a line can be", 0.12);
knob("maxThick", "Max", "Thickest a line can be", 0.3);

knob("spin", "Spin", "How fast the scene turns; 0.5 is still", 0.55);
knob("soft", "Soft", "Edge softness — this is the anti-aliasing", 0.2);

knob("hue", "Hue", "Line hue", 0.55);
knob("sat", "Sat", "Line saturation; 0 is white", 0);
knob("level", "Level", "Overall brightness", 1);

toggle("autoAspect", "Aspect", "Correct for a non-square model", true);

// ----------------------------------------------------------------- the scene
//
// Lines, in world coordinates on the scene square. Parallel flat arrays rather
// than objects: these are walked per LED, and the flat form keeps the inner loop
// free of property lookups. Order is oldest first, and compaction in updateLines
// keeps it that way.

var lineAxis = [];
var linePos = [];   // the perpendicular coordinate; a line's whole position
var lineBase = [];  // the fixed end, on the growth axis
var lineLen = [];   // how far it reaches once fully extended
var lineDir = [];   // +1 or -1 along that axis
var lineHalf = [];
var lineBorn = [];  // musical time, in beats, that it started extending
var lineN = 0;

// This frame's visible lines in screen coordinates, rebuilt by updateLines so
// renderPoint is a straight walk with no camera arithmetic in it.
var drawAxis = [];
var drawPos = [];
var drawLo = [];
var drawHi = [];
var drawHalf = [];
var drawN = 0;

// ------------------------------------------------------------------ the clock

var beats = 0;
var lastBeat = -1;

// ------------------------------------------------------------------ the phase

var phaseState = TOP;
var phaseStart = 0;
var phaseCount = 3;
var phaseSplit = 0.5;   // as a fraction of the frame, not of the scene square

// What endShift worked out for the phase after it. Held here rather than
// returned so that a phase change, like a frame, allocates nothing.
var nextState = TOP;
var nextBandLo = 0;
var nextBandHi = 1;

// The three lines of the current phase, planned in full when the phase begins so
// that they can be guaranteed not to overlap, and revealed one per beat.
var planPos = [];
var planHalf = [];
var planW = [];
var planGap = [];

// ----------------------------------------------------------------- the camera

var camU = 0;
var camV = 0;
var camBaseU = 0;
var camBaseV = 0;
var shiftDU = 0;
var shiftDV = 0;
var shiftStart = 0;

// --------------------------------------------------- frame-derived quantities

var aspectX = 1;
var sceneR = Math.SQRT2;
var axisMargin = [0, 0];  // where the frame starts, on the scene square
var axisSpan = [1, 1];    // and how much of the square it covers
var theta = 0;
var cosT = 1;
var sinT = 0;
var rateMul = 1;
var softW = 0.02;
var started = false;

function init() {
  lineN = 0;
  drawN = 0;
  beats = 0;
  lastBeat = -1;
  camU = camV = camBaseU = camBaseV = 0;
  shiftDU = shiftDV = 0;
  theta = 0;
  // The whole frame is fair game for the opening phase, since nothing has been
  // uncovered yet and there is no strip to draw into.
  beginPhase(0, TOP, 0, 1);
  started = true;
}

/** Smoothstep. Both the extending and the shifting run on it. */
function ease(p) {
  return p * p * (3 - 2 * p);
}

// -------------------------------------------------------------- phase changes

/**
 * Start a phase in `state`, placing its lines somewhere in the band the last
 * shift uncovered.
 *
 * The band arrives as a fraction of the frame along the axis of the *previous*
 * phase, which is the axis a new line's position varies along — the two
 * alternate, so the previous phase's axis is this one's perpendicular.
 */
function beginPhase(beat, state, bandLo, bandHi) {
  phaseState = state;
  phaseStart = beat;
  phaseCount = 1 + Math.round(clamp(count, 0, 1) * (LINES_MAX - 1));
  phaseSplit = clamp(split + (Math.random() * 2 - 1) * 0.25 * spread, 0.08, 0.92);

  var perp = 1 - STATE_AXIS[state];
  planLines(
    axisMargin[perp] + bandLo * axisSpan[perp],
    axisMargin[perp] + bandHi * axisSpan[perp]
  );
}

/**
 * Choose this phase's line positions and widths, in scene coordinates, inside
 * the band from `lo` to `hi`.
 *
 * They must not overlap, so they are packed rather than sampled independently:
 * widths are drawn first, scaled down together if they cannot all fit, and what
 * is left over is split into the gaps around them by a random partition. That
 * gives non-overlap by construction instead of by rejection, which matters
 * because a narrow band and thick lines can make rejection sampling take
 * arbitrarily long or never succeed at all.
 *
 * The order is then shuffled, so that the first line drawn is not always the one
 * nearest the low edge of the band.
 */
function planLines(lo, hi) {
  var n = phaseCount;
  var i;

  var span = hi - lo;
  if (span < 0.02) {
    // Degenerate band — a split pushed hard against an edge. Open it up around
    // its own center rather than trying to pack into nothing.
    var mid = (lo + hi) * 0.5;
    lo = mid - 0.01;
    hi = mid + 0.01;
    span = hi - lo;
  }

  // Widths are specified against the frame; the scene square is bigger, and the
  // aspect correction has already made it isotropic, so one factor converts.
  var scale = axisSpan[1];
  var a = lerp(THICK_MIN, THICK_MAX, clamp(minThick, 0, 1)) * scale;
  var b = lerp(THICK_MIN, THICK_MAX, clamp(maxThick, 0, 1)) * scale;
  var wLo = a < b ? a : b;
  var wHi = a < b ? b : a;

  var total = 0;
  for (i = 0; i < n; ++i) {
    planW[i] = wLo + Math.random() * (wHi - wLo);
    total += planW[i];
  }

  // Leave a little air even in the worst case, so lines packed into a tight band
  // still read as separate lines rather than as one solid block.
  var cap = span * 0.85;
  if (total > cap) {
    var k = cap / total;
    for (i = 0; i < n; ++i) {
      planW[i] *= k;
    }
    total = cap;
  }

  var gapSum = 0;
  for (i = 0; i <= n; ++i) {
    planGap[i] = Math.random();
    gapSum += planGap[i];
  }
  if (gapSum <= 0) {
    gapSum = 1;
  }

  var slack = span - total;
  var at = lo;
  for (i = 0; i < n; ++i) {
    at += slack * planGap[i] / gapSum;
    planPos[i] = at + planW[i] * 0.5;
    planHalf[i] = planW[i] * 0.5;
    at += planW[i];
  }

  for (i = n - 1; i > 0; --i) {
    var j = Math.floor(Math.random() * (i + 1));
    var p = planPos[i]; planPos[i] = planPos[j]; planPos[j] = p;
    var h = planHalf[i]; planHalf[i] = planHalf[j]; planHalf[j] = h;
  }
}

/** Put the k'th planned line into the scene, at the edge, with no length yet. */
function spawnLine(k, beat) {
  if (lineN >= MAX_LINES) {
    dropOldest();
  }

  var axis = STATE_AXIS[phaseState];
  var flip = STATE_FLIP[phaseState];
  var margin = axisMargin[axis];

  var slot = lineN++;
  lineAxis[slot] = axis;
  // Recorded in world coordinates, which is the whole trick: from here on the
  // line never moves, and every shift is the camera moving instead.
  lineBase[slot] = (flip ? 1 - margin : margin) + (axis === 0 ? camU : camV);
  // Edge of frame to edge of frame, so a beat of extending is exactly a
  // traverse of what you can see and the shift that follows is exactly the
  // fraction of the frame the split asks for.
  lineLen[slot] = axisSpan[axis];
  linePos[slot] = planPos[k] + (axis === 0 ? camV : camU);
  lineDir[slot] = flip ? -1 : 1;
  lineHalf[slot] = planHalf[k];
  // The beat itself rather than the current time, so a long frame cannot let a
  // line start late and drift off the grid.
  lineBorn[slot] = beat;
}

function dropOldest() {
  for (var i = 1; i < lineN; ++i) {
    lineAxis[i - 1] = lineAxis[i];
    linePos[i - 1] = linePos[i];
    lineBase[i - 1] = lineBase[i];
    lineLen[i - 1] = lineLen[i];
    lineDir[i - 1] = lineDir[i];
    lineHalf[i - 1] = lineHalf[i];
    lineBorn[i - 1] = lineBorn[i];
  }
  --lineN;
}

/**
 * Set the camera moving for the shift beat.
 *
 * The distance is fixed by where the division has to end up: content covers the
 * frame edge to edge, and it has to come to rest covering the near edge to the
 * split, so the camera travels the rest of the frame — 1 - split of it. Which
 * way that is along which axis is the state's axis and sign, and nothing more.
 */
function beginShift(beat) {
  var axis = STATE_AXIS[phaseState];
  var flip = STATE_FLIP[phaseState];

  var dist = axisSpan[axis] * (1 - phaseSplit) * (flip ? -1 : 1);

  shiftDU = axis === 0 ? dist : 0;
  shiftDV = axis === 1 ? dist : 0;
  shiftStart = beat;
}

/**
 * Bank the shift and pick what follows it.
 *
 * The next phase runs on the perpendicular axis, its direction a coin flip, and
 * it draws into the band the shift just uncovered — which is the split to the
 * far edge, or its mirror if the lines were growing the other way.
 */
function endShift() {
  camBaseU += shiftDU;
  camBaseV += shiftDV;
  shiftDU = 0;
  shiftDV = 0;

  var flip = STATE_FLIP[phaseState];
  nextBandLo = flip ? 0 : phaseSplit;
  nextBandHi = flip ? 1 - phaseSplit : 1;

  nextState = STATE_AXIS[phaseState] === 1
    ? (Math.random() < 0.5 ? LEFT : RIGHT)
    : (Math.random() < 0.5 ? TOP : BOTTOM);
}

/**
 * Advance the state machine one beat. A phase is `phaseCount` beats of drawing
 * and then one of shifting, and the beat after that belongs to the next phase.
 */
function onBeat(beat) {
  var k = beat - phaseStart;

  if (k > phaseCount) {
    endShift();
    beginPhase(beat, nextState, nextBandLo, nextBandHi);
    k = 0;
  }

  if (k < phaseCount) {
    spawnLine(k, beat);
  } else {
    beginShift(beat);
  }
}

// ------------------------------------------------------------------ per frame

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;

  updateFrame(model);
  if (!started) {
    init();
  }

  rateMul = 1 + 2 * clamp(rate, 0, 1);
  softW = lerp(0.002, 0.05, soft) * axisSpan[1];
  theta += (spin - 0.5) * 2 * TAU * 0.15 * dt;
  cosT = Math.cos(theta);
  sinT = Math.sin(theta);

  // Musical time, accumulated rather than divided out of a wall clock, so that
  // moving the tempo knob changes the rate from here on instead of jumping the
  // phase to wherever the new tempo says it should already have been.
  var beatSec = 60 / lerp(40, 200, clamp(bpm, 0, 1));
  beats += dt / beatSec;

  // A frame long enough to span two beats still gets both, in order.
  var beat = Math.floor(beats);
  while (lastBeat < beat) {
    onBeat(++lastBeat);
  }

  var q = clamp((beats - shiftStart) * rateMul, 0, 1);
  camU = camBaseU + shiftDU * ease(q);
  camV = camBaseV + shiftDV * ease(q);

  updateLines();
}

/**
 * Work out where the frame sits on the scene square.
 *
 * The square is sized to circumscribe the frame, so that whatever the rotation
 * is, no LED can ever land outside the composition and see its edge. Everything
 * specified as a fraction of the frame — splits, line placements, thicknesses —
 * is converted through the margin and span this leaves.
 */
function updateFrame(model) {
  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  sceneR = Math.sqrt(aspectX * aspectX + 1);
  axisSpan[0] = aspectX / sceneR;
  axisSpan[1] = 1 / sceneR;
  axisMargin[0] = (1 - axisSpan[0]) * 0.5;
  axisMargin[1] = (1 - axisSpan[1]) * 0.5;
}

/**
 * Age every line, forget the ones carried irretrievably out of frame, and leave
 * the rest in screen coordinates for the renderer.
 *
 * The surviving lines are compacted in place rather than filtered into a new
 * array, so a pattern that runs for hours allocates nothing after load.
 */
function updateLines() {
  var keep = 0;
  drawN = 0;

  for (var i = 0; i < lineN; ++i) {
    var axis = lineAxis[i];
    var base = lineBase[i] - (axis === 0 ? camU : camV);
    var pos = linePos[i] - (axis === 0 ? camV : camU);
    var half = lineHalf[i];

    var p = (beats - lineBorn[i]) * rateMul;
    var len = p >= 1 ? 1 : ease(p < 0 ? 0 : p);
    var end = base + lineDir[i] * lineLen[i] * len;
    var lo = base < end ? base : end;
    var hi = base < end ? end : base;

    if (hi < -PRUNE_MARGIN || lo > 1 + PRUNE_MARGIN ||
        pos + half < -PRUNE_MARGIN || pos - half > 1 + PRUNE_MARGIN) {
      continue;
    }

    if (keep !== i) {
      lineAxis[keep] = axis;
      linePos[keep] = linePos[i];
      lineBase[keep] = lineBase[i];
      lineLen[keep] = lineLen[i];
      lineDir[keep] = lineDir[i];
      lineHalf[keep] = half;
      lineBorn[keep] = lineBorn[i];
    }
    ++keep;

    if (len > 0 &&
        hi >= -EDGE_MARGIN && lo <= 1 + EDGE_MARGIN &&
        pos + half >= -EDGE_MARGIN && pos - half <= 1 + EDGE_MARGIN) {
      drawAxis[drawN] = axis;
      drawPos[drawN] = pos;
      drawLo[drawN] = lo;
      drawHi[drawN] = hi;
      drawHalf[drawN] = half;
      ++drawN;
    }
  }

  lineN = keep;
}

function renderPoint(point, deltaMs) {
  // Into the scene square: center, correct the aspect, turn by the scene's
  // rotation backwards — rotating where we sample from is rotating what is
  // drawn — and scale so the square is the unit box.
  var cx = (point.xn - 0.5) * aspectX;
  var cy = point.yn - 0.5;
  var u = (cx * cosT + cy * sinT) / sceneR + 0.5;
  var v = (cy * cosT - cx * sinT) / sceneR + 0.5;

  // Lines are opaque and all one color, so the brightest wins and there is
  // nothing to accumulate. Within a phase they cannot overlap by construction;
  // across phases they cross, and a crossing is simply lit.
  var best = 0;

  for (var i = 0; i < drawN; ++i) {
    var along, perp;
    if (drawAxis[i] === 0) {
      along = u;
      perp = v;
    } else {
      along = v;
      perp = u;
    }

    // Distance outside the rectangle, as the largest of the three ways of being
    // outside it. Negative inside. Taking the max rather than a true Euclidean
    // distance keeps the ends square, which is what a line arrested exactly at
    // the division wants.
    var d = Math.abs(perp - drawPos[i]) - drawHalf[i];
    var d0 = drawLo[i] - along;
    if (d0 > d) {
      d = d0;
    }
    var d1 = along - drawHi[i];
    if (d1 > d) {
      d = d1;
    }

    if (d >= softW * 0.5) {
      continue;
    }
    var cover = 0.5 - d / softW;
    if (cover > best) {
      best = cover;
      if (best >= 0.999) {
        break;
      }
    }
  }

  if (best <= 0) {
    return rgb(0, 0, 0);
  }
  return hsb(hue * 360, sat * 100, clamp(best, 0, 1) * level * 100);
}
