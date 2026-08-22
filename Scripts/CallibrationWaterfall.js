/**
 * Static wiring calibration for the Waterfall's individual LED strips.
 *
 * Every pixel is placed by its geometry, not by its index within a strip. The
 * topmost Y of the whole Waterfall is the datum: a pixel's row is its distance
 * below that datum measured in pixel pitches. Strips that hang lower therefore
 * begin partway down the layout rather than restarting it, so every band, digit
 * row and checker edge lines up horizontally across the full installation even
 * when the strips have different vertical offsets. Misalignment on the physical
 * waterfall is then a real discrepancy between the model and the rig.
 *
 * The layout begins with a solid colored band and one checker-sized black
 * blanking band. A white group number comes next, followed by another blank band
 * and the alternating black/color checker field through the remainder.
 *
 * Four consecutive strips are one group. Twenty-four shared rows form a 24x4
 * display: row is the digit row and strip-within-group is its column. Even
 * groups put the digit immediately after the leading blank band. Odd groups put
 * it one digit height plus one black band lower. Both slots are reserved in
 * every group, so adjacent white labels are staggered and cannot run together.
 *
 * Checker colors repeat within every group by strip order:
 *
 *   1 red, 2 green, 3 blue, 4 yellow, then repeat.
 *
 * The leading solid band instead identifies the whole four-strip group: group
 * 0 is red, 1 green, 2 blue, 3 yellow, then that sequence repeats.
 *
 * Only Waterfall-tagged geometry is considered. Strip order comes from the
 * fixture hierarchy, which is what identifies wiring; vertical placement comes
 * from the model, which is what is being calibrated.
 */

// knobi's upper bound is exclusive, hence 65 and 33 for useful maxima of 64
// solid-band pixels and 32 pixels per checker block.
knobi("solidPixels", "Solid Band", "Solid colored pixels at the start of every strip", 40, 65);
knobi("checkerPixels", "Checker Size", "Pixels in each colored or black block, and the blanking bands", 16, 33);

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
var indexedGeneration = -1;
var stripForPoint = [];
var rowForPoint = [];

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  // LXModel.bang() bumps the generation whenever geometry moves while the point
  // count and structure stay the same, which is exactly what editing a per-strip
  // trim offset does. Identity and point-count checks are both blind to it, so
  // without the generation test the rows stay stale until something else forces
  // a rebuild.
  if (indexedModel !== model ||
      indexedPointCount !== model.points.length ||
      indexedGeneration !== model.getGeneration()) {
    indexModel(model);
  }
}

/** Build point-index -> strip/row lookup tables from the Waterfall geometry. */
function indexModel(model) {
  indexedModel = model;
  indexedPointCount = model.points.length;
  indexedGeneration = model.getGeneration();
  stripForPoint = [];
  rowForPoint = [];

  var strips = collectWaterfallStrips(model);
  if (strips.length === 0) {
    return;
  }

  var topY = waterfallTopY(strips);
  var pitch = pixelPitch(strips);

  for (var strip = 0; strip < strips.length; ++strip) {
    indexStrip(strips[strip].points, strip, topY, pitch);
  }
}

/** Ordered leaf components beneath every Waterfall-tagged model, and nothing else. */
function collectWaterfallStrips(model) {
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
  for (var r = 0; r < roots.length; ++r) {
    collectLeafModels(roots[r], strips);
  }
  return uniqueModels(strips);
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

/** The datum: highest point anywhere in the Waterfall. Row 0 sits here. */
function waterfallTopY(strips) {
  var top = null;
  for (var s = 0; s < strips.length; ++s) {
    var points = strips[s].points;
    for (var i = 0; i < points.length; ++i) {
      if (top === null || points[i].y > top) {
        top = points[i].y;
      }
    }
  }
  return top;
}

/**
 * Median per-strip vertical spacing. The median rather than the mean so that a
 * strip modelled flat, or one stray duplicate point, cannot skew every row.
 */
function pixelPitch(strips) {
  var pitches = [];
  for (var s = 0; s < strips.length; ++s) {
    var points = strips[s].points;
    if (points.length < 2) {
      continue;
    }
    var span = Math.abs(points[points.length - 1].y - points[0].y);
    if (span > 0) {
      pitches.push(span / (points.length - 1));
    }
  }
  if (pitches.length === 0) {
    return 0;
  }
  pitches.sort(function (a, b) { return a - b; });
  return pitches[Math.floor(pitches.length / 2)];
}

/**
 * Rows are measured down from the shared datum. Degenerate geometry, where no
 * strip has any vertical extent, falls back to per-strip ordering so the
 * pattern still renders something legible.
 */
function indexStrip(points, stripIndex, topY, pitch) {
  for (var i = 0; i < points.length; ++i) {
    var pointIndex = points[i].index;
    stripForPoint[pointIndex] = stripIndex;
    rowForPoint[pointIndex] = (pitch > 0)
      ? Math.max(0, Math.round((topY - points[i].y) / pitch))
      : i;
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

  var row = rowForPoint[point.index];
  var solid = Math.max(0, solidPixels | 0);
  var block = Math.max(1, checkerPixels | 0);
  var group = Math.floor(strip / 4);
  var upperDigitStart = solid + block;
  var lowerDigitStart = upperDigitStart + DIGIT_HEIGHT + block;
  var digitStart = (group % 2 === 0) ? upperDigitStart : lowerDigitStart;
  var digitEnd = digitStart + DIGIT_HEIGHT;
  var checkerStart = lowerDigitStart + DIGIT_HEIGHT + block;

  if (row < solid) {
    return groupColor(group);
  } else if (row < digitEnd) {
    if (row >= digitStart && digitPixel(group % 10, row - digitStart, strip % 4)) {
      return rgb(255, 255, 255);
    }
    return rgb(0, 0, 0);
  } else if (row < checkerStart) {
    // The leading blank and whichever digit slot this group does not use are
    // all held black, keeping adjacent group labels visually separate.
    return rgb(0, 0, 0);
  }

  var checkerRow = row - checkerStart;
  // The checker starts with n black pixels, then n colored pixels.
  if ((Math.floor(checkerRow / block) % 2) === 0) {
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
