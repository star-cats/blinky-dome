/**
 * Wiring and placement calibration for the V3 dome's LED triangles.
 *
 * Companion to CallibrationWaterfall.js, and the same idea: every pixel is
 * addressed by what the model says it is -- which module, which harness, which
 * triangle, how far along that triangle -- so anything that looks wrong on the
 * physical dome is a real disagreement between the rig and the fixture, not an
 * artifact of the pattern.
 *
 * The four modes walk outward from the smallest thing that can be miswired to
 * the largest, and each one isolates a single fixture setting:
 *
 *   0  Group     Every triangle on a harness holds one solid colour. Four flat
 *                colours, no motion. Reads the harness start universe/channel
 *                and the controller IP: a harness on the wrong universe is dark
 *                or wears its neighbour's colour. The Triangle knob shows all
 *                triangles at 0 or isolates one numbered triangle at 1-15.
 *   1  Alignment Every third pixel is lit and the lit set marches forward along
 *                each triangle, with its three edges red, green, and blue.
 *                The Triangle knob applies here too.
 *                The march is built on model order -- strip A, then B, then C,
 *                each running corner to corner -- so it only looks like one
 *                clean lap of the
 *                triangle when that triangle's order and flip parameters match
 *                how it is really wired. A wrong order jumps the march to
 *                another edge; a wrong flip runs one edge backward.
 *   2  Position  A band of colour sweeps up the dome through Y, alternating
 *                with black. Purely geometric, so it ignores wiring entirely: a
 *                triangle whose band edge does not line up with its neighbours
 *                is placed or rolled wrong in the fixture.
 *   3  Module    A beam orbits the dome like a lighthouse and each module
 *                answers only in its own colour. With all five connected the
 *                colours should sweep past in order and each one should hold
 *                together as a fifth of the dome; a module out of sequence has
 *                its yaw or its controller swapped with another.
 *
 * Modules are the fixture instances tagged "module", harnesses come from the
 * "harnessN" tag on each strip, and triangles are the runs of strips sharing a
 * "triangleNN" tag -- all of which Fixtures/v3_quintile_module.lxf emits. On a
 * model with none of that the whole thing degrades to a single module whose
 * triangles are every three consecutive strips, which is still enough to run
 * the chase and the sweep.
 */

// knobi's upper bound is exclusive: 4 gives modes 0-3, 16 gives triangle
// choices 0-15, and 101 gives a useful maximum of 100 cm of band.
knobi("mode", "Mode", "0 group, 1 alignment, 2 position, 3 module", 0, 4);
knobi("triangle", "Triangle", "Group/Alignment triangle: 0 all, 1-15 isolate", 0, 16);
knob("speed", "Speed", "Chase rate in Alignment mode, sweep rate in Position mode", 0.5);
knobi("bandCm", "Band", "Height of one colored band in Position mode, centimetres", 25, 101);
knob("beam", "Beam", "Angular width of the orbiting beam in Module mode", 0.35);
knob("orbit", "Orbit", "Rate the beam sweeps around the dome in Module mode", 0.5);

var MODE_GROUP = 0;
var MODE_ALIGNMENT = 1;
var MODE_POSITION = 2;
var MODE_MODULE = 3;

// Hues, so every mode can dim a colour by simply lowering brightness.
var RED = 0, YELLOW = 60, GREEN = 120, BLUE = 240, MAGENTA = 300;

// Harness 1-4. Reorder to taste -- the wiring drawing draws these groups
// green, blue, orange, red.
var HARNESS_HUES = [BLUE, RED, GREEN, YELLOW];
// Strip/edge 1-3 within every triangle.
var EDGE_HUES = [RED, GREEN, BLUE];
// Module 1-5, in the order the fixtures appear in the structure.
var MODULE_HUES = [RED, GREEN, BLUE, YELLOW, MAGENTA];

var BLACK = rgb(0, 0, 0);
var STRIPS_PER_TRIANGLE = 3;

// One lit pixel every CHASE_PERIOD, stepping this many pixels per second.
var CHASE_PERIOD = 3;
var CHASE_PIXELS_MIN = 1;
var CHASE_PIXELS_MAX = 24;

// Metres per second the Position band climbs, and the tightest band the Band
// knob can ask for -- its zero position, below the 1 cm its whole-centimetre
// steps would otherwise floor at.
var SWEEP_SPEED_MIN = 0.05;
var SWEEP_SPEED_MAX = 1.5;
var BAND_MIN_CM = 0.5;

// Revolutions per minute of the Module beam, on its own Orbit knob, and the
// beam half-width in degrees.
var ORBIT_RPM_MIN = 2.5;
var ORBIT_RPM_MAX = 30.0;
var BEAM_HALF_WIDTH_MIN = 5.0;
var BEAM_HALF_WIDTH_MAX = 60.0;

var indexedModel = null;
var indexedPointCount = -1;
var indexedGeneration = -1;
var moduleForPoint = [];
var harnessForPoint = [];
var triangleForPoint = [];
var edgeForPoint = [];
var slotForPoint = [];
var azimuthForPoint = [];

var chasePhase = 0;
var sweepPhase = 0;
var orbitPhase = 0;

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  // LXModel.bang() bumps the generation when geometry moves while the point
  // count and structure stay put, which is exactly what re-rolling a triangle
  // or re-yawing a module does. Identity and count checks are blind to it.
  if (indexedModel !== model ||
      indexedPointCount !== model.points.length ||
      indexedGeneration !== model.getGeneration()) {
    indexModel(model);
  }
  advance(deltaMs);
}

/** Advance every phase, each wrapped so it cannot drift off into float noise. */
function advance(deltaMs) {
  var seconds = deltaMs / 1000;
  chasePhase = (chasePhase + seconds * lerp(CHASE_PIXELS_MIN, CHASE_PIXELS_MAX, speed)) % CHASE_PERIOD;
  var cycle = 2 * bandMetres();
  sweepPhase = (sweepPhase + seconds * lerp(SWEEP_SPEED_MIN, SWEEP_SPEED_MAX, speed)) % cycle;
  orbitPhase = (orbitPhase + seconds * lerp(ORBIT_RPM_MIN, ORBIT_RPM_MAX, orbit) * 6) % 360;
}

function bandMetres() {
  return Math.max(BAND_MIN_CM, bandCm | 0) / 100;
}

/** Build point-index -> module/harness/triangle/edge/slot/azimuth lookups. */
function indexModel(model) {
  indexedModel = model;
  indexedPointCount = model.points.length;
  indexedGeneration = model.getGeneration();
  moduleForPoint = [];
  harnessForPoint = [];
  triangleForPoint = [];
  edgeForPoint = [];
  slotForPoint = [];
  azimuthForPoint = [];

  var modules = collectModules(model);
  var sumX = 0, sumZ = 0, count = 0;

  for (var m = 0; m < modules.length; ++m) {
    var strips = uniqueModels(collectLeafModels(modules[m], []));
    var triangle = -1;
    var previousKey = null;
    var edge = 0;
    var slot = 0;

    for (var s = 0; s < strips.length; ++s) {
      var strip = strips[s];
      // Triangles are runs of strips sharing a "triangleNN" tag. Without that
      // tag, fall back on the fixed three strips per triangle.
      var key = tagWithPrefix(strip, "triangle");
      var boundary = (key !== null) ? (key !== previousKey) : (s % STRIPS_PER_TRIANGLE === 0);
      if (boundary) {
        ++triangle;
        edge = 0;
        slot = 0;
      } else {
        ++edge;
      }
      previousKey = key;

      var harness = tagNumber(strip, "harness");
      var points = strip.points;
      for (var i = 0; i < points.length; ++i) {
        var index = points[i].index;
        moduleForPoint[index] = m;
        harnessForPoint[index] = harness;
        triangleForPoint[index] = triangle;
        edgeForPoint[index] = edge;
        slotForPoint[index] = slot++;
        sumX += points[i].x;
        sumZ += points[i].z;
        ++count;
      }
    }
  }

  if (count === 0) {
    return;
  }

  // The beam orbits the centre of the lit geometry, not the world origin, so
  // the dome can sit anywhere in a larger installation model.
  var centreX = sumX / count;
  var centreZ = sumZ / count;
  var all = model.points;
  for (var p = 0; p < all.length; ++p) {
    var pointIndex = all[p].index;
    if (moduleForPoint[pointIndex] != null) {
      azimuthForPoint[pointIndex] = degrees360(
        Math.atan2(all[p].z - centreZ, all[p].x - centreX) * 180 / Math.PI
      );
    }
  }
}

/** The module fixtures, in structure order; the whole model if none are tagged. */
function collectModules(model) {
  var modules = [];
  if (model.tags.contains("module")) {
    modules.push(model);
  }
  var tagged = model.sub("module");
  for (var i = 0; i < tagged.size(); ++i) {
    var candidate = tagged.get(i);
    if (candidate !== model) {
      modules.push(candidate);
    }
  }
  return (modules.length > 0) ? uniqueModels(modules) : [model];
}

/** Recursively collect ordered leaf models, which are the fixture components. */
function collectLeafModels(model, output) {
  if (model.children.length === 0) {
    if (model.points.length > 0) {
      output.push(model);
    }
    return output;
  }
  for (var i = 0; i < model.children.length; ++i) {
    collectLeafModels(model.children[i], output);
  }
  return output;
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

/**
 * First tag that extends the prefix, e.g. "triangle11" for "triangle". The
 * length test is what keeps the bare "triangle" tag every strip carries from
 * answering, which would collapse a whole module into one triangle.
 */
function tagWithPrefix(model, prefix) {
  var tags = model.tags;
  for (var i = 0; i < tags.size(); ++i) {
    var tag = tags.get(i);
    if (tag.length > prefix.length && tag.indexOf(prefix) === 0) {
      return tag;
    }
  }
  return null;
}

/** The number carried by a tag like "harness3", or 0 if there is no such tag. */
function tagNumber(model, prefix) {
  var tag = tagWithPrefix(model, prefix);
  if (tag === null) {
    return 0;
  }
  var value = parseInt(tag.substring(prefix.length), 10);
  return isNaN(value) ? 0 : value;
}

function renderPoint(point, deltaMs) {
  var module = moduleForPoint[point.index];
  if (module == null) {
    return BLACK;
  }
  var selectedMode = mode | 0;
  if ((selectedMode === MODE_GROUP || selectedMode === MODE_ALIGNMENT) &&
      !triangleIsSelected(point)) {
    return BLACK;
  }
  switch (selectedMode) {
    case MODE_GROUP:
      return groupColor(point);
    case MODE_ALIGNMENT:
      return alignmentColor(point);
    case MODE_POSITION:
      return positionColor(point, module);
    default:
      return moduleColor(point, module);
  }
}

/** True when the point belongs to the triangle selected within its module. */
function triangleIsSelected(point) {
  var selectedTriangle = triangle | 0;
  return selectedTriangle === 0 ||
    triangleForPoint[point.index] === selectedTriangle - 1;
}

/** Solid colour per harness. Harness numbers are 1-based; 0 means untagged. */
function groupColor(point) {
  var harness = harnessForPoint[point.index];
  var hue = (harness > 0)
    ? HARNESS_HUES[(harness - 1) % HARNESS_HUES.length]
    : 0;
  return hsb(hue, (harness > 0) ? 100 : 0, 100);
}

/** Every third pixel lit, marching through red, green, and blue triangle edges. */
function alignmentColor(point) {
  var slot = slotForPoint[point.index];
  var step = Math.floor(chasePhase);
  if (((slot - step) % CHASE_PERIOD + CHASE_PERIOD) % CHASE_PERIOD !== 0) {
    return BLACK;
  }
  return hsb(EDGE_HUES[edgeForPoint[point.index] % EDGE_HUES.length], 100, 100);
}

/** Horizontal bands of colour and black, climbing through Y. */
function positionColor(point, module) {
  var band = bandMetres();
  var cell = Math.floor((point.y - sweepPhase) / band);
  if ((((cell % 2) + 2) % 2) === 0) {
    return BLACK;
  }
  return hsb(MODULE_HUES[module % MODULE_HUES.length], 100, 100);
}

/** One beam of the module's colour, orbiting the dome's vertical axis. */
function moduleColor(point, module) {
  var halfWidth = lerp(BEAM_HALF_WIDTH_MIN, BEAM_HALF_WIDTH_MAX, beam);
  var offset = angleBetween(azimuthForPoint[point.index], orbitPhase);
  if (offset >= halfWidth) {
    return BLACK;
  }
  return hsb(MODULE_HUES[module % MODULE_HUES.length], 100, 100 * (1 - offset / halfWidth));
}

function degrees360(degrees) {
  return ((degrees % 360) + 360) % 360;
}

/** Smallest angle between two bearings, 0-180 degrees. */
function angleBetween(a, b) {
  var delta = degrees360(a - b);
  return (delta > 180) ? (360 - delta) : delta;
}
