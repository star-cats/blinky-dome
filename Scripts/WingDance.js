/**
 * Wing Dance, ported from a compact Processing sketch.
 *
 * A Lorenz trajectory supplies 30,000 body points, interleaved by `i % 9`
 * into nine animated butterflies. The trajectory resets identically on every
 * source frame, so it is integrated once in init() and reused without changing
 * the drawing equations. Each active butterfly is then scaled around its live
 * centroid, preserving the native dance while making count and layout useful.
 */

knob("butterflies", "Butterflies", "Number of dancers, from 1 to 9", 1);
knob("speed", "Speed", "Speed of the original wing and formation motion", 0.5);
knob("flutter", "Flutter", "Depth of each butterfly's wing beat", 0.5);
knob("size", "Size", "Scale of each butterfly around its own moving center", 0.5);
knob("spread", "Spread", "Distance of the butterfly centers from the middle", 0.5);
knob("density", "Density", "Point density; lower values improve performance", 1);

knob("rotation", "Rotation", "Rotate the complete dance; center is upright", 0.5);
knob("centerX", "Center X", "Horizontal position of the complete dance", 0.5);
knob("centerY", "Center Y", "Vertical position of the complete dance", 0.5);

knob("glow", "Glow", "Radius of the point-cloud glow", 0.375);
knob("opacity", "Opacity", "Opacity of each accumulated point", 0.61);
knob("hue", "Hue", "Butterfly color", 0);
knob("saturation", "Saturation", "Color saturation; zero is the original white", 0);
knob("background", "Background", "Background brightness", 0.035);
knob("level", "Level", "Overall output brightness", 1);
toggle("autoAspect", "Aspect", "Keep the dance proportional on non-square models", true);

var POINT_COUNT = 30000;
var MAX_BUTTERFLIES = 9;
var LORENZ_STEP = 0.0005;

var GRID_SIZE = 256;
var GRID_LAST = GRID_SIZE - 1;
var GRID_CELLS = GRID_SIZE * GRID_SIZE;

// The original advances t by one per draw. At 60 fps the two animation phases
// therefore advance by 3 PI and PI / 8 radians per second respectively.
var FRAME_RATE = 60;
var FRAME_PERIOD = 960;

var ink = null;
var lorenzX = null;
var lorenzZ = null;
var rawX = null;
var rawY = null;
var rawGroup = null;

var groupSumX = null;
var groupSumY = null;
var groupPointCount = null;

var frameTime = 0;
var aspectX = 1;

// Controls resolved once per frame.
var activeButterflies = 9;
var butterflyScale = 1;
var spreadScale = 1;
var flutterScale = 1;
var drawStride = 1;
var cosRotation = 1;
var sinRotation = 0;
var panX = 0;
var panY = 0;
var glowOffset = 2.25;
var pointTransmission = 1 - 144 / 255;
var outputBackground = 9 / 255;
var outputLevel = 1;

function init() {
  ink = new Array(GRID_CELLS);
  lorenzX = new Array(POINT_COUNT);
  lorenzZ = new Array(POINT_COUNT);
  rawX = new Array(POINT_COUNT);
  rawY = new Array(POINT_COUNT);
  rawGroup = new Array(POINT_COUNT);

  groupSumX = new Array(MAX_BUTTERFLIES);
  groupSumY = new Array(MAX_BUTTERFLIES);
  groupPointCount = new Array(MAX_BUTTERFLIES);

  for (var cell = 0; cell < GRID_CELLS; ++cell) {
    ink[cell] = 0;
  }

  // Preserve the source's descending loop and simultaneous [x,y,z] update.
  var x = 9;
  var y = 9;
  var z = 9;
  for (var i = POINT_COUNT; i--;) {
    lorenzX[i] = x;
    lorenzZ[i] = z;

    var oldX = x;
    var oldY = y;
    var oldZ = z;
    x = oldX + 9 * (oldY - oldX) * LORENZ_STEP;
    y = oldY + (oldX * (28 - oldZ) - oldY) * LORENZ_STEP;
    z = oldZ + (oldX * oldY - oldZ - oldZ) * LORENZ_STEP;
  }
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (ink == null) {
    init();
  }

  var dt = isFinite(deltaMs) ? Math.max(0, Math.min(deltaMs / 1000, 0.25)) : 0;
  frameTime += dt * FRAME_RATE * speed * 2;
  if (frameTime >= FRAME_PERIOD) {
    frameTime -= Math.floor(frameTime / FRAME_PERIOD) * FRAME_PERIOD;
  }

  activeButterflies = Math.max(1, Math.min(
    MAX_BUTTERFLIES,
    1 + Math.floor(butterflies * 8.999999)
  ));
  flutterScale = flutter * 2;
  butterflyScale = 0.4 + size * 1.2;
  spreadScale = spread * 2;
  drawStride = 1 + Math.floor((1 - density) * 7.999999);

  var angle = (rotation - 0.5) * Math.PI * 2;
  cosRotation = Math.cos(angle);
  sinRotation = Math.sin(angle);
  panX = (centerX - 0.5) * 1.2;
  panY = (centerY - 0.5) * 1.2;
  glowOffset = glow * 6;
  pointTransmission = 1 - (0.05 + opacity * 0.85);
  outputBackground = background;
  outputLevel = level;

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  for (var cell = 0; cell < GRID_CELLS; ++cell) {
    ink[cell] = 0;
  }
  for (var group = 0; group < MAX_BUTTERFLIES; ++group) {
    groupSumX[group] = 0;
    groupSumY[group] = 0;
    groupPointCount[group] = 0;
  }

  var fastPhase = frameTime * Math.PI / 20;
  var slowPhase = frameTime * Math.PI / 480;
  var rawCount = 0;

  for (var i = POINT_COUNT; i--;) {
    var group = i % activeButterflies;

    // Apply Density evenly inside every modulo group. The Lorenz trajectory is
    // already tabulated, so omitted points cost no integration or trig work.
    if (Math.floor(i / activeButterflies) % drawStride !== 0) {
      continue;
    }

    var x = lorenzX[i];
    var z = lorenzZ[i];
    var wing = Math.sin(fastPhase - x * x / 99 + group);
    var e = 1 + flutterScale * wing;
    var q = x * e + 89;
    var k = z / 59 - e / 29 + slowPhase + group * 8;
    var sourceX = (q * Math.cos(k) + 200) / 400;
    var sourceY = (200 - (q + 60 * Math.cos(k / 2)) * Math.sin(k)) / 400;

    rawX[rawCount] = sourceX;
    rawY[rawCount] = sourceY;
    rawGroup[rawCount] = group;
    groupSumX[group] += sourceX;
    groupSumY[group] += sourceY;
    ++groupPointCount[group];
    ++rawCount;
  }

  for (var pointIndex = 0; pointIndex < rawCount; ++pointIndex) {
    var group = rawGroup[pointIndex];
    var groupCenterX = groupSumX[group] / groupPointCount[group];
    var groupCenterY = groupSumY[group] / groupPointCount[group];

    // Size is local to each butterfly. Spread moves only its live centroid.
    var x = 0.5 + (groupCenterX - 0.5) * spreadScale +
      (rawX[pointIndex] - groupCenterX) * butterflyScale;
    var y = 0.5 + (groupCenterY - 0.5) * spreadScale +
      (rawY[pointIndex] - groupCenterY) * butterflyScale;

    var dx = x - 0.5;
    var dy = y - 0.5;
    var rotatedX = dx * cosRotation - dy * sinRotation + 0.5 + panX;
    var rotatedY = dx * sinRotation + dy * cosRotation + 0.5 + panY;
    splat(rotatedX * GRID_LAST, rotatedY * GRID_LAST);
  }
}

function renderPoint(point, deltaMs) {
  if (ink == null) {
    return gray(outputBackground * 100);
  }

  var u = 0.5 + (point.xn - 0.5) * aspectX;
  var v = 1 - point.yn;
  if (u < 0 || u > 1 || v < 0 || v > 1) {
    return hsb(hue * 360, saturation * 100, outputBackground * outputLevel * 100);
  }

  var gx = u * GRID_LAST;
  var gy = v * GRID_LAST;
  var coverage = sampleInk(gx, gy);
  coverage += 0.32 * sampleInk(gx - glowOffset, gy);
  coverage += 0.32 * sampleInk(gx + glowOffset, gy);
  coverage += 0.32 * sampleInk(gx, gy - glowOffset);
  coverage += 0.32 * sampleInk(gx, gy + glowOffset);
  coverage += 0.14 * sampleInk(gx - glowOffset, gy - glowOffset);
  coverage += 0.14 * sampleInk(gx + glowOffset, gy - glowOffset);
  coverage += 0.14 * sampleInk(gx - glowOffset, gy + glowOffset);
  coverage += 0.14 * sampleInk(gx + glowOffset, gy + glowOffset);

  var accumulatedOpacity = 1 - Math.pow(pointTransmission, coverage);
  var luminance = outputBackground + (1 - outputBackground) * accumulatedOpacity;
  return hsb(hue * 360, saturation * 100, luminance * outputLevel * 100);
}

function splat(x, y) {
  if (!isFinite(x) || !isFinite(y) ||
      x < 0 || x > GRID_LAST || y < 0 || y > GRID_LAST) {
    return;
  }

  var x0 = Math.floor(x);
  var y0 = Math.floor(y);
  var x1 = Math.min(x0 + 1, GRID_LAST);
  var y1 = Math.min(y0 + 1, GRID_LAST);
  var fx = x - x0;
  var fy = y - y0;

  ink[y0 * GRID_SIZE + x0] += (1 - fx) * (1 - fy);
  ink[y0 * GRID_SIZE + x1] += fx * (1 - fy);
  ink[y1 * GRID_SIZE + x0] += (1 - fx) * fy;
  ink[y1 * GRID_SIZE + x1] += fx * fy;
}

function sampleInk(x, y) {
  if (x < 0 || x > GRID_LAST || y < 0 || y > GRID_LAST) {
    return 0;
  }

  var x0 = Math.floor(x);
  var y0 = Math.floor(y);
  var x1 = Math.min(x0 + 1, GRID_LAST);
  var y1 = Math.min(y0 + 1, GRID_LAST);
  var fx = x - x0;
  var fy = y - y0;
  var top = ink[y0 * GRID_SIZE + x0] * (1 - fx) +
    ink[y0 * GRID_SIZE + x1] * fx;
  var bottom = ink[y1 * GRID_SIZE + x0] * (1 - fx) +
    ink[y1 * GRID_SIZE + x1] * fx;
  return top * (1 - fy) + bottom * fy;
}
