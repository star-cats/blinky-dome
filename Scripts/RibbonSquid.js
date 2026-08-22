/**
 * Ribbon squid, ported from a compact Processing sketch.
 *
 * The original 10,000 translucent points and its native motion are preserved.
 * Time-independent trigonometric phases are tabulated at load, leaving only a
 * handful of trig calls per frame. A luminance buffer recreates Processing's
 * point accumulation while remaining practical for a sparse LED model.
 */

knob("speed", "Speed", "Speed of the original swimming and ribbon motion", 0.5);
knob("wave", "Wave", "Strength of the squid's vertical ripple and tentacle motion", 0.5);
knob("size", "Size", "Overall squid size", 0.5);
knob("width", "Width", "Horizontal body and ribbon scale", 0.5);
knob("height", "Height", "Vertical body and ribbon scale", 0.5);
knob("rotation", "Rotation", "Rotate the complete squid; center is upright", 0.5);
knob("centerX", "Center X", "Horizontal squid position", 0.5);
knob("centerY", "Center Y", "Vertical squid position", 0.5);

knob("glow", "Glow", "Radius of the point-cloud glow", 0.375);
knob("opacity", "Opacity", "Opacity of each accumulated point", 0.61);
knob("hue", "Hue", "Ribbon squid color", 0);
knob("saturation", "Saturation", "Color saturation; zero is the original white", 0);
knob("background", "Background", "Background brightness", 0.035);
knob("level", "Level", "Overall output brightness", 1);
toggle("autoAspect", "Aspect", "Keep the squid proportional on non-square models", true);

var POINT_COUNT = 10000;
var GRID_SIZE = 256;
var GRID_LAST = GRID_SIZE - 1;
var GRID_CELLS = GRID_SIZE * GRID_SIZE;

// PI / 40 per Processing draw at 60 fps.
var TIME_RATE = Math.PI * 1.5;
// All time-dependent terms repeat together after 18 PI.
var TIME_PERIOD = Math.PI * 18;

var ink = null;
var pointXAmplitude = null;
var pointStaticY = null;
var pointWaveAmplitude = null;
var pointD2Third = null;

var pointCosD = null;
var pointSinD = null;
var pointCosD3 = null;
var pointSinD3 = null;
var pointCosWave = null;
var pointSinWave = null;
var pointCosD2Seventh = null;
var pointSinD2Seventh = null;

var time = 0;
var aspectX = 1;

// Control values resolved once per frame.
var waveScale = 1;
var scaleX = 1;
var scaleY = 1;
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
  pointXAmplitude = new Array(POINT_COUNT);
  pointStaticY = new Array(POINT_COUNT);
  pointWaveAmplitude = new Array(POINT_COUNT);
  pointD2Third = new Array(POINT_COUNT);

  pointCosD = new Array(POINT_COUNT);
  pointSinD = new Array(POINT_COUNT);
  pointCosD3 = new Array(POINT_COUNT);
  pointSinD3 = new Array(POINT_COUNT);
  pointCosWave = new Array(POINT_COUNT);
  pointSinWave = new Array(POINT_COUNT);
  pointCosD2Seventh = new Array(POINT_COUNT);
  pointSinD2Seventh = new Array(POINT_COUNT);

  for (var cell = 0; cell < GRID_CELLS; ++cell) {
    ink[cell] = 0;
  }

  for (var i = 0; i < POINT_COUNT; ++i) {
    var y = i / 295;
    var k = 4 * Math.cos(i / 29);
    var e = y / 4 - 16;
    var d = Math.sqrt(k * k + e * e) - 5;
    var d2 = d * d;
    var waveAmplitude = y / 9 * k;
    var wavePhase = e * 9 - d * 3;
    var d2Seventh = d2 / 7;

    pointXAmplitude[i] = d2 / 0.7 - k * k * 2 + y;
    pointStaticY[i] = 3 * Math.sin(k * 2) + Math.cos(y) / k +
      waveAmplitude * 3;
    pointWaveAmplitude[i] = waveAmplitude;
    pointD2Third[i] = d2 / 3;

    pointCosD[i] = Math.cos(d);
    pointSinD[i] = Math.sin(d);
    pointCosD3[i] = Math.cos(d / 3);
    pointSinD3[i] = Math.sin(d / 3);
    pointCosWave[i] = Math.cos(wavePhase);
    pointSinWave[i] = Math.sin(wavePhase);
    pointCosD2Seventh[i] = Math.cos(d2Seventh);
    pointSinD2Seventh[i] = Math.sin(d2Seventh);
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

  waveScale = wave * 2;
  var overallScale = 0.4 + size * 1.2;
  scaleX = overallScale * (0.5 + width);
  scaleY = overallScale * (0.5 + height);
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

  // Every per-point angle is a fixed phase plus one of these time shifts.
  var cosThirdTime = Math.cos(time / 3);
  var sinThirdTime = Math.sin(time / 3);
  var cosNinthTime = Math.cos(time / 9);
  var sinNinthTime = Math.sin(time / 9);
  var cosTime = Math.cos(time);
  var sinTime = Math.sin(time);

  for (var i = 0; i < POINT_COUNT; ++i) {
    // cos(d - t/3)
    var cosC = pointCosD[i] * cosThirdTime + pointSinD[i] * sinThirdTime;
    // sin(d/3 - t/9)
    var sinC3 = pointSinD3[i] * cosNinthTime - pointCosD3[i] * sinNinthTime;
    // sin(e*9 - d*3 + t)
    var ribbonWave = pointSinWave[i] * cosTime + pointCosWave[i] * sinTime;
    // sin(t - d*d/7)
    var deepWave = sinTime * pointCosD2Seventh[i] -
      cosTime * pointSinD2Seventh[i];

    var sourceX = (pointXAmplitude[i] * cosC + 200) / 400;
    var animatedY = pointWaveAmplitude[i] * ribbonWave +
      79 * sinC3 + pointD2Third[i] * deepWave;
    var sourceY = (pointStaticY[i] + 200 + waveScale * animatedY) / 400;

    var dx = (sourceX - 0.5) * scaleX;
    var dy = (sourceY - 0.5) * scaleY;
    var x = dx * cosRotation - dy * sinRotation + 0.5 + panX;
    var y = dx * sinRotation + dy * cosRotation + 0.5 + panY;
    splat(x * GRID_LAST, y * GRID_LAST);
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
  // The source's cos(y)/k term deliberately sends a few ribbon points far off
  // canvas. Discarding them here matches Processing's clipping behavior.
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
