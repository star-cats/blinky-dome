/**
 * Jelly Time, ported from a つぶやきProcessing sketch.
 *
 * The original draws 10,000 translucent points into a 400 x 400 Processing
 * canvas every frame. Those points are interleaved between sixteen individual
 * jellies by `i % 16`. This version isolates three of those jellies, recenters
 * and enlarges each one. The three jellies roam independently in X and Y, and
 * every point gets a compact glow so the fine point clouds remain legible on
 * sparse LED layouts.
 */

knob("count", "Jellies", "Number of independently moving jellies, from 1 to 3", 1);
knob("form", "Form", "Choose which three strands from the original sketch become jellies", 0.44);
knob("size", "Size", "Scale of each jelly", 0.5);

knob("jellySpeed", "Jelly Speed", "Speed of the internal rippling and deformation", 0.5);
knob("moveSpeed", "Move Speed", "Speed at which the jellies travel around the frame", 0.5);
knob("moveX", "X Travel", "Horizontal roaming distance", 0.55);
knob("moveY", "Y Travel", "Vertical roaming distance", 0.5);
knob("orbit", "Orbit", "Blend irregular two-axis wandering into smooth elliptical orbits", 0);

knob("glow", "Glow", "Radius of the point-cloud glow", 0.375);
knob("opacity", "Opacity", "Opacity of each accumulated point", 0.61);
knob("hue", "Hue", "Jelly color", 0);
knob("saturation", "Saturation", "Color saturation; zero is the original white", 0);
knob("background", "Background", "Background brightness", 0.035);
knob("level", "Level", "Overall output brightness", 1);
toggle("autoAspect", "Aspect", "Keep motion and jelly shapes square on non-square models", true);

var POINT_COUNT = 10000;
var GRID_SIZE = 256;
var GRID_LAST = GRID_SIZE - 1;
var GRID_CELLS = GRID_SIZE * GRID_SIZE;

var JELLY_COUNT = 3;
var POINTS_PER_JELLY = 625;

// Independent Lissajous-like motion keeps the jellies circulating through the
// frame without locking them into a formation. Frequencies are cycles/second.
var MOVE_X_HZ = [0.071, 0.093, 0.057];
var MOVE_Y_HZ = [0.089, 0.061, 0.107];
var MOVE_X_PHASE = [0.2, 2.4, 4.5];
var MOVE_Y_PHASE = [1.4, 4.1, 5.6];

// Processing advances t by PI / 20 per draw. At its conventional 60 fps that
// is 3 PI units per second; integrating deltaMs makes the port frame-rate
// independent while preserving the original pace.
var TIME_RATE = Math.PI * 3;
var TIME_PERIOD = Math.PI * 96;

var ink = null;
var pointK = null;
var pointE = null;
var pointBaseD = null;
var jellyPointX = null;
var jellyPointY = null;

var time = 0;
var motionTime = 0;
var aspectX = 1;

// Control values resolved once per frame rather than once per LED.
var activeJellyCount = 3;
var jellyScale = 5;
var firstGroup = 7;
var moveRangeX = 0.22;
var moveRangeY = 0.2;
var orbitAmount = 0;
var glowOffset = 2.25;
var pointTransmission = 1 - 144 / 255;
var outputBackground = 9 / 255;
var outputLevel = 1;

function init() {
  ink = new Array(GRID_CELLS);
  pointK = new Array(POINT_COUNT);
  pointE = new Array(POINT_COUNT);
  pointBaseD = new Array(POINT_COUNT);
  jellyPointX = new Array(JELLY_COUNT * POINTS_PER_JELLY);
  jellyPointY = new Array(JELLY_COUNT * POINTS_PER_JELLY);

  for (var cell = 0; cell < GRID_CELLS; ++cell) {
    ink[cell] = 0;
  }

  // These terms depend only on the point index, not on animation time.
  for (var i = 0; i < POINT_COUNT; ++i) {
    var k = 9 * Math.cos(i * 5) * Math.sin(i);
    var e = 9 * Math.cos(i * 3) * Math.cos(i * 2);
    var magnitude = Math.sqrt(k * k + e * e);
    pointK[i] = k;
    pointE[i] = e;
    pointBaseD[i] = magnitude * magnitude * magnitude / 1999;
  }
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (ink == null) {
    init();
  }

  var dt = isFinite(deltaMs) ? Math.max(0, Math.min(deltaMs / 1000, 0.25)) : 0;
  var jellyRate = jellySpeed * 2;
  var travelRate = moveSpeed * 2;
  time += dt * TIME_RATE * jellyRate;
  if (time >= TIME_PERIOD) {
    time -= Math.floor(time / TIME_PERIOD) * TIME_PERIOD;
  }
  motionTime += dt * travelRate;

  activeJellyCount = Math.max(1, Math.min(3, 1 + Math.floor(count * 2.999999)));
  jellyScale = 2.5 + size * 5;
  firstGroup = Math.min(15, Math.floor(form * 15.999999));
  moveRangeX = moveX * 0.4;
  moveRangeY = moveY * 0.4;
  orbitAmount = orbit;
  glowOffset = glow * 6;
  var pointAlpha = 0.05 + opacity * 0.85;
  pointTransmission = 1 - pointAlpha;
  outputBackground = background;
  outputLevel = level;

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  for (var cell = 0; cell < GRID_CELLS; ++cell) {
    ink[cell] = 0;
  }

  for (var jelly = 0; jelly < activeJellyCount; ++jelly) {
    var group = (firstGroup + jelly) % 16;
    var m = group * 13;
    var wave = Math.sin(time / 2 + m);
    var groupWave = wave * wave * wave / 3;
    var base = jelly * POINTS_PER_JELLY;
    var sumX = 0;
    var sumY = 0;
    var pointCount = 0;

    // Selecting one residue class isolates one genuine jelly from the source;
    // no complete-scene replicas are made here.
    for (var i = group; i < POINT_COUNT; i += 16) {
      var d = pointBaseD[i] + 1.5 - groupWave;
      var c = d / 16 - time / 48 + m;
      var p = Math.pow(d, Math.sin(d * d - time + m));
      var sourceX = (99 * Math.sin(c) + pointK[i] * p + 200) / 400;
      var sourceY = (99 * Math.sin(c * 4) + pointE[i] * p + 200) / 400;
      jellyPointX[base + pointCount] = sourceX;
      jellyPointY[base + pointCount] = sourceY;
      sumX += sourceX;
      sumY += sourceY;
      ++pointCount;
    }

    // Each strand naturally travels around the original scene. Recenter it on
    // its own live centroid before scaling, then give it an independent X/Y
    // orbit instead of pinning all three to fixed positions.
    var centerX = sumX / pointCount;
    var centerY = sumY / pointCount;
    var xFrequency = MOVE_X_HZ[jelly];
    var yFrequency = MOVE_Y_HZ[jelly] +
      (xFrequency - MOVE_Y_HZ[jelly]) * orbitAmount;
    var yPhase = MOVE_Y_PHASE[jelly] +
      (MOVE_X_PHASE[jelly] + Math.PI / 2 - MOVE_Y_PHASE[jelly]) * orbitAmount;
    var movingX = 0.5 + moveRangeX * Math.sin(
      Math.PI * 2 * MOVE_X_HZ[jelly] * motionTime + MOVE_X_PHASE[jelly]
    );
    var movingY = 0.5 + moveRangeY * Math.sin(
      Math.PI * 2 * yFrequency * motionTime + yPhase
    );
    for (var n = 0; n < pointCount; ++n) {
      var x = movingX + (jellyPointX[base + n] - centerX) * jellyScale;
      var y = movingY + (jellyPointY[base + n] - centerY) * jellyScale;
      splat(x * GRID_LAST, y * GRID_LAST);
    }
  }
}

function renderPoint(point, deltaMs) {
  if (ink == null) {
    return gray(outputBackground * 100);
  }

  // Preserve the source's square canvas on non-square models.
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

  // Repeated translucent points composite over the selected background.
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
