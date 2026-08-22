/**
 * Face Hugger, ported from a compact Processing sketch.
 *
 * The source evaluates a 200 x 200 field and draws it with a translucent
 * background, so old poses fade instead of disappearing immediately. This port
 * keeps a persistent luminance buffer and applies the same fade and point-alpha
 * compositing in a frame-rate-independent form.
 */

knob("speed", "Speed", "Speed of the original pulsing motion", 0.5);
knob("motion", "Motion", "Strength of the animated limb deformation", 0.5);
knob("density", "Density", "Source-grid density; lower values improve performance", 1);
knob("size", "Size", "Overall creature size", 0.5);
knob("width", "Width", "Horizontal body and limb scale", 0.5);
knob("height", "Height", "Vertical body and limb scale", 0.5);
knob("rotation", "Rotation", "Rotate the creature; center is upright", 0.5);
knob("centerX", "Center X", "Horizontal creature position", 0.5);
knob("centerY", "Center Y", "Vertical creature position", 0.5);

knob("trails", "Trails", "Persistence of previous poses; zero clears every frame", 0.649509803922);
knob("glow", "Glow", "Radius of the point-cloud glow", 0.375);
knob("opacity", "Opacity", "Opacity of each accumulated source point", 0.4);
knob("hue", "Hue", "Creature color", 0);
knob("saturation", "Saturation", "Color saturation; zero is the original white", 0);
knob("background", "Background", "Background brightness", 0.023529411765);
knob("level", "Level", "Overall output brightness", 1);
toggle("autoAspect", "Aspect", "Keep the creature proportional on non-square models", true);

var POINT_COUNT = 40000;
var GRID_SIZE = 256;
var GRID_LAST = GRID_SIZE - 1;
var GRID_CELLS = GRID_SIZE * GRID_SIZE;

// PI / 90 per Processing draw at 60 fps. Every animated term repeats at 2 PI.
var TIME_RATE = Math.PI * 2 / 3;
var TIME_PERIOD = Math.PI * 2;
var REFERENCE_FPS = 60;

var ink = null;
var pointBaseX = null;
var pointBaseY = null;
var pointMotionX = null;
var pointCos2D = null;
var pointSin2D = null;
var pointCosD = null;
var pointSinD = null;

var time = 0;
var aspectX = 1;

// Values resolved once per frame.
var motionScale = 1;
var scaleX = 1;
var scaleY = 1;
var cosRotation = 1;
var sinRotation = 0;
var panX = 0;
var panY = 0;
var glowOffset = 2.25;
var pointAlpha = 46 / 255;
var outputBackground = 6 / 255;
var outputLevel = 1;
var sampleStep = 1;

function init() {
  ink = new Array(GRID_CELLS);
  pointBaseX = new Array(POINT_COUNT);
  pointBaseY = new Array(POINT_COUNT);
  pointMotionX = new Array(POINT_COUNT);
  pointCos2D = new Array(POINT_COUNT);
  pointSin2D = new Array(POINT_COUNT);
  pointCosD = new Array(POINT_COUNT);
  pointSinD = new Array(POINT_COUNT);

  for (var cell = 0; cell < GRID_CELLS; ++cell) {
    ink[cell] = 6 / 255;
  }

  for (var i = 0; i < POINT_COUNT; ++i) {
    var x = i % 200;
    var y = Math.floor(i / 200);
    var k = x / 8 - 12.5;
    var e = y / 8 - 12.5;
    var radius = Math.sqrt(k * k + e * e) / 12;
    var o = radius * Math.cos(Math.sin(k / 2) * Math.cos(e / 2));
    var d = 5 * Math.cos(o);
    var staticLimb = Math.sin(y * o * o) / 9;

    pointBaseX[i] = (x + d * k * staticLimb) / 1.5 + 133;
    pointMotionX[i] = d * k / 1.5;
    pointBaseY[i] = (y / 3 - d * 40) * 1.5 + 300;
    pointCos2D[i] = Math.cos(d * 2);
    pointSin2D[i] = Math.sin(d * 2);
    pointCosD[i] = Math.cos(d);
    pointSinD[i] = Math.sin(d);
  }
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (ink == null) {
    init();
  }

  var dt = isFinite(deltaMs) ? Math.max(0, Math.min(deltaMs / 1000, 0.25)) : 0;
  var frameEquivalent = dt * REFERENCE_FPS;
  time += dt * TIME_RATE * speed * 2;
  if (time >= TIME_PERIOD) {
    time -= Math.floor(time / TIME_PERIOD) * TIME_PERIOD;
  }

  motionScale = motion * 2;
  sampleStep = 1 + Math.floor((1 - density) * 3.999999);
  var overallScale = 0.4 + size * 1.2;
  scaleX = overallScale * (0.5 + width);
  scaleY = overallScale * (0.5 + height);
  var angle = (rotation - 0.5) * Math.PI * 2;
  cosRotation = Math.cos(angle);
  sinRotation = Math.sin(angle);
  panX = (centerX - 0.5) * 1.2;
  panY = (centerY - 0.5) * 1.2;
  glowOffset = glow * 6;
  outputBackground = background;
  outputLevel = level;

  // Defaults reproduce background(6, 96) and stroke(400, 46). Convert their
  // per-60fps-frame alpha to the actual frame duration so trails do not change
  // length when the renderer's frame rate changes.
  var backgroundAlpha60 = 1 - trails * 0.96;
  var backgroundTransmission = Math.pow(1 - backgroundAlpha60, frameEquivalent);
  var pointAlpha60 = opacity * (115 / 255);
  pointAlpha = 1 - Math.pow(1 - pointAlpha60, frameEquivalent);

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  // Translucent background: fade old poses toward the selected background.
  for (var cell = 0; cell < GRID_CELLS; ++cell) {
    ink[cell] = outputBackground +
      (ink[cell] - outputBackground) * backgroundTransmission;
  }

  var cosTime = Math.cos(time);
  var sinTime = Math.sin(time);

  // Step both grid axes equally so reduced Density does not introduce stripes.
  for (var sourceRow = 0; sourceRow < 200; sourceRow += sampleStep) {
    var rowBase = sourceRow * 200;
    for (var sourceColumn = 0; sourceColumn < 200; sourceColumn += sampleStep) {
      var i = rowBase + sourceColumn;
      // sin(2d + t) and cos(d + t), expanded from precomputed d phases.
      var limbWave = pointSin2D[i] * cosTime + pointCos2D[i] * sinTime;
      var bodyWave = pointCosD[i] * cosTime - pointSinD[i] * sinTime;
      var sourceX = (pointBaseX[i] +
        pointMotionX[i] * motionScale * limbWave) / 400;
      var sourceY = (pointBaseY[i] + 28.5 * motionScale * bodyWave) / 400;

      var dx = (sourceX - 0.5) * scaleX;
      var dy = (sourceY - 0.5) * scaleY;
      var x = dx * cosRotation - dy * sinRotation + 0.5 + panX;
      var y = dx * sinRotation + dy * cosRotation + 0.5 + panY;
      splat(x * GRID_LAST, y * GRID_LAST);
    }
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
  var center = sampleInk(gx, gy);
  var signal = Math.max(0, center - outputBackground);
  signal += 0.32 * Math.max(0, sampleInk(gx - glowOffset, gy) - outputBackground);
  signal += 0.32 * Math.max(0, sampleInk(gx + glowOffset, gy) - outputBackground);
  signal += 0.32 * Math.max(0, sampleInk(gx, gy - glowOffset) - outputBackground);
  signal += 0.32 * Math.max(0, sampleInk(gx, gy + glowOffset) - outputBackground);
  signal += 0.14 * Math.max(0, sampleInk(gx - glowOffset, gy - glowOffset) - outputBackground);
  signal += 0.14 * Math.max(0, sampleInk(gx + glowOffset, gy - glowOffset) - outputBackground);
  signal += 0.14 * Math.max(0, sampleInk(gx - glowOffset, gy + glowOffset) - outputBackground);
  signal += 0.14 * Math.max(0, sampleInk(gx + glowOffset, gy + glowOffset) - outputBackground);

  var luminance = Math.min(1, outputBackground + signal);
  return hsb(hue * 360, saturation * 100, luminance * outputLevel * 100);
}

/** Composites one translucent white point into four anti-aliased cells. */
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

  var index = y0 * GRID_SIZE + x0;
  var alpha = pointAlpha * (1 - fx) * (1 - fy);
  ink[index] += (1 - ink[index]) * alpha;
  index = y0 * GRID_SIZE + x1;
  alpha = pointAlpha * fx * (1 - fy);
  ink[index] += (1 - ink[index]) * alpha;
  index = y1 * GRID_SIZE + x0;
  alpha = pointAlpha * (1 - fx) * fy;
  ink[index] += (1 - ink[index]) * alpha;
  index = y1 * GRID_SIZE + x1;
  alpha = pointAlpha * fx * fy;
  ink[index] += (1 - ink[index]) * alpha;
}

function sampleInk(x, y) {
  if (x < 0 || x > GRID_LAST || y < 0 || y > GRID_LAST) {
    return outputBackground;
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
