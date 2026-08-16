/**
 * Noise Ripple Distortion
 *
 * Displaces the current input colors in X and Y with two seamless noise maps.
 * Perlin drives broad horizontal flow while Swirl supplies a more directional
 * vertical ripple. Their texture coordinates drift at different rates, and
 * both displaced color coordinates wrap across the model boundaries.
 */

var ImageIO = Java.type("javax.imageio.ImageIO");
var JFile = Java.type("java.io.File");
var System = Java.type("java.lang.System");
var IntArray = Java.type("int[]");

var IMAGE_DIR = "Images/Noise";
var X_NOISE_FILE = "Perlin_14-128x128.png";
var Y_NOISE_FILE = "Swirl_03-128x128.png";
var MEDIA_ROOT = null;

// Resolution of the reusable XY-to-model-point lookup. Matching the source
// textures keeps the resampling smooth without making model indexing costly.
var LOOKUP_SIZE = 128;
var BIN_COUNT = 32;
var MAX_DISPLACEMENT = 0.28;

knob("speed", "Motion Speed", "How quickly the two distortion fields drift", 0.35);
knob("intensity", "Distortion", "Maximum wrapped X/Y displacement", 0.4);

var xNoise = null;
var yNoise = null;
var resolvedRoot;

var phaseX = 0;
var phaseY = 0;
var displacement = 0;

var sourceColors = null;
var indexedModel = null;
var indexedPointCount = -1;
var binHead = [];
var pointNext = [];
var sourceLookup = null;
var nearestBestPoint = -1;
var nearestBestDistanceSq = Infinity;

function init() {
  xNoise = loadTexture(X_NOISE_FILE);
  yNoise = loadTexture(Y_NOISE_FILE);
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;
  var rate = lerp(0, 0.65, speed);

  // Deliberately different rates and directions keep the two axes from locking
  // into one diagonal translation.
  phaseX = wrap01(phaseX + dt * rate);
  phaseY = wrap01(phaseY - dt * rate * 0.63);
  displacement = intensity * MAX_DISPLACEMENT;

  if (sourceColors == null || sourceColors.length != colors.length) {
    sourceColors = new IntArray(colors.length);
  }
  for (var i = 0; i < colors.length; ++i) {
    sourceColors[i] = colors[i];
  }

  if (indexedModel !== model || indexedPointCount != model.points.length) {
    buildSourceLookup(model);
  }
}

function renderPoint(point, deltaMs, enabledAmount, inputColor) {
  if (xNoise == null || yNoise == null || sourceLookup == null ||
      displacement <= 0 || enabledAmount <= 0) {
    return inputColor;
  }

  // Each map moves through both texture axes, but with different trajectories.
  // The maps are centered and normalized so Intensity has equal authority over
  // positive and negative displacement regardless of each PNG's gray range.
  var dx = signedNoise(xNoise,
    point.xn * 1.7 + phaseX,
    point.yn * 1.7 + phaseX * 0.31);
  var dy = signedNoise(yNoise,
    point.xn * 1.35 + phaseY * 0.43,
    point.yn * 1.35 + phaseY);

  var sampleX = wrap01(point.xn + dx * displacement);
  var sampleY = wrap01(point.yn + dy * displacement);
  var distortedColor = sampleSource(sampleX, sampleY);
  return LXColor.lerp(inputColor, distortedColor, clamp(enabledAmount, 0, 1));
}

/**
 * Snapshot sampling is essential for an effect: the default script runner
 * overwrites colors in point order, so reading the live array would make later
 * points sample already-distorted output from earlier points.
 */
function sampleSource(u, v) {
  var x = wrap01(u) * LOOKUP_SIZE;
  var y = wrap01(v) * LOOKUP_SIZE;
  var floorX = Math.floor(x);
  var floorY = Math.floor(y);
  var x0 = floorX % LOOKUP_SIZE;
  var y0 = floorY % LOOKUP_SIZE;
  var x1 = (x0 + 1) % LOOKUP_SIZE;
  var y1 = (y0 + 1) % LOOKUP_SIZE;
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

/** Build a toroidal nearest-point lookup for arbitrary LX models. */
function buildSourceLookup(model) {
  indexedModel = model;
  indexedPointCount = model.points.length;
  sourceLookup = new IntArray(LOOKUP_SIZE * LOOKUP_SIZE);

  var binTotal = BIN_COUNT * BIN_COUNT;
  for (var b = 0; b < binTotal; ++b) {
    binHead[b] = -1;
  }

  for (var i = 0; i < model.points.length; ++i) {
    var point = model.points[i];
    var bx = Math.min(BIN_COUNT - 1, Math.floor(wrap01(point.xn) * BIN_COUNT));
    var by = Math.min(BIN_COUNT - 1, Math.floor(wrap01(point.yn) * BIN_COUNT));
    var bin = by * BIN_COUNT + bx;
    pointNext[i] = binHead[bin];
    binHead[bin] = i;
  }

  if (model.points.length == 0) {
    sourceLookup = null;
    return;
  }

  for (var y = 0; y < LOOKUP_SIZE; ++y) {
    var v = y / LOOKUP_SIZE;
    for (var x = 0; x < LOOKUP_SIZE; ++x) {
      var u = x / LOOKUP_SIZE;
      sourceLookup[y * LOOKUP_SIZE + x] = nearestPointIndex(model, u, v);
    }
  }
}

/**
 * Find the nearest model point on a wrapped XY plane. Bins expand in rings;
 * once the nearest possible unsearched bin is farther than the current winner,
 * the result is exact and the search stops.
 */
function nearestPointIndex(model, u, v) {
  var centerX = Math.floor(u * BIN_COUNT) % BIN_COUNT;
  var centerY = Math.floor(v * BIN_COUNT) % BIN_COUNT;
  nearestBestPoint = -1;
  nearestBestDistanceSq = Infinity;

  for (var radius = 0; radius <= Math.ceil(BIN_COUNT / 2); ++radius) {
    if (radius == 0) {
      var centerBin = centerY * BIN_COUNT + centerX;
      inspectBin(model, centerBin, u, v);
    } else {
      for (var dx = -radius; dx <= radius; ++dx) {
        var topBin = wrapIndex(centerY - radius, BIN_COUNT) * BIN_COUNT +
          wrapIndex(centerX + dx, BIN_COUNT);
        inspectBin(model, topBin, u, v);

        var bottomBin = wrapIndex(centerY + radius, BIN_COUNT) * BIN_COUNT +
          wrapIndex(centerX + dx, BIN_COUNT);
        inspectBin(model, bottomBin, u, v);
      }
      for (var dy = -radius + 1; dy < radius; ++dy) {
        var leftBin = wrapIndex(centerY + dy, BIN_COUNT) * BIN_COUNT +
          wrapIndex(centerX - radius, BIN_COUNT);
        inspectBin(model, leftBin, u, v);

        var rightBin = wrapIndex(centerY + dy, BIN_COUNT) * BIN_COUNT +
          wrapIndex(centerX + radius, BIN_COUNT);
        inspectBin(model, rightBin, u, v);
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

function inspectBin(model, bin, u, v) {
  for (var i = binHead[bin]; i >= 0; i = pointNext[i]) {
    var point = model.points[i];
    var dx = Math.abs(point.xn - u);
    var dy = Math.abs(point.yn - v);
    dx = Math.min(dx, 1 - dx);
    dy = Math.min(dy, 1 - dy);
    var distanceSq = dx * dx + dy * dy;
    if (distanceSq < nearestBestDistanceSq) {
      nearestBestDistanceSq = distanceSq;
      nearestBestPoint = i;
    }
  }
}

function signedNoise(image, u, v) {
  var gray = sampleGray(image, u, v);
  return clamp((gray - image.center) / image.halfSpan, -1, 1);
}

/** Bilinear grayscale texture sampling with seamless wrapping on both axes. */
function sampleGray(image, u, v) {
  u = wrap01(u);
  v = wrap01(v);
  var x = u * image.w;
  var y = v * image.h;
  var floorX = Math.floor(x);
  var floorY = Math.floor(y);
  var x0 = floorX % image.w;
  var y0 = floorY % image.h;
  var x1 = (x0 + 1) % image.w;
  var y1 = (y0 + 1) % image.h;
  var tx = x - floorX;
  var ty = y - floorY;
  var a = grayOf(image.px[y0 * image.w + x0]);
  var b = grayOf(image.px[y0 * image.w + x1]);
  var c = grayOf(image.px[y1 * image.w + x0]);
  var d = grayOf(image.px[y1 * image.w + x1]);
  return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
}

function grayOf(argb) {
  var r = (argb >> 16) & 0xff;
  var g = (argb >> 8) & 0xff;
  var b = argb & 0xff;
  return (r * 0.2126 + g * 0.7152 + b * 0.0722) / 255;
}

function wrap01(value) {
  return value - Math.floor(value);
}

function wrapIndex(value, size) {
  return ((value % size) + size) % size;
}

function mediaRoot() {
  if (resolvedRoot !== undefined) {
    return resolvedRoot;
  }
  var candidates = [];
  if (MEDIA_ROOT) {
    candidates.push(MEDIA_ROOT);
  }
  var dir = new JFile(System.getProperty("user.dir"));
  for (var i = 0; i < 6 && dir != null; ++i) {
    candidates.push(dir.getPath());
    dir = dir.getParentFile();
  }
  candidates.push(System.getProperty("user.home") + "/Chromatik");

  resolvedRoot = null;
  for (var j = 0; j < candidates.length; ++j) {
    if (new JFile(candidates[j], IMAGE_DIR).isDirectory()) {
      resolvedRoot = candidates[j];
      break;
    }
  }
  return resolvedRoot;
}

function loadTexture(name) {
  var root = mediaRoot();
  if (root == null) {
    throw new Error("NoiseRippleDistortion.js: could not find " + IMAGE_DIR + " — set MEDIA_ROOT");
  }
  var file = new JFile(new JFile(root, IMAGE_DIR), name);
  var buffered = ImageIO.read(file);
  if (buffered == null) {
    throw new Error("NoiseRippleDistortion.js: could not read " + file);
  }
  var w = buffered.getWidth();
  var h = buffered.getHeight();
  var px = buffered.getRGB(0, 0, w, h, new IntArray(w * h), 0, w);
  var low = 1;
  var high = 0;

  for (var i = 0; i < w * h; ++i) {
    var gray = grayOf(px[i]);
    if (gray < low) {
      low = gray;
    }
    if (gray > high) {
      high = gray;
    }
  }

  return {
    w: w,
    h: h,
    px: px,
    center: (low + high) * 0.5,
    halfSpan: Math.max((high - low) * 0.5, 1e-6)
  };
}
