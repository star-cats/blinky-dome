/**
 * Static wiring calibration for the Waterfall's individual LED strips.
 *
 * Every strip is indexed from its own first physical point to its last, without
 * relying on global point indices. Its first and last 16 pixels are solid. The
 * pixels between them alternate in 8-pixel black/color blocks, beginning with
 * black so the first checker block cannot merge into the solid top marker.
 *
 * Strip colors repeat by fixture order:
 *
 *   1 red, 2 green, 3 blue, 4 yellow, then repeat.
 *
 * The model hierarchy is indexed once when it changes. Ordinarily the pattern
 * finds the Waterfall-tagged fixture and walks its 40 ordered child components.
 * A flattened Waterfall view is also supported using the fixture's alternating
 * 360/315 point counts.
 */

// knobi's upper bound is exclusive, hence 65 and 33 for useful maxima of 64
// solid-end pixels and 32 pixels per checker block.
knobi("solidPixels", "Solid Ends", "Solid colored pixels at both ends of every strip", 16, 65);
knobi("checkerPixels", "Checker Size", "Pixels in each colored or black block through the bulk", 8, 33);

var WATERFALL_STRIPS = 40;
var LONG_STRIP_PIXELS = 360;
var SHORT_STRIP_PIXELS = 315;
var FLAT_WATERFALL_PIXELS = 20 * (LONG_STRIP_PIXELS + SHORT_STRIP_PIXELS);

var indexedModel = null;
var indexedPointCount = -1;
var stripForPoint = [];
var positionForPoint = [];
var lengthForPoint = [];

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
  lengthForPoint = [];

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
    lengthForPoint[pointIndex] = length;
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
      lengthForPoint[pointIndex] = length;
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
  var length = lengthForPoint[point.index];
  var solid = Math.max(0, solidPixels | 0);
  var block = Math.max(1, checkerPixels | 0);

  // If the two requested end markers overlap, the entire short strip is on.
  var on = position < solid || position >= length - solid;
  if (!on) {
    var bulkPosition = position - solid;
    // The first bulk block is black. Thereafter color and black alternate.
    on = (Math.floor(bulkPosition / block) % 2) === 1;
  }

  if (!on) {
    return rgb(0, 0, 0);
  }

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
