/**
 * Two dragonfish, ported from a compact Processing sketch.
 *
 * The source interleaves two fish with `i % 2`. Both genuine strands are kept:
 * their geometry and native motion are calculated first, then optional scene
 * transforms are applied around their live centroids. Points are accumulated
 * into a luminance buffer so the original translucent drawing remains visible
 * when sampled by a sparse LED model.
 */

knob("speed", "Speed", "Speed of the original swimming and body motion", 0.5);
knob("size", "Size", "Scale of each fish around its moving center", 0.5);
knob("separation", "Separation", "Distance between the fish; center preserves the original spacing", 0.5);
knob("rotation", "Rotation", "Rotate the complete two-fish scene; center is upright", 0.5);
knob("centerX", "Center X", "Horizontal position of the complete scene", 0.5);
knob("centerY", "Center Y", "Vertical position of the complete scene", 0.5);

knob("glow", "Glow", "Radius of the point-cloud glow", 0.375);
knob("opacity", "Opacity", "Opacity of each accumulated point", 0.61);
knob("hue", "Hue", "Dragonfish color", 0);
knob("saturation", "Saturation", "Color saturation; zero is the original white", 0);
knob("background", "Background", "Background brightness", 0.035);
knob("level", "Level", "Overall output brightness", 1);
toggle("autoAspect", "Aspect", "Keep the fish proportional on non-square models", true);

var POINT_COUNT = 20000;
var FISH_COUNT = 2;
var POINTS_PER_FISH = POINT_COUNT / FISH_COUNT;

var GRID_SIZE = 256;
var GRID_LAST = GRID_SIZE - 1;
var GRID_CELLS = GRID_SIZE * GRID_SIZE;

// PI / 80 per Processing draw at 60 fps.
var TIME_RATE = Math.PI * 0.75;
// Every time-dependent term returns exactly to its starting value after 12 PI.
var TIME_PERIOD = Math.PI * 12;

var ink = null;
var pointRadius = null;
var pointD2 = null;
var pointStaticY = null;
var pointCosC = null;
var pointSinC = null;
var pointCosC3 = null;
var pointSinC3 = null;
var pointCosD = null;
var pointSinD = null;

// Coefficient sums resolve each fish's live centroid without a separate pass.
var centerXCos = [0, 0];
var centerXSin = [0, 0];
var centerYSinC3 = [0, 0];
var centerYCosC3 = [0, 0];
var centerYD2CosD = [0, 0];
var centerYD2SinD = [0, 0];
var centerYStatic = [0, 0];

var time = 0;
var aspectX = 1;

// Values derived from controls once per frame.
var fishScale = 1;
var separationScale = 1;
var cosRotation = 1;
var sinRotation = 0;
var panX = 0;
var panY = 0;
var glowOffset = 2.25;
var pointTransmission = 1 - 144 / 255;
var outputBackground = 9 / 255;
var outputLevel = 1;

function init() {
  centerXCos = [0, 0];
  centerXSin = [0, 0];
  centerYSinC3 = [0, 0];
  centerYCosC3 = [0, 0];
  centerYD2CosD = [0, 0];
  centerYD2SinD = [0, 0];
  centerYStatic = [0, 0];

  ink = new Array(GRID_CELLS);
  pointRadius = new Array(POINT_COUNT);
  pointD2 = new Array(POINT_COUNT);
  pointStaticY = new Array(POINT_COUNT);
  pointCosC = new Array(POINT_COUNT);
  pointSinC = new Array(POINT_COUNT);
  pointCosC3 = new Array(POINT_COUNT);
  pointSinC3 = new Array(POINT_COUNT);
  pointCosD = new Array(POINT_COUNT);
  pointSinD = new Array(POINT_COUNT);

  for (var cell = 0; cell < GRID_CELLS; ++cell) {
    ink[cell] = 0;
  }

  // Everything here depends only on i. Pulling it out of the frame loop keeps
  // the full 20,000-point source affordable without changing its equations.
  for (var i = 0; i < POINT_COUNT; ++i) {
    var y = i / 663;
    var k = (4 + Math.cos(y)) * Math.cos(i);
    var e = y / 5 - 11;
    var d = Math.sqrt(k * k + e * e) - 5;
    var baseC = d / 2.5 + (i % 2) * 8;
    pointRadius[i] = 79 + k * k;
    pointD2[i] = d * d;
    pointStaticY[i] = 3 * Math.sin(k * 2) +
      Math.sin(y / 9 + 6) * k * (e + Math.sin(e * 4 - d * 4));
    pointCosC[i] = Math.cos(baseC);
    pointSinC[i] = Math.sin(baseC);
    pointCosC3[i] = Math.cos(baseC / 3);
    pointSinC3[i] = Math.sin(baseC / 3);
    pointCosD[i] = Math.cos(d);
    pointSinD[i] = Math.sin(d);

    var fish = i % 2;
    centerXCos[fish] += pointRadius[i] * pointCosC[i];
    centerXSin[fish] += pointRadius[i] * pointSinC[i];
    centerYSinC3[fish] += pointSinC3[i];
    centerYCosC3[fish] += pointCosC3[i];
    centerYD2CosD[fish] += pointD2[i] * pointCosD[i];
    centerYD2SinD[fish] += pointD2[i] * pointSinD[i];
    centerYStatic[fish] += pointStaticY[i];
  }
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (ink == null) {
    init();
  }

  var dt = isFinite(deltaMs) ? Math.max(0, Math.min(deltaMs / 1000, 0.25)) : 0;
  time += dt * TIME_RATE * speed * 2;
  if (time >= TIME_PERIOD) {
    time -= Math.floor(time / TIME_PERIOD) * TIME_PERIOD;
  }

  fishScale = 0.4 + size * 1.2;
  separationScale = separation * 2;
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

  // Expand the three time-shifted trig functions once. Their per-point phases
  // were tabulated in init(), turning 60,000 trig calls into six per frame.
  var cosHalfTime = Math.cos(time / 2);
  var sinHalfTime = Math.sin(time / 2);
  var cosSixthTime = Math.cos(time / 6);
  var sinSixthTime = Math.sin(time / 6);
  var cosDoubleTime = Math.cos(time * 2);
  var sinDoubleTime = Math.sin(time * 2);

  var fishCenterX0 = 0.5 +
    (centerXCos[0] * cosHalfTime + centerXSin[0] * sinHalfTime) /
    (400 * POINTS_PER_FISH);
  var fishCenterX1 = 0.5 +
    (centerXCos[1] * cosHalfTime + centerXSin[1] * sinHalfTime) /
    (400 * POINTS_PER_FISH);
  var fishCenterY0 = 0.5 + (
    99 * (centerYSinC3[0] * cosSixthTime - centerYCosC3[0] * sinSixthTime) +
    centerYD2CosD[0] * sinDoubleTime - centerYD2SinD[0] * cosDoubleTime +
    centerYStatic[0]
  ) / (400 * POINTS_PER_FISH);
  var fishCenterY1 = 0.5 + (
    99 * (centerYSinC3[1] * cosSixthTime - centerYCosC3[1] * sinSixthTime) +
    centerYD2CosD[1] * sinDoubleTime - centerYD2SinD[1] * cosDoubleTime +
    centerYStatic[1]
  ) / (400 * POINTS_PER_FISH);

  // This is the original formula, expanded and normalized from 400 pixels to
  // 0..1. Even and odd indices remain the two separate dragonfish.
  for (var i = 0; i < POINT_COUNT; ++i) {
    var cosC = pointCosC[i] * cosHalfTime + pointSinC[i] * sinHalfTime;
    var sinC3 = pointSinC3[i] * cosSixthTime - pointCosC3[i] * sinSixthTime;
    var sinMotion = sinDoubleTime * pointCosD[i] - cosDoubleTime * pointSinD[i];
    var sourceX = (pointRadius[i] * cosC + 200) / 400;
    var sourceY = (99 * sinC3 + 200 +
      pointD2[i] * sinMotion + pointStaticY[i]) / 400;
    var fishCenterX = i % 2 === 0 ? fishCenterX0 : fishCenterX1;
    var fishCenterY = i % 2 === 0 ? fishCenterY0 : fishCenterY1;

    // Size acts locally on each fish. Separation acts only on their moving
    // centers, so neither control suppresses the source animation.
    var x = 0.5 + (fishCenterX - 0.5) * separationScale +
      (sourceX - fishCenterX) * fishScale;
    var y = 0.5 + (fishCenterY - 0.5) * separationScale +
      (sourceY - fishCenterY) * fishScale;

    // Rotate the combined scene about its center, then pan in screen space.
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

/** Adds one anti-aliased point to the four surrounding buffer cells. */
function splat(x, y) {
  if (x < 0 || x > GRID_LAST || y < 0 || y > GRID_LAST) {
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

/** Bilinearly samples the point buffer, returning black beyond its edges. */
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
