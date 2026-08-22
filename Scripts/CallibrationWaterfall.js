/**
 * Static wiring calibration for the Waterfall's individual LED strips.
 *
 * Every strip is indexed from its own first physical point to its last, without
 * relying on global point indices. The layout begins with a solid colored band
 * and one checker-sized black blanking band. A white group number comes next,
 * followed by another blank band and the alternating black/color checker field
 * through the remainder of the strip.
 *
 * Four consecutive strips are one group. Twenty-four shared pixel positions form
 * a 24x4 display: pixel position is the digit row and strip-within-group is its
 * column. Even-numbered groups put the digit immediately after the leading
 * blank band. Odd-numbered groups put it one digit height plus one black band
 * lower. Both slots are reserved in every group, so adjacent white labels are
 * vertically staggered and cannot run together.
 *
 * Checker colors repeat within every group by strip order:
 *
 *   1 red, 2 green, 3 blue, 4 yellow, then repeat.
 *
 * The leading solid band instead identifies the whole four-strip group: group
 * 0 is red, 1 green, 2 blue, 3 yellow, then that sequence repeats.
 *
 * The model hierarchy is indexed once when it changes. Ordinarily the pattern
 * finds the Waterfall-tagged fixture and walks its 40 ordered child components.
 * A flattened Waterfall view is also supported using the fixture's alternating
 * 360/315 point counts.
 */

// knobi's upper bound is exclusive, hence 65 and 33 for useful maxima of 64
// solid-band pixels and 32 pixels per checker block.
knobi("solidPixels", "Solid Band", "Solid colored pixels at the start of every strip", 40, 65);
knobi("checkerPixels", "Checker Size", "Pixels in each colored or black block through the bulk", 16, 33);

var WATERFALL_STRIPS = 40;
var LONG_STRIP_PIXELS = 360;
var SHORT_STRIP_PIXELS = 315;
var FLAT_WATERFALL_PIXELS = 20 * (LONG_STRIP_PIXELS + SHORT_STRIP_PIXELS);

var DIGIT_HEIGHT = 24;

// Standard seven-segment names: a/g/d are top/middle/bottom, f/e are the
// upper/lower left, and b/c are the upper/lower right. Segment geometry below
// derives its middle and bottom rows from DIGIT_HEIGHT so the font scales as a
// unit rather than leaving old 12-pixel assumptions behind.
var DIGIT_SEGMENTS = [
  "abcdef",  // 0
  "bc",      // 1
  "abdeg",   // 2
  "abcdg",   // 3
  "bcfg",    // 4
  "acdfg",   // 5
  "acdefg",  // 6
  "abc",     // 7
  "abcdefg", // 8
  "abcdfg"   // 9
];

var indexedModel = null;
var indexedPointCount = -1;
var stripForPoint = [];
var positionForPoint = [];

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (indexedModel !== model || indexedPointCount !== model.points.length) {
    indexModel(model);
  }
}

/** Build point-index -> strip/order lookup tables from the model hierarchy. */
function indexModel(model) {
  indexedModel = model;
  indexedPointCount = model.points.length;
  stripForPoint = [];
  positionForPoint = [];

  var roots = [];
  if (hasTag(model, "waterfall")) {
    roots.push(model);
  }

  // On a full installation model the Waterfall is a descendant rather than
  // the pattern's root. LXModel.sub() preserves fixture order.
  var waterfallModels = model.sub("waterfall");
  for (var i = 0; i < waterfallModels.size(); ++i) {
    var candidate = waterfallModels.get(i);
    if (candidate !== model) {
      roots.push(candidate);
    }
  }

  var strips = [];
  if (roots.length > 0) {
    for (var r = 0; r < roots.length; ++r) {
      collectLeafModels(roots[r], strips);
    }
  } else {
    // A model view may retain the fixture children but not the parent's tag.
    collectLeafModels(model, strips);
  }

  strips = uniqueModels(strips);

  // Some views deliberately flatten their hierarchy. The Waterfall has an
  // unambiguous 13,500-point layout: 360,315 repeated twenty times.
  if (strips.length === 1 && strips[0] === model &&
      model.points.length === FLAT_WATERFALL_PIXELS) {
    indexFlatWaterfall(model.points);
    return;
  }

  for (var strip = 0; strip < strips.length; ++strip) {
    indexStrip(strips[strip].points, strip);
  }
}

/** Recursively collect ordered leaf models, which are the fixture components. */
function collectLeafModels(model, output) {
  if (model.children.length === 0) {
    if (model.points.length > 0) {
      output.push(model);
    }
    return;
  }
  for (var i = 0; i < model.children.length; ++i) {
    collectLeafModels(model.children[i], output);
  }
}

/** Remove hierarchy aliases without disturbing fixture order. */
function uniqueModels(models) {
  var result = [];
  var firstPointSeen = {};
  for (var i = 0; i < models.length; ++i) {
    var points = models[i].points;
    if (points.length === 0) {
      continue;
    }
    var key = "p" + points[0].index;
    if (!firstPointSeen[key]) {
      firstPointSeen[key] = true;
      result.push(models[i]);
    }
  }
  return result;
}

function indexStrip(points, stripIndex) {
  var length = points.length;
  for (var position = 0; position < length; ++position) {
    var pointIndex = points[position].index;
    stripForPoint[pointIndex] = stripIndex;
    positionForPoint[pointIndex] = position;
  }
}

function indexFlatWaterfall(points) {
  var offset = 0;
  for (var strip = 0; strip < WATERFALL_STRIPS; ++strip) {
    var length = (strip % 2 === 0) ? LONG_STRIP_PIXELS : SHORT_STRIP_PIXELS;
    var end = offset + length;
    for (var i = offset; i < end; ++i) {
      var pointIndex = points[i].index;
      stripForPoint[pointIndex] = strip;
      positionForPoint[pointIndex] = i - offset;
    }
    offset = end;
  }
}

function hasTag(model, tag) {
  return model.tags.contains(tag);
}

function renderPoint(point, deltaMs) {
  var strip = stripForPoint[point.index];
  if (strip == null) {
    return rgb(0, 0, 0);
  }

  var position = positionForPoint[point.index];
  var solid = Math.max(0, solidPixels | 0);
  var block = Math.max(1, checkerPixels | 0);
  var group = Math.floor(strip / 4);
  var upperDigitStart = solid + block;
  var lowerDigitStart = upperDigitStart + DIGIT_HEIGHT + block;
  var digitStart = (group % 2 === 0) ? upperDigitStart : lowerDigitStart;
  var digitEnd = digitStart + DIGIT_HEIGHT;
  var checkerStart = lowerDigitStart + DIGIT_HEIGHT + block;

  if (position < solid) {
    return groupColor(group);
  } else if (position < digitEnd) {
    if (position >= digitStart && digitPixel(group % 10, position - digitStart, strip % 4)) {
      return rgb(255, 255, 255);
    }
    return rgb(0, 0, 0);
  } else if (position < checkerStart) {
    // The leading blank and whichever digit slot this group does not use are
    // all held black, keeping adjacent group labels visually separate.
    return rgb(0, 0, 0);
  }

  var checkerPosition = position - checkerStart;
  // The checker starts with n black pixels, then n colored pixels.
  if ((Math.floor(checkerPosition / block) % 2) === 0) {
    return rgb(0, 0, 0);
  }

  return stripColor(strip);
}

/** Rasterize one pixel of the fixed-width, DIGIT_HEIGHT x 4 seven-segment font. */
function digitPixel(digit, row, column) {
  var segments = DIGIT_SEGMENTS[digit];
  var middle = Math.floor((DIGIT_HEIGHT - 1) / 2);
  if (row === 0) {
    return segments.indexOf("a") >= 0;
  }
  if (row === middle) {
    return segments.indexOf("g") >= 0;
  }
  if (row === DIGIT_HEIGHT - 1) {
    return segments.indexOf("d") >= 0;
  }
  if (row < middle) {
    return (column === 0 && segments.indexOf("f") >= 0) ||
      (column === 3 && segments.indexOf("b") >= 0);
  }
  return (column === 0 && segments.indexOf("e") >= 0) ||
    (column === 3 && segments.indexOf("c") >= 0);
}

function stripColor(strip) {
  switch (strip % 4) {
    case 0:
      return rgb(255, 0, 0);
    case 1:
      return rgb(0, 255, 0);
    case 2:
      return rgb(0, 0, 255);
    default:
      return rgb(255, 255, 0);
  }
}

function groupColor(group) {
  switch (group % 4) {
    case 0:
      return rgb(255, 0, 0);
    case 1:
      return rgb(0, 255, 0);
    case 2:
      return rgb(0, 0, 255);
    default:
      return rgb(255, 255, 0);
  }
}
