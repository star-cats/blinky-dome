/**
 * A shape that turns and breathes, printing a copy of itself four times a beat
 * and leaving the copies behind to recede.
 *
 * The shape is a closed contour written as a polar function r(theta), which is
 * what makes the whole thing work: a squircle, a triangle and a five-lobe flower
 * are three such functions, so morphing between them is nothing more exotic than
 * blending two radii. There is no vertex correspondence to solve and no way for
 * the contour to tangle or self-intersect on the way across, which is the usual
 * problem with morphing outlines.
 *
 * Every sixteenth note the live shape is stamped: its exact state — angle,
 * scale, position, where it had got to in the morph, and how bright it was — is
 * frozen into a copy. Copies never update their shape again. They only recede:
 * shrinking, drifting, turning a little further and fading, each on exp(-t), so
 * the trail thins out fast near the head and lingers at the tail rather than
 * marching away in even steps.
 *
 * Only the contour is drawn, as a stroke of its own thickness with an optional
 * halo around it. The interiors are empty, so the trail is seen through itself
 * and a copy twenty deep is still legible behind the ones in front. Depth still
 * decides what happens where two strokes cross: copies stack strictly back to
 * front, newest in front, and rendering runs front to back per LED so the
 * nearer stroke wins the crossing and the pixel can stop early once it is
 * covered.
 *
 * One detail falls out rather than being built. A stamp copies the live shape's
 * brightness and size along with everything else, and the live shape is grey at
 * its resting size except on the beat, where it flashes white and jumps larger.
 * Both fall back fast enough to be gone before the next sixteenth, so of every
 * four stamps the one taken on the beat is frozen white and oversized while the
 * other three are neither. The trail ends up self-marking its own downbeats
 * twice over, and they stay legible as the whole thing recedes.
 */

var TAU = Math.PI * 2;

// Fixed tempo, as specified. Everything is derived from the accumulated clock
// rather than from wall time, so the phase cannot jump when the engine hitches.
var BPM = 120;
var BEAT_SEC = 60 / BPM;
var STAMP_SEC = BEAT_SEC / 4;

/** Copies kept alive. The oldest is dropped when a new stamp needs the room. */
var MAX_SHAPES = 20;

/**
 * Time constant of the beat's size pulse falling back to normal.
 *
 * Deliberately much shorter than the sixteenth between stamps — at 50ms the
 * pulse is 8% of its height by the time the next stamp is taken, so the
 * downbeat's copy is frozen visibly larger and the three after it are not.
 */
var PULSE_TAU = 0.05;

var SHAPE_COUNT = 3;
var SHAPE_SQUIRCLE = 0;
var SHAPE_TRIANGLE = 1;
var SHAPE_FLOWER = 2;

/** Superellipse exponent for the squircle: 2 is a circle, high is a square. */
var SQUIRCLE_N = 4;

/**
 * How deep the flower's lobes cut, as a fraction of its radius. Half what it
 * was, so the flower reads as a rippled circle rather than a star.
 */
var FLOWER_DEPTH = 0.175;
var FLOWER_LOBES = 5;

/**
 * Radius samples per contour.
 *
 * Every shape is baked to a table of radii once, rather than evaluated per LED.
 * A copy's morph state is frozen the moment it is stamped, so its table is built
 * once at stamp time and never again; only the live shape rebuilds each frame.
 * That turns a few hundred thousand pow() and cos() calls a frame into a few
 * hundred, and leaves the inner loop doing nothing but a lookup and a lerp.
 */
var LUT_SIZE = 256;

/** Fraction of each morph stage spent holding the shape before it starts to go. */
var MORPH_HOLD = 0.45;

knob("size", "Size", "Leading shape size, as a fraction of the frame", 0.3);
knob("posx", "X", "Center, horizontal", 0.5);
knob("posy", "Y", "Center, vertical", 0.5);

knob("spin", "Spin", "How fast the leading shape turns; 0.5 is still", 0.57);
knob("breathe", "Breathe", "How much the leading shape's scale swells and shrinks", 0.35);
knob("pulse", "Pulse", "How much bigger the leading shape jumps on the beat; 0.2 is 20%", 0.2);
knob("morph", "Morph", "How fast it works around squircle, triangle, flower", 0.3);

knob("shrink", "Shrink", "How fast a stamped copy shrinks away", 0.4);
knob("fade", "Fade", "How fast a stamped copy fades out", 0.45);
knob("drift", "Drift", "How far a stamped copy travels as it recedes", 0.35);
knob("driftDir", "Drift Dir", "Which way copies travel; 0.25 is straight up", 0.25);
knob("twist", "Twist", "How much further a copy turns as it recedes", 0.55);

knob("thickness", "Thick", "Stroke width of the contour", 0.3);
knob("glow", "Glow", "Halo spreading out from the stroke", 0.35);

knob("base", "Base", "Leading shape's resting grey", 0.65);
knob("flash", "Flash", "How long the beat's flash to white takes to fall back", 0.3);
knob("soft", "Soft", "Edge softness — this is the anti-aliasing", 0.25);
knob("level", "Level", "Overall brightness", 1);

toggle("autoAspect", "Aspect", "Correct for a non-square model", true);

// Each shape is normalized so that all three enclose the same area, worked out
// once at load. Without this the morph reads as a size change as much as a shape
// change: a triangle on a unit circumradius covers less than half the squircle's
// area, and it visibly shrinks and swells as the contour crosses between them.
var shapeNorm = [1, 1, 1];

/**
 * The live shape's radius table, plus one per stamped copy.
 *
 * The copies' tables live in a fixed pool and are written in place, so a pattern
 * that stamps eight times a second forever allocates nothing after load.
 */
var liveLut = [];
var stampLuts = [];

// Stamped copies, as a ring buffer. Parallel flat arrays rather than objects:
// these are walked per LED, and the flat form keeps the inner loop free of
// property lookups.
var stampAge = [];
var stampRot = [];
var stampScale = [];
var stampCx = [];
var stampCy = [];
var stampBright = [];
var stampMaxR = [];
var stampMinR = [];
var stampHead = 0;
var stampCount = 0;

/** Extremes of the table buildLut last wrote. See buildLut. */
var builtMaxR = 1;
var builtMinR = 1;

/**
 * The draw list for this frame: every visible shape, front to back, with the
 * live shape always at index 0. Rebuilt each frame in preRender so renderPoint
 * is a straight walk with no ordering logic in it.
 */
var drawLut = [];
var drawCx = [];
var drawCy = [];
var drawScale = [];
var drawRot = [];
var drawBright = [];
var drawMaxR = [];
var drawMinR = [];
var drawCount = 0;

var clock = 0;
var lastStampIndex = -1;
var liveRot = 0;
var morphPhase = 0;
var liveMaxR = 1;
var liveMinR = 1;
var softWorld = 0.02;
var strokeHalf = 0.02;
var glowAmount = 0;
var glowReach = 0.01;
var strokeBand = 0.04;
var aspectX = 1;

function init() {
  liveLut = new Array(LUT_SIZE);

  stampLuts = new Array(MAX_SHAPES);
  for (var i = 0; i < MAX_SHAPES; ++i) {
    stampLuts[i] = new Array(LUT_SIZE);
  }

  stampAge = new Array(MAX_SHAPES);
  stampRot = new Array(MAX_SHAPES);
  stampScale = new Array(MAX_SHAPES);
  stampCx = new Array(MAX_SHAPES);
  stampCy = new Array(MAX_SHAPES);
  stampBright = new Array(MAX_SHAPES);
  stampMaxR = new Array(MAX_SHAPES);
  stampMinR = new Array(MAX_SHAPES);
  stampHead = 0;
  stampCount = 0;

  var slots = MAX_SHAPES + 1;
  drawLut = new Array(slots);
  drawCx = new Array(slots);
  drawCy = new Array(slots);
  drawScale = new Array(slots);
  drawRot = new Array(slots);
  drawBright = new Array(slots);
  drawMaxR = new Array(slots);
  drawMinR = new Array(slots);

  normalizeShapes();

  clock = 0;
  lastStampIndex = -1;
  liveRot = 0;
  morphPhase = 0;
}

// ---------------------------------------------------------------- the contours
//
// Each is r(theta) for a closed curve about the origin. They only have to be
// continuous and positive; nothing downstream cares what they are.

function shapeRadius(shape, theta) {
  if (shape === SHAPE_SQUIRCLE) {
    // Superellipse |x|^n + |y|^n = 1, solved for r along the ray at theta.
    var c = Math.abs(Math.cos(theta));
    var s = Math.abs(Math.sin(theta));
    return Math.pow(Math.pow(c, SQUIRCLE_N) + Math.pow(s, SQUIRCLE_N), -1 / SQUIRCLE_N);
  }
  if (shape === SHAPE_TRIANGLE) {
    // A regular polygon in polar form: the ray hits a flat face, and the
    // distance to that face is the inradius over the cosine of the angle off
    // the face's normal. Sharp vertices, and continuous everywhere.
    var wedge = TAU / 3;
    var half = wedge / 2;
    var a = theta - Math.floor(theta / wedge) * wedge;
    return Math.cos(half) / Math.cos(a - half);
  }
  // Five lobes: a circle with a cosine ripple around it.
  return 1 + FLOWER_DEPTH * Math.cos(FLOWER_LOBES * theta);
}

/**
 * Scale each contour to a common enclosed area.
 *
 * Area is (1/2) * integral of r^2, so making the mean of r^2 equal across the
 * three is exactly making their areas equal. Measured by sampling rather than
 * derived, so editing a contour above — or adding a fourth — needs no new
 * constant worked out by hand to keep the morph from pulsing.
 */
function normalizeShapes() {
  for (var shape = 0; shape < SHAPE_COUNT; ++shape) {
    var sum = 0;
    for (var i = 0; i < LUT_SIZE; ++i) {
      var r = shapeRadius(shape, i * TAU / LUT_SIZE);
      sum += r * r;
    }
    shapeNorm[shape] = 1 / Math.sqrt(sum / LUT_SIZE);
  }
}

/**
 * Bake the contour at morph position m into a table of radii.
 *
 * m runs 0..3 and wraps: its whole part picks the pair being crossed and its
 * fraction says how far across. The fraction is held flat for the first
 * MORPH_HOLD of each stage and smoothstepped over the rest, so each shape is
 * legible as itself for a while instead of the contour being permanently caught
 * between two things.
 *
 * Leaves the table's smallest and largest radius in builtMinR and builtMaxR.
 * Drawing outlines rather than fills makes both worth having: the renderer
 * rejects a point that is outside the contour's reach *and* one that is deep
 * enough inside to be clear of the stroke, which for a hollow shape is most of
 * the area it covers.
 */
function buildLut(lut, m) {
  var stage = Math.floor(m) % SHAPE_COUNT;
  if (stage < 0) {
    stage += SHAPE_COUNT;
  }
  var next = (stage + 1) % SHAPE_COUNT;

  var frac = m - Math.floor(m);
  var u = clamp((frac - MORPH_HOLD) / (1 - MORPH_HOLD), 0, 1);
  var blend = u * u * (3 - 2 * u);

  var normA = shapeNorm[stage];
  var normB = shapeNorm[next];
  var maxR = 0;
  var minR = Infinity;

  for (var i = 0; i < LUT_SIZE; ++i) {
    var theta = i * TAU / LUT_SIZE;
    var r = lerp(
      shapeRadius(stage, theta) * normA,
      shapeRadius(next, theta) * normB,
      blend
    );
    lut[i] = r;
    if (r > maxR) {
      maxR = r;
    }
    if (r < minR) {
      minR = r;
    }
  }
  builtMaxR = maxR;
  builtMinR = minR;
}

/** Radius at an arbitrary angle, wrapping and interpolating between samples. */
function sampleLut(lut, theta) {
  var turns = theta / TAU;
  turns -= Math.floor(turns);
  var f = turns * LUT_SIZE;
  var i0 = f | 0;
  if (i0 >= LUT_SIZE) {
    i0 = LUT_SIZE - 1;
  }
  var i1 = (i0 + 1) % LUT_SIZE;
  var t = f - i0;
  return lut[i0] + (lut[i1] - lut[i0]) * t;
}

// -------------------------------------------------------------------- stamping

/** Freeze the live shape into the ring, dropping the oldest if it is full. */
function stamp(scale, brightness) {
  var slot = stampHead;
  stampHead = (stampHead + 1) % MAX_SHAPES;
  if (stampCount < MAX_SHAPES) {
    ++stampCount;
  }

  // The copy's contour never changes again, so its table is written once here
  // rather than rebuilt every frame for the rest of its life.
  buildLut(stampLuts[slot], morphPhase);
  stampMaxR[slot] = builtMaxR;
  stampMinR[slot] = builtMinR;

  stampAge[slot] = 0;
  stampRot[slot] = liveRot;
  stampScale[slot] = scale;
  stampCx[slot] = posx;
  stampCy[slot] = posy;
  // Brightness is copied along with the geometry, which is what makes every
  // fourth copy in the trail a white one.
  stampBright[slot] = brightness;
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (liveLut.length === 0) {
    init();
  }

  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;
  clock += dt;

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  liveRot += (spin - 0.5) * 2 * TAU * 0.35 * dt;
  morphPhase += lerp(0.005, 0.12, morph) * dt * SHAPE_COUNT;
  morphPhase -= Math.floor(morphPhase / SHAPE_COUNT) * SHAPE_COUNT;

  // The live contour is the only one that has to be rebuilt, because it is the
  // only one still moving through the morph.
  buildLut(liveLut, morphPhase);
  liveMaxR = builtMaxR;
  liveMinR = builtMinR;

  var beatPhase = (clock / BEAT_SEC);
  beatPhase -= Math.floor(beatPhase);
  var sinceBeat = beatPhase * BEAT_SEC;

  // Grey at rest, white on the beat, falling back exponentially in between.
  var flashTau = lerp(0.03, 0.25, flash);
  var rest = clamp(base, 0, 1);
  var liveBright = rest + (1 - rest) * Math.exp(-sinceBeat / flashTau);

  // Size is the slow breath, then a sharp jump on the beat falling straight back
  // out of it. The two multiply rather than add, so the beat is the same
  // proportional kick wherever the breath has got to.
  var liveScale = lerp(0.05, 0.6, size)
    * (1 + breathe * 0.45 * Math.sin(clock * TAU / (BEAT_SEC * 8)))
    * (1 + pulse * Math.exp(-sinceBeat / PULSE_TAU));
  if (liveScale < 0) {
    liveScale = 0;
  }

  softWorld = lerp(0.004, 0.06, soft);
  strokeHalf = lerp(0.003, 0.055, thickness);
  glowAmount = glow;
  // Reach grows with the amount, so one knob widens and brightens the halo
  // together rather than needing two dialed in agreement.
  glowReach = lerp(0.006, 0.11, glow);
  // How far from the contour anything can still be lit. Four time constants of
  // the halo is under 2% of its peak, which no fixture resolves.
  strokeBand = strokeHalf + softWorld + (glowAmount > 0 ? 4 * glowReach : 0);

  // One stamp per sixteenth. Driven off the index rather than a countdown, so a
  // long frame cannot let the phase slip: the stamp lands on the same grid it
  // would have if every frame had been short.
  var stampIndex = Math.floor(clock / STAMP_SEC);
  if (stampIndex > lastStampIndex) {
    // Only ever one per frame. A frame long enough to span two sixteenths would
    // otherwise stamp two copies in the same state and at the same age, which
    // draws as one copy and wastes a slot out of the twenty.
    lastStampIndex = stampIndex;
    stamp(liveScale, liveBright);
  }

  ageStamps(dt);
  buildDrawList(liveScale, liveBright);
}

/** Advance every copy along its recession. */
function ageStamps(dt) {
  for (var i = 0; i < stampCount; ++i) {
    stampAge[i] += dt;
  }
}

/**
 * Everything visible this frame, front to back.
 *
 * The live shape leads, then the copies newest first. A copy's four recessions
 * all run on exp(-t): shrink and fade decay toward nothing, while drift and
 * twist are the integral of a decaying rate, so they ease out to a limit instead
 * of running away. That is what makes the trail bunch up at its tail rather than
 * spreading evenly.
 */
function buildDrawList(liveScale, liveBright) {
  drawLut[0] = liveLut;
  drawCx[0] = posx;
  drawCy[0] = posy;
  drawScale[0] = liveScale;
  drawRot[0] = liveRot;
  drawBright[0] = liveBright;
  drawMaxR[0] = liveMaxR;
  drawMinR[0] = liveMinR;
  drawCount = 1;

  var shrinkRate = lerp(0.05, 1.6, shrink);
  var fadeTau = lerp(2.4, 0.28, fade);
  var driftDist = lerp(0, 0.55, drift);
  var driftAngle = driftDir * TAU;
  var driftX = Math.cos(driftAngle) * driftDist;
  var driftY = Math.sin(driftAngle) * driftDist;
  var twistAmount = (twist - 0.5) * 2 * TAU * 0.5;

  // The time constant the eased motions settle over. Tied to the fade, so a
  // copy finishes travelling at about the point it becomes invisible rather
  // than still visibly creeping when it is nearly gone.
  var easeTau = fadeTau * 0.8;

  for (var n = 0; n < stampCount; ++n) {
    // Newest first: walk backwards from the slot before the write head.
    var slot = (stampHead - 1 - n + MAX_SHAPES * 2) % MAX_SHAPES;
    var t = stampAge[slot];

    var brightness = stampBright[slot] * Math.exp(-t / fadeTau);
    var scale = stampScale[slot] * Math.exp(-shrinkRate * t);
    if (brightness < 0.002 || scale < 0.002) {
      // Faded or shrunk past anything the fixture can show. Skipping it here
      // costs the renderer nothing and keeps the front-to-back walk short.
      continue;
    }

    var ease = 1 - Math.exp(-t / easeTau);
    var at = drawCount++;
    drawLut[at] = stampLuts[slot];
    drawCx[at] = stampCx[slot] + driftX * ease;
    drawCy[at] = stampCy[slot] + driftY * ease;
    drawScale[at] = scale;
    drawRot[at] = stampRot[slot] + twistAmount * ease;
    drawBright[at] = brightness;
    drawMaxR[at] = stampMaxR[slot];
    drawMinR[at] = stampMinR[slot];
  }
}

function renderPoint(point, deltaMs) {
  var px = point.xn;
  var py = point.yn;

  // Front to back, accumulating what each shape contributes through whatever
  // the shapes in front of it left uncovered. Only the strokes cover anything,
  // so a point inside a contour goes on to see every copy behind it — which is
  // the point of drawing outlines, and the reason both rejection radii below
  // earn their keep.
  var accum = 0;
  var remaining = 1;

  for (var i = 0; i < drawCount; ++i) {
    var scale = drawScale[i];
    var dx = (px - drawCx[i]) * aspectX;
    var dy = py - drawCy[i];
    var distSq = dx * dx + dy * dy;

    // Outside everything this contour can reach.
    var outer = drawMaxR[i] * scale + strokeBand;
    if (distSq > outer * outer) {
      continue;
    }
    // Deep enough inside to be clear of the stroke everywhere around. For a
    // hollow shape this is most of the area it covers, and skipping it here is
    // what keeps an outline no more expensive to draw than a fill was.
    var inner = drawMinR[i] * scale - strokeBand;
    if (inner > 0 && distSq < inner * inner) {
      continue;
    }

    var dist = Math.sqrt(distSq);
    var radius = sampleLut(drawLut[i], Math.atan2(dy, dx) - drawRot[i]) * scale;

    // Distance to the contour, measured along the ray from the center. That is
    // not quite the true distance to the curve — it understates it where the
    // contour runs steeply, so a stroke thickens a little at a flower's waist
    // and a triangle's corners. At this stroke width it reads as weight in the
    // corners rather than as error, and it saves a gradient estimate per shape
    // per LED.
    var offset = Math.abs(dist - radius);

    // The solid stroke, which is the only thing that occludes.
    var coverage = clamp(0.5 - (offset - strokeHalf) / softWorld, 0, 1);

    // The halo outside it. Folded into the gap the stroke leaves rather than
    // added on top, so the core stays exactly the shape's own brightness and
    // turning Glow up spreads light outward instead of blowing the stroke out.
    var value = coverage;
    if (glowAmount > 0 && coverage < 1) {
      var outside = offset - strokeHalf;
      if (outside < 0) {
        outside = 0;
      }
      value += (1 - coverage) * glowAmount * Math.exp(-outside / glowReach);
    }

    if (value <= 0) {
      continue;
    }

    accum += remaining * value * drawBright[i];
    // Only the stroke takes light away from what is behind it. A halo is light,
    // not surface, so it never hides the copies further back.
    remaining *= 1 - coverage;
    if (remaining < 0.004) {
      break;
    }
  }

  if (accum <= 0) {
    return rgb(0, 0, 0);
  }
  return gray(clamp(accum * level, 0, 1) * 100);
}
