/**
 * Lines march in on the beat, then the whole picture slides over, cuts itself
 * open and fills the opening with a flat new tone. Repeat forever, the axis
 * turning a quarter turn each time.
 *
 * A phase is four beats by default. On the first three a line enters from one
 * edge of the frame and extends across it, one per beat. On the fourth a cut is
 * struck across the far edge of everything drawn — a line laid down whole rather
 * than extended, marking where the picture is about to come apart — and the
 * entire scene translates back toward the edge it came from, far enough to bring
 * that cut in to the middle of the frame. Everything is carried along with it,
 * and what opens up behind is a solid field of a new tone. The next phase draws
 * into that field, on the perpendicular axis, growing away from a perpendicular
 * edge; then it cuts and shifts again, and so on. Lines enter downward and the
 * picture slides up; then lines enter rightward and the picture slides left;
 * then up-or-down again, chosen by coin flip, forever.
 *
 * There is no canvas and nothing is ever erased. The picture is an ordered list
 * of two kinds of opaque marks in a world that extends past the frame, plus a
 * camera offset, and a shift is nothing but the camera moving:
 *
 *   - a LINE, a rectangle of one flat tone;
 *   - a PAINT, a half-plane of one flat tone, laid down at the moment of a cut
 *     and covering everything beyond it.
 *
 * A paint is what makes the opening a color rather than a hole. It is placed in
 * world coordinates at the cut, so it does not have to be animated or clipped:
 * the camera slides the frame across it and the field is revealed at exactly the
 * rate the picture moves. Marks are only ever appended, so walking the list from
 * newest back is a painter's algorithm in reverse — the first mark covering a
 * point is the one you see, and a paint correctly buries every line older than
 * the cut while leaving every line drawn after it on top.
 *
 * The tones are the four quarters 0, 1/3, 2/3, 1. Lines are black on every field
 * but the black one, where they go light because black on black is nothing, so
 * the piece reads as black ruling over flat ground with the darkest sections
 * inverting. They are flat: a mark is its tone edge to edge, and the only
 * blending anywhere is the pixel of anti-aliasing at a mark's border.
 *
 * The beat grid comes from the PrimaryController in the blinky-dome-patterns
 * package, through a PrimaryController.Follower that free-runs at the
 * controller's tempo and drifts onto its grid rather than being set from it.
 * Every phase boundary, every line's growth and every camera shift is a function
 * of that clock's beat position, so the whole composition cuts in time with the
 * rest of the show. A clock that could jump would be visible here in a way it is
 * not in most patterns: a mark's extent is measured from the beat it was born
 * on, so a discontinuity does not merely re-time the next event, it snaps every
 * line currently being laid down to a new length. The Follower never jumps,
 * which is exactly why it and not getGridBeats is what this reads.
 *
 * The script engine is handed Chromatik's package class loader, so Java.type
 * reaches the package's classes the same way it reaches the JDK's. If the
 * package is not installed the lookup fails and the pattern free-runs at the
 * Free BPM knob, which is what the Follower would have done anyway with no
 * controller in the project.
 *
 * The four states — top, bottom, left, right — are one piece of code. A state is
 * an axis and a sign, and everything else is written against a coordinate `t`
 * that runs 0 at the edge the lines come from to 1 at the edge they reach. Lines
 * grow t upward, the camera moves t downward, the field laid down is t from the
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
 * corners in: at any angle off the square the outer corners of the frame fall
 * outside the picture and sit at the opening tone. That is the honest reading of
 * a design whose whole vocabulary — top, bottom, left, right, half way up — is
 * written against the edges of a frame, and on a fixture that is not a filled
 * rectangle anyway it costs nothing.
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

/** The two kinds of mark. See the header. */
var KIND_LINE = 0;
var KIND_PAINT = 1;

/**
 * The four tones a revealed field can take, and the tone the lines drawn on each
 * of them get.
 *
 * Lines are black on every field that carries any light at all, so the picture
 * reads as black rule-work laid over flat ground and the ruling stays one thing
 * throughout rather than changing identity with each cut. The black field is the
 * one exception, because black lines on it would be no lines at all; there they
 * go light instead, and that is the only place in the piece where the ruling is
 * lighter than what it sits on.
 *
 * Every pair here differs, which is what guarantees a line is always visible
 * against its own ground. See beginShift for the other half of that guarantee —
 * a field never opens onto the tone already showing.
 */
var BG_LEVELS = [0, 0.33, 0.66, 1];
var LINE_LEVELS = [0.66, 0, 0, 0];

/** The ground before the first cut, and the lines that go on it. */
var BASE_INDEX = 0;

/** Most lines a single phase can draw, and so the longest a phase can be. */
var LINES_MAX = 5;

/**
 * Marks kept alive.
 *
 * Nothing is ever erased, so the count is set entirely by how fast updateItems
 * can prove marks buried. That turns out to be fast, because a cut buries in
 * bulk: over two hours the retained list peaks at 60 and the drawn subset at
 * about 40, whatever the aspect and whatever the angle.
 *
 * The ceiling has to clear that by a wide margin, because once it binds the
 * overflow in allocItem starts evicting the oldest mark whether or not anyone
 * can see it, and a mark that is still visible blinks out where you are looking.
 * Four times the measured peak buys a lot of room for a run longer than any
 * tested here.
 */
var MAX_ITEMS = 256;

/** Line width as a fraction of the frame's height, at either end of the knob. */
var THICK_MIN = 0.004;
var THICK_MAX = 0.12;

/**
 * How far past the frame a mark has to be carried before it is forgotten.
 *
 * Not zero, because shifts alternate axes but not directions: two phases apart,
 * the picture can slide back the way it came, and something that had just left
 * the frame ought to return rather than having quietly ceased to exist. One
 * shift is at most 0.92 of a frame span, so this covers any single reversal;
 * nothing that needs two of them in a row is still worth carrying.
 */
var PRUNE_MARGIN = 0.7;

/** Slack on the visibility test, so a mark's soft edge is never clipped. */
var EDGE_MARGIN = 0.05;

/** Full cycles of the tone phase per second at the top of the Speed knob. */
var PHASE_RATE = 0.25;

/**
 * How far past each end of the frame a line is drawn while it is being laid
 * down, as a fraction of the frame's span on its own axis.
 *
 * A line that stops exactly at the frame edge has an end, and turning the scene
 * swings that end into view — the line is then plainly a segment that halts in
 * mid-air rather than something running off the side of the world, and the whole
 * illusion goes with it. Drawing half a frame further at each end puts both ends
 * outside anything a rotation can reach.
 *
 * Whether a half is *enough* is a matter of geometry rather than taste, and it
 * depends on the model: the visible square, turned, reaches a half-diagonal from
 * center, which is 0.5 in scene units whatever the aspect. An end sits
 * span/2 + overdraw from center, so clearing it wants overdraw >= 0.5 - span/2.
 * On a squarish model a half of the span is comfortably more than that; on a
 * wide one the short axis needs a little more, so axisOver takes whichever is
 * larger and the ends stay hidden either way.
 */
var OVERDRAW = 0.5;

knob("fallbackBpm", "Free BPM", "Tempo to cut to when there is no controller, or before it has found one; 0.5 is 120", 0.5);
knob("sync", "Sync", "How long the scene takes to drift back onto the controller's beat grid; it never snaps. 0.5 is one second", 0.5);
knob("rate", "Rate", "How much quicker than a beat a line extends or the scene shifts; 0 is exactly one beat, 1 is three times", 0);
knob("count", "Count", "Lines drawn per phase, before the cut", 0.5);

knob("split", "Split", "Where the cut lands, as a fraction of the frame", 0.5);
knob("spread", "Spread", "How far the cut wanders from that, a quarter of the frame either way at full", 0.4);

knob("minThick", "Min", "Thinnest a line can be", 0.12);
knob("maxThick", "Max", "Thickest a line can be", 0.3);

knob("angle", "Angle", "Where the scene is turned to; a full turn across the knob", 0);

/**
 * Advances `phase`, which every tone is shifted by on the way out.
 *
 * Zero leaves the picture exactly as composed, and that is the point of the
 * strict wrap in renderPoint: at rest this control does nothing at all rather
 * than something very small.
 *
 * A warning about turning it up, because the tones are levels and the shift is
 * cyclic, which are not the same kind of thing. Tone 1 and tone 0 are one step
 * apart on a cycle of length 1, so any nonzero phase drives them to the same
 * value — and the white field is exactly the one whose lines are black. Sweep
 * this and the white sections go flat, their ruling swallowed, until the phase
 * comes back around. Every other field keeps its lines throughout. If you want
 * the sweep without that, the fix is in the tone table: no two entries of
 * BG_LEVELS and LINE_LEVELS that need to stay apart may differ by exactly 1.
 */
knob("speed", "Speed", "How fast the tones cycle; 0 leaves them alone", 0);
// Deliberately low. Every mark is flat to its own edge, so this is strictly the
// anti-aliasing on that edge and not shading: at 0.12 the transition is under 1%
// of the frame, which is about half a pixel on a fixture 60 points across —
// enough to keep a near-horizontal edge from stepping, little enough that the
// tones still read as poster-flat. Turn it up only if the fixture is coarser.
knob("soft", "Soft", "Edge softness — this is the anti-aliasing, not shading", 0.12);

knob("hue", "Hue", "Hue everything is tinted", 0.55);
knob("sat", "Sat", "Saturation of that tint; 0 leaves the four tones as greys", 0);
knob("level", "Level", "Overall brightness", 1);

toggle("autoAspect", "Aspect", "Correct for a non-square model", true);

// ----------------------------------------------------------------- the picture
//
// Marks, oldest first, in world coordinates on the scene square. Parallel flat
// arrays rather than objects: these are walked per LED, and the flat form keeps
// the inner loop free of property lookups. Compaction in updateItems preserves
// the order, which is the whole basis of the painter's walk in renderPoint.

var itemKind = [];
var itemAxis = [];   // line: the axis it runs along; paint: the axis it cuts
var itemPos = [];    // line: the perpendicular coordinate; its whole position
var itemBase = [];   // line: the fixed end; paint: the boundary
var itemLen = [];    // line: how far it reaches once fully extended
var itemDir = [];    // line: which way it grows; paint: which side it covers
var itemHalf = [];   // line: half its thickness
var itemBorn = [];   // line: musical time, in beats, it started extending
var itemOd = [];     // line: how far it overruns each end while being laid down
var itemCut = [];    // line: the beat its phase was cut, or Infinity if it has not been
var itemLevel = [];  // the flat tone it lays down
var itemN = 0;

// Scratch, written by updateItems' first pass and read by its second: whether
// each mark survives, whether it is drawn, and where it landed on screen.
var itemKeep = [];
var itemDraw = [];
var itemSLo = [];
var itemSHi = [];
var itemSPos = [];

// This frame's visible marks in screen coordinates, rebuilt by updateItems so
// renderPoint is a straight walk with no camera arithmetic in it. A paint keeps
// its boundary in drawLo and the side it covers in drawDir; a line uses drawLo
// and drawHi for its extent and leaves drawDir alone.
var drawKind = [];
var drawAxis = [];
var drawPos = [];
var drawLo = [];
var drawHi = [];
var drawHalf = [];
var drawDir = [];
var drawLevel = [];
var drawN = 0;

// ------------------------------------------------------------------ the clock

/**
 * The show's beat grid, free-running and drifting onto it. See the header.
 *
 * Resolved once at load. The guard is not for a missing controller — a Follower
 * with no controller to follow is a supported case that free-runs — but for the
 * package itself being absent, which would otherwise throw here and take the
 * whole script down to a blank pattern rather than a merely unsynced one.
 */
var clock = null;
try {
  clock = new (Java.type("com.starcats.blinkydome.PrimaryController$Follower"))();
} catch (e) {
  clock = null;
}

/** Stands in for the Follower's own beat count when there is no Follower. */
var freeBeats = 0;

/** This frame's position on that clock, in beats. Everything is timed off it. */
var beats = 0;

/**
 * The last beat the state machine has acted on.
 *
 * Compared with `<` rather than `!==` because the Follower's drift correction
 * can walk the clock back over a boundary it has just crossed. Re-crossing must
 * not spawn a second line on the same beat, so only forward crossings fire; a
 * beat retreated over is simply not delivered twice.
 */
var lastBeat = -1;

// ------------------------------------------------------------------ the phase

var phaseState = TOP;
var phaseStart = 0;
var phaseCount = 3;
var phaseSplit = 0.5;   // as a fraction of the frame, not of the scene square

// The tone of the field the current phase is drawing into, and so of its lines.
var sectionIndex = BASE_INDEX;
var sectionLevel = LINE_LEVELS[BASE_INDEX];

// What endShift worked out for the phase after it. Held here rather than
// returned so that a phase change, like a frame, allocates nothing.
var nextState = TOP;
var nextBandLo = 0;
var nextBandHi = 1;

// The lines of the current phase, planned in full when the phase begins so that
// they can be guaranteed not to overlap, and revealed one per beat.
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
var axisOver = [0, 0];    // and how far a line being drawn overruns each end of it
var cosT = 1;
var sinT = 0;

/** Cycles 0..1 under the Speed knob; every tone leaves shifted by it. */
var phase = 0;

/** The starting ground, phase-shifted; refreshed each frame by updateItems. */
var groundLevel = BG_LEVELS[BASE_INDEX];
var rateMul = 1;
var softW = 0.02;
var thickLo = 0.01;
var thickHi = 0.03;
var started = false;

/**
 * The engine's own constructor hook, called once when the script loads and again
 * whenever the model changes.
 *
 * It must not read a knob. This runs from initModel, which is well before the
 * engine binds the control values into scope, so `count` and friends are simply
 * not defined yet and touching one is a fatal error. All it does is ask for a
 * restart; resetScene does the real work on the next frame, by which time both
 * the knobs and the new model are there to be read.
 */
function init() {
  started = false;
}

/**
 * Start the composition over, here and now.
 *
 * The clock is not rewound with it. It belongs to the show rather than to this
 * pattern — rewinding it would put the scene back in step with a grid that has
 * moved on — so the state machine is baselined onto wherever it has got to
 * instead. Everything timed in beats, which is everything, is relative to that
 * baseline, so a restart at beat 4000 composes exactly as one at beat 0.
 */
function resetScene() {
  itemN = 0;
  drawN = 0;
  var beat = Math.floor(beats);
  // One short, so the first crossing in preRender delivers this beat rather than
  // skipping it and leaving the opening phase a line down.
  lastBeat = beat - 1;
  shiftStart = beats;
  camU = camV = camBaseU = camBaseV = 0;
  shiftDU = shiftDV = 0;
  phase = 0;
  sectionIndex = BASE_INDEX;
  sectionLevel = LINE_LEVELS[BASE_INDEX];
  // The whole frame is fair game for the opening phase, since nothing has been
  // cut yet and there is no field to draw into.
  beginPhase(beat, TOP, 0, 1);
  started = true;
}

/** Smoothstep. Both the extending and the shifting run on it. */
function ease(p) {
  return p * p * (3 - 2 * p);
}

/**
 * A tone, moved around the cycle by the current phase.
 *
 * Applied to each mark's own tone rather than to the finished pixel, which for
 * everything but the hairline of anti-aliasing at a border is the same
 * arithmetic — inside a mark the pixel *is* that mark's tone. At a border it is
 * the difference between right and wrong. Shifting afterwards would blend the
 * two original tones and then wrap the blend, and wherever the wrap fell inside
 * that blend the seam would sweep the whole range and draw a bright-then-dark
 * outline around every shape in the picture. Shifting first means the seam only
 * ever interpolates between the two tones actually being shown.
 *
 * It is also a good deal cheaper here: a few dozen marks a frame rather than
 * once for every LED.
 *
 * The wrap is strict rather than a floor, so a phase of zero is an exact
 * identity and tone 1 stays white instead of dropping to black at rest.
 */
function shiftTone(v) {
  v += phase;
  return v > 1 ? v - 1 : v;
}

/** A half-thickness somewhere in the range the two knobs bracket. */
function randomHalf() {
  return (thickLo + Math.random() * (thickHi - thickLo)) * 0.5;
}

// -------------------------------------------------------------- phase changes

/**
 * Start a phase in `state`, placing its lines somewhere in the field the last
 * cut opened up.
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

  var total = 0;
  for (i = 0; i < n; ++i) {
    planW[i] = randomHalf() * 2;
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

/** Reserve the next slot, dropping the oldest mark if the list is full. */
function allocItem() {
  if (itemN >= MAX_ITEMS) {
    for (var i = 1; i < itemN; ++i) {
      itemKind[i - 1] = itemKind[i];
      itemAxis[i - 1] = itemAxis[i];
      itemPos[i - 1] = itemPos[i];
      itemBase[i - 1] = itemBase[i];
      itemLen[i - 1] = itemLen[i];
      itemDir[i - 1] = itemDir[i];
      itemHalf[i - 1] = itemHalf[i];
      itemBorn[i - 1] = itemBorn[i];
      itemOd[i - 1] = itemOd[i];
      itemCut[i - 1] = itemCut[i];
      itemLevel[i - 1] = itemLevel[i];
    }
    --itemN;
  }
  return itemN++;
}

/**
 * Lay a line along `axis`, at `pos` across it, growing from `base` in `dir`.
 *
 * Everything is in world coordinates, which is the whole trick: from here on the
 * mark never moves, and every shift is the camera moving instead.
 */
function addLine(axis, pos, base, len, dir, half, born, od, cut, lvl) {
  var slot = allocItem();
  itemKind[slot] = KIND_LINE;
  itemAxis[slot] = axis;
  itemPos[slot] = pos;
  itemBase[slot] = base;
  itemLen[slot] = len;
  itemDir[slot] = dir;
  itemHalf[slot] = half;
  itemBorn[slot] = born;
  itemOd[slot] = od;
  itemCut[slot] = cut;
  itemLevel[slot] = lvl;
}

/** Flood everything past `bound` on `axis`, on the `dir` side of it. */
function addPaint(axis, bound, dir, lvl, od, born) {
  var slot = allocItem();
  itemKind[slot] = KIND_PAINT;
  itemAxis[slot] = axis;
  itemBase[slot] = bound;
  itemDir[slot] = dir;
  itemOd[slot] = od;
  itemBorn[slot] = born;
  itemCut[slot] = Infinity;
  itemLevel[slot] = lvl;
}

/**
 * Send in the field that the coming cut will open onto — a beat before the cut.
 *
 * A field is a half-plane, so it has no far end to hide, but it does have a
 * boundary, and dropping that boundary onto the frame's far edge at the moment
 * of the cut is a pop: square-on the edge is off screen and nothing shows, but a
 * turned frame reaches past it into the corner, and a whole triangle of new tone
 * would appear out of nowhere. So the field starts an overrun further out, where
 * even a turned frame cannot see it, and slides that overrun off before it is
 * needed. It has to be sent early to do this — hence a beat ahead — and it
 * arrives exactly as the cut lands, boundary already on the edge and still.
 *
 * Square-on, the whole slide happens beyond the frame and is invisible; the
 * field still begins to enter the picture precisely on the cut. Turned, the
 * corner fills gradually instead of blinking. Either way the boundary is where
 * it should be by the time it matters, so nothing downstream — the split, the
 * camera, the cut line — knows this happened.
 *
 * One useful side effect: the field reaches the boundary just as the phase's
 * lines are trimmed back to it, and it already covers everything past that
 * point, so the trim has nothing left to reveal and the snap is seamless.
 */
function beginField(beat) {
  var axis = STATE_AXIS[phaseState];
  var flip = STATE_FLIP[phaseState];
  var dir = flip ? -1 : 1;
  var edge = phaseBase(axis, flip) + dir * axisSpan[axis];

  // Never the tone already on screen, or the cut would open onto its own ground
  // and the whole thing it is there to announce would be invisible.
  var pick = (sectionIndex + 1 + Math.floor(Math.random() * 3)) % 4;
  addPaint(axis, edge, dir, BG_LEVELS[pick], axisOver[axis], beat);
  sectionIndex = pick;
  sectionLevel = LINE_LEVELS[pick];
}

/** Where this phase's lines start from, in world coordinates. */
function phaseBase(axis, flip) {
  return (flip ? 1 - axisMargin[axis] : axisMargin[axis]) + (axis === 0 ? camU : camV);
}

/** Put the k'th planned line into the picture, at the edge, with no length yet. */
function spawnLine(k, beat) {
  var axis = STATE_AXIS[phaseState];
  var flip = STATE_FLIP[phaseState];
  addLine(
    axis,
    planPos[k] + (axis === 0 ? camV : camU),
    // Edge of frame to edge of frame, so the cut that follows is exactly the
    // fraction of the frame the split asks for. The overdraw hangs off either
    // end of that and is not part of it.
    phaseBase(axis, flip),
    axisSpan[axis],
    flip ? -1 : 1,
    planHalf[k],
    // The beat itself rather than the current time, so a long frame cannot let a
    // line start late and drift off the grid.
    beat,
    axisOver[axis],
    Infinity,
    sectionLevel
  );
}

/**
 * Strike the cut and set the camera moving.
 *
 * All three things that happen here sit at the same world coordinate — the far
 * edge of everything this phase drew. The picture is about to come apart along
 * it, so it gets, in this order: the field flooding everything beyond it, then
 * the cut line laid across it. Order is what makes the cut read as a cut rather
 * than as an edge: the paint buries the ends of the lines that ran up to it, and
 * the cut line goes on top of the paint, whole and at once, so the opening
 * arrives already marked instead of growing a border.
 *
 * The distance is fixed by where that edge has to end up: content covers the
 * frame edge to edge, and it has to come to rest covering the near edge to the
 * split, so the camera travels the rest of the frame — 1 - split of it.
 */
function beginShift(beat) {
  var axis = STATE_AXIS[phaseState];
  var flip = STATE_FLIP[phaseState];
  var dir = flip ? -1 : 1;
  var perp = 1 - axis;

  var edge = phaseBase(axis, flip) + dir * axisSpan[axis];

  // Everything still uncut belongs to the phase now ending — nothing else can,
  // since every earlier phase was cut when it ended. Recording the beat here is
  // what lets a line work out, on its own, that its leading end is now the
  // boundary and its trailing overdraw is on its way out.
  for (var i = itemN - 1; i >= 0; --i) {
    if (itemKind[i] !== KIND_LINE) {
      continue;
    }
    if (isFinite(itemCut[i])) {
      break;
    }
    itemCut[i] = beat;
  }

  // The cut line keeps its overdraw for good rather than being trimmed to the
  // frame, because nothing ever cuts across *it* — so it is carried as a line
  // that is simply longer, already cut, with the overrun folded into its base
  // and length. Otherwise turning the scene would show its two ends stopping
  // inside the picture, which is exactly what the overdraw exists to prevent.
  var od = axisOver[perp];
  addLine(
    perp,
    edge,
    phaseBase(perp, false) - od,
    axisSpan[perp] + 2 * od,
    1,
    randomHalf(),
    beat,
    0,
    // Already cut, so it arrives at its full length rather than extending into
    // it: the cut is struck, not drawn.
    -1,
    sectionLevel
  );

  var dist = axisSpan[axis] * (1 - phaseSplit) * dir;
  shiftDU = axis === 0 ? dist : 0;
  shiftDV = axis === 1 ? dist : 0;
  shiftStart = beat;
}

/**
 * Bank the shift and pick what follows it.
 *
 * The next phase runs on the perpendicular axis, its direction a coin flip, and
 * it draws into the band the cut just opened — which is the split to the far
 * edge, or its mirror if the lines were growing the other way.
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
 * and then one of cutting and shifting, and the beat after that belongs to the
 * next phase.
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
    // The last line of the phase shares its beat with the field being sent in
    // for the cut that follows. Spawned first, so it still belongs to the tone
    // it was composed against rather than the one arriving.
    if (k === phaseCount - 1) {
      beginField(beat);
    }
  } else {
    beginShift(beat);
  }
}

// ------------------------------------------------------------------ per frame

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;

  // Before anything reads it, and before resetScene can need to baseline onto
  // it. The Follower does its own controller lookup, so there is no tempo
  // tracking and no wiring in this script at all.
  //
  // Fed the capped dt rather than the raw frame time, which matters more here
  // than it does to an animation that merely reads a phase. A stall — a project
  // load, a model rebuild — would otherwise arrive as one frame worth several
  // beats, and the loop below would spawn every one of those beats' lines at
  // once and run the state machine through whole phases nobody saw. Capping
  // leaves the clock behind the grid instead, which is precisely the error the
  // Follower exists to close, and it closes it over Sync.
  var freeBpm = lerp(40, 200, clamp(fallbackBpm, 0, 1));
  if (clock !== null) {
    // Exponential, so the knob's center is one second and each half of it is a
    // decade either side. A linear 0.1..10 would spend nine tenths of its travel
    // above a second, where the differences stop being visible.
    clock.loop(dt * 1000, freeBpm, 0.1 * Math.pow(100, clamp(sync, 0, 1)));
    beats = clock.getBeats();
  } else {
    freeBeats += dt * freeBpm / 60;
    beats = freeBeats;
  }

  updateFrame(model);

  rateMul = 1 + 2 * clamp(rate, 0, 1);
  softW = lerp(0.002, 0.05, soft) * axisSpan[1];
  var a = lerp(THICK_MIN, THICK_MAX, clamp(minThick, 0, 1)) * axisSpan[1];
  var b = lerp(THICK_MIN, THICK_MAX, clamp(maxThick, 0, 1)) * axisSpan[1];
  thickLo = a < b ? a : b;
  thickHi = a < b ? b : a;

  if (!started) {
    resetScene();
  }

  // Set, not accumulated: the knob is the angle itself, so moving it puts the
  // scene where it says rather than changing how fast it drifts from here.
  var theta = clamp(angle, 0, 1) * TAU;
  cosT = Math.cos(theta);
  sinT = Math.sin(theta);

  // Kept inside 0..1 so the wrap in renderPoint only ever has to fire once, and
  // so it cannot drift into the range where a float stops resolving small steps.
  phase += clamp(speed, 0, 1) * PHASE_RATE * dt;
  phase -= Math.floor(phase);

  // A frame long enough to span two beats still gets both, in order.
  var beat = Math.floor(beats);
  while (lastBeat < beat) {
    onBeat(++lastBeat);
  }

  var q = clamp((beats - shiftStart) * rateMul, 0, 1);
  camU = camBaseU + shiftDU * ease(q);
  camV = camBaseV + shiftDV * ease(q);

  updateItems();
}

/**
 * Work out where the frame sits on the scene square.
 *
 * The square circumscribes the frame, which is what makes the world isotropic:
 * one unit means the same distance along either axis however oblong the model
 * is, so a rotation cannot skew and a line has one thickness rather than two.
 * Everything specified as a fraction of the frame — splits, line placements,
 * thicknesses — is converted through the margin and span this leaves.
 *
 * The square's actual size cancels out of every one of those conversions, so it
 * is the aspect ratio doing the work here and not the circumscribing.
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

  // The asked-for half of a span, or as much more as it takes to put a line's
  // end past the half-diagonal a turned frame can reach. See OVERDRAW.
  for (var a = 0; a < 2; ++a) {
    var wanted = OVERDRAW * axisSpan[a];
    var needed = 0.5 - axisSpan[a] * 0.5;
    axisOver[a] = wanted > needed ? wanted : needed;
  }
}

/**
 * Age every mark, forget the ones that can no longer be seen, and leave the rest
 * in screen coordinates for the renderer.
 *
 * What can still be seen is decided exactly rather than approximated, by walking
 * the list newest first and carrying the *uncovered window*: the region no paint
 * seen so far has already filled. A paint is an axis-aligned half-plane, so the
 * uncovered region always stays an axis-aligned rectangle — intersecting it with
 * one more half-plane just pushes one of its four sides in. A mark that misses
 * that window is buried, however recently it was laid down, and when the window
 * closes entirely every remaining mark is buried and the walk can stop dead.
 *
 * All of this is done in world coordinates, and that is what makes it sound.
 * Marks never move relative to one another — only the camera moves, and it moves
 * all of them together — so the window is a fixed fact about the picture and not
 * something that can be invalidated by a later shift. Dropping a buried mark is
 * therefore permanent-safe: anything a paint hides sits on the far side of that
 * paint's boundary, so if the boundary is ever carried off the frame, whatever it
 * was hiding went with it.
 *
 * Survivors are compacted in place rather than filtered into a new array, so a
 * pattern that runs for hours allocates nothing after load.
 */
function updateItems() {
  var i;

  groundLevel = shiftTone(BG_LEVELS[BASE_INDEX]);

  // The frame in world coordinates, at each of the two margins: the loose one
  // decides what is worth keeping against a reversal, the tight one what is
  // actually drawn this frame.
  var lu0 = camU - PRUNE_MARGIN, lu1 = camU + 1 + PRUNE_MARGIN;
  var lv0 = camV - PRUNE_MARGIN, lv1 = camV + 1 + PRUNE_MARGIN;
  var tu0 = camU - EDGE_MARGIN, tu1 = camU + 1 + EDGE_MARGIN;
  var tv0 = camV - EDGE_MARGIN, tv1 = camV + 1 + EDGE_MARGIN;

  var wu0 = -Infinity, wu1 = Infinity;
  var wv0 = -Infinity, wv1 = Infinity;
  var floor = 0;
  var paintBound = 0;

  for (i = itemN - 1; i >= 0; --i) {
    // The window clipped to the frame. Once this is empty nothing older than
    // this mark can ever show through again.
    var ku0 = wu0 > lu0 ? wu0 : lu0, ku1 = wu1 < lu1 ? wu1 : lu1;
    var kv0 = wv0 > lv0 ? wv0 : lv0, kv1 = wv1 < lv1 ? wv1 : lv1;
    if (ku0 > ku1 || kv0 > kv1) {
      floor = i + 1;
      break;
    }

    var axis = itemAxis[i];
    var dir = itemDir[i];
    var bu0, bu1, bv0, bv1;   // the mark's own world bounds
    var extent = true;

    if (itemKind[i] === KIND_PAINT) {
      // A field slides the last of its overrun off before it is due, so that by
      // the time it is anyone's business it is already sitting on the boundary.
      var pq = (beats - itemBorn[i]) * rateMul;
      if (pq < 0) { pq = 0; } else if (pq > 1) { pq = 1; }
      paintBound = itemBase[i] + dir * itemOd[i] * (1 - pq);

      bu0 = bv0 = -Infinity;
      bu1 = bv1 = Infinity;
      if (dir > 0) {
        if (axis === 0) { bu0 = paintBound; } else { bv0 = paintBound; }
      } else {
        if (axis === 0) { bu1 = paintBound; } else { bv1 = paintBound; }
      }
      itemSLo[i] = paintBound - (axis === 0 ? camU : camV);
      itemSHi[i] = itemSLo[i];
      itemSPos[i] = 0;
    } else {
      // A line's two ends live on different clocks.
      //
      // Until its phase is cut it is being laid down, overrunning both ends of
      // the frame: it starts as nothing out beyond the near edge and its leading
      // end sweeps the whole overrun length. The cut then takes that leading end
      // instantly back to the boundary — that snap is the picture coming apart,
      // and it wants to be a cut and not a retraction. The trailing overrun has
      // no such reason to be abrupt, so it eases out linearly across the shift,
      // leaving the line exactly frame-length once everything settles.
      var base = itemBase[i];
      var od = itemOd[i];
      var trail, head;

      if (beats < itemCut[i]) {
        var p = (beats - itemBorn[i]) * rateMul;
        var grown = p >= 1 ? 1 : ease(p < 0 ? 0 : p);
        extent = grown > 0;
        trail = base - dir * od;
        head = trail + dir * (itemLen[i] + 2 * od) * grown;
      } else {
        var q = (beats - itemCut[i]) * rateMul;
        if (q < 0) { q = 0; } else if (q > 1) { q = 1; }
        trail = base - dir * od * (1 - q);
        head = base + dir * itemLen[i];
      }

      var alo = trail < head ? trail : head;
      var ahi = trail < head ? head : trail;
      var plo = itemPos[i] - itemHalf[i];
      var phi = itemPos[i] + itemHalf[i];

      if (axis === 0) {
        bu0 = alo; bu1 = ahi; bv0 = plo; bv1 = phi;
        itemSPos[i] = itemPos[i] - camV;
      } else {
        bv0 = alo; bv1 = ahi; bu0 = plo; bu1 = phi;
        itemSPos[i] = itemPos[i] - camU;
      }
      var camA = axis === 0 ? camU : camV;
      itemSLo[i] = alo - camA;
      itemSHi[i] = ahi - camA;
    }

    itemKeep[i] = bu1 >= ku0 && bu0 <= ku1 && bv1 >= kv0 && bv0 <= kv1;

    var du0 = wu0 > tu0 ? wu0 : tu0, du1 = wu1 < tu1 ? wu1 : tu1;
    var dv0 = wv0 > tv0 ? wv0 : tv0, dv1 = wv1 < tv1 ? wv1 : tv1;
    itemDraw[i] = extent && du0 <= du1 && dv0 <= dv1 &&
      bu1 >= du0 && bu0 <= du1 && bv1 >= dv0 && bv0 <= dv1;

    // Older marks see the window with this paint's half-plane taken out of it.
    // Taken where the boundary is *now*, mid-slide or not — and a field only
    // ever covers more as it settles, so a mark buried under one stays buried
    // and dropping it is still permanent-safe.
    if (itemKind[i] === KIND_PAINT) {
      if (dir > 0) {
        if (axis === 0) { if (paintBound < wu1) { wu1 = paintBound; } }
        else { if (paintBound < wv1) { wv1 = paintBound; } }
      } else {
        if (axis === 0) { if (paintBound > wu0) { wu0 = paintBound; } }
        else { if (paintBound > wv0) { wv0 = paintBound; } }
      }
    }
  }

  var keep = 0;
  drawN = 0;

  for (i = floor; i < itemN; ++i) {
    if (!itemKeep[i]) {
      continue;
    }

    if (itemDraw[i]) {
      drawKind[drawN] = itemKind[i];
      drawAxis[drawN] = itemAxis[i];
      drawPos[drawN] = itemSPos[i];
      drawLo[drawN] = itemSLo[i];
      drawHi[drawN] = itemSHi[i];
      drawHalf[drawN] = itemHalf[i];
      drawDir[drawN] = itemDir[i];
      drawLevel[drawN] = shiftTone(itemLevel[i]);
      ++drawN;
    }

    if (keep !== i) {
      itemKind[keep] = itemKind[i];
      itemAxis[keep] = itemAxis[i];
      itemPos[keep] = itemPos[i];
      itemBase[keep] = itemBase[i];
      itemLen[keep] = itemLen[i];
      itemDir[keep] = itemDir[i];
      itemHalf[keep] = itemHalf[i];
      itemBorn[keep] = itemBorn[i];
      itemOd[keep] = itemOd[i];
      itemCut[keep] = itemCut[i];
      itemLevel[keep] = itemLevel[i];
    }
    ++keep;
  }

  itemN = keep;
}

function renderPoint(point, deltaMs) {
  // Into the scene square: center, correct the aspect, turn by the scene's
  // rotation backwards — rotating where we sample from is rotating what is
  // drawn — and scale so the square is the unit box.
  var cx = (point.xn - 0.5) * aspectX;
  var cy = point.yn - 0.5;
  var u = (cx * cosT + cy * sinT) / sceneR + 0.5;
  var v = (cy * cosT - cx * sinT) / sceneR + 0.5;

  // Newest mark first. Everything here is opaque and flat, so the first one
  // covering this point is the one that shows and the walk stops; the only
  // reason it carries a running total at all is the pixel of anti-aliasing at
  // each border, where a mark is partly covering and lets the rest through.
  var accum = 0;
  var remaining = 1;

  for (var i = drawN - 1; i >= 0; --i) {
    var axis = drawAxis[i];
    var along = axis === 0 ? u : v;
    var cover;

    if (drawKind[i] === KIND_PAINT) {
      cover = 0.5 + drawDir[i] * (along - drawLo[i]) / softW;
    } else {
      // Distance outside the rectangle, as the largest of the three ways of
      // being outside it. Negative inside. Taking the max rather than a true
      // Euclidean distance keeps the ends square, which is what a line arrested
      // exactly at the cut wants.
      var perp = axis === 0 ? v : u;
      var d = Math.abs(perp - drawPos[i]) - drawHalf[i];
      var d0 = drawLo[i] - along;
      if (d0 > d) {
        d = d0;
      }
      var d1 = along - drawHi[i];
      if (d1 > d) {
        d = d1;
      }
      cover = 0.5 - d / softW;
    }

    if (cover <= 0) {
      continue;
    }
    if (cover > 1) {
      cover = 1;
    }

    accum += remaining * cover * drawLevel[i];
    remaining *= 1 - cover;
    if (remaining < 0.004) {
      break;
    }
  }

  // Whatever light got past every mark falls on the ground the picture started
  // from, which is the only thing under all of it. Already phase-shifted, in
  // step with every drawLevel above.
  accum += remaining * groundLevel;

  return hsb(hue * 360, sat * 100, clamp(accum, 0, 1) * level * 100);
}
