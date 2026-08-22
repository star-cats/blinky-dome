/**
 * Clockworx, inspired by a compact Processing particle sketch.
 *
 * Fifteen random unit vectors are born per source frame, up to roughly 5,000.
 * Their X/Y components move through the original bitwise clockwork field while
 * a translucent background leaves fading traces. Particles live in fixed-size
 * ring buffers, so the pattern does not allocate continuously as it runs.
 */

knob("speed", "Speed", "Rate of the clockwork particle simulation", 0.5);
knob("birthRate", "Birth Rate", "How quickly new particles enter the dance", 0.5);
knob("population", "Population", "Maximum number of live particles", 1);
knob("drift", "Drift", "Distance particles move on each simulation step", 0.5);
knob("gears", "Gears", "Number of bit-mask gears; center is the original & 7 field", 0.5);
knob("torque", "Torque", "Strength of the discrete rotational field", 0.5);

knob("size", "Zoom", "Mechanism zoom from 0.1x to 10x; center is 1x", 0.5);
knob("rotation", "Rotation", "Rotate the complete mechanism; center is upright", 0.5);
knob("centerX", "Center X", "Horizontal mechanism position", 0.5);
knob("centerY", "Center Y", "Vertical mechanism position", 0.5);

knob("trails", "Trails", "Persistence of previous particle positions", 0.726);
knob("glow", "Glow", "Radius of the particle glow", 0.375);
knob("opacity", "Opacity", "Opacity of each particle", 0.502);
knob("hue", "Hue", "Particle color", 0);
knob("saturation", "Saturation", "Color saturation; zero is the original white", 0);
knob("background", "Background", "Background brightness", 0);
knob("level", "Level", "Overall output brightness", 1);
toggle("autoAspect", "Aspect", "Keep the mechanism circular on non-square models", true);

var MAX_PARTICLES = 5015;
var SOURCE_BIRTHS_PER_FRAME = 15;
var REFERENCE_FPS = 60;
// Dividing this by 90 gives 32,000, whose low five bits are zero. Subtracting
// it therefore leaves every supported & mask (1 through 31) exactly unchanged.
var CLOCK_PERIOD = 2880000;

var GRID_SIZE = 256;
var GRID_LAST = GRID_SIZE - 1;
var GRID_CELLS = GRID_SIZE * GRID_SIZE;

var ink = null;
var particleX = null;
var particleY = null;
var particleHead = 0;
var particleCount = 0;

var spawnAccumulator = 0;
var motionAccumulator = 0;
var clock = 0;
var aspectX = 1;

// Per-frame control values.
var particleLimit = MAX_PARTICLES;
var driftScale = 1;
var gearMask = 7;
var torqueScale = 9;
var sceneScale = 1;
var cosRotation = 1;
var sinRotation = 0;
var panX = 0;
var panY = 0;
var glowOffset = 2.25;
var pointAlpha = 96 / 255;
var outputBackground = 0;
var outputLevel = 1;

function init() {
  ink = new Array(GRID_CELLS);
  particleX = new Array(MAX_PARTICLES);
  particleY = new Array(MAX_PARTICLES);

  for (var cell = 0; cell < GRID_CELLS; ++cell) {
    ink[cell] = 0;
  }
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (ink == null) {
    init();
  }

  var dt = isFinite(deltaMs) ? Math.max(0, Math.min(deltaMs / 1000, 0.25)) : 0;
  var frameEquivalent = dt * REFERENCE_FPS;

  particleLimit = Math.round(200 + population * (MAX_PARTICLES - 200));
  driftScale = drift * 2;
  var gearBits = 1 + Math.floor(gears * 4.999999);
  gearMask = Math.pow(2, gearBits) - 1;
  torqueScale = 2 + torque * 14;
  // Two decades of exponential zoom with an exact 1x center detent.
  sceneScale = Math.pow(10, (size - 0.5) * 2);

  var angle = (rotation - 0.5) * Math.PI * 2;
  cosRotation = Math.cos(angle);
  sinRotation = Math.sin(angle);
  // Pan is effectively measured in source space, then enlarged with Zoom. At
  // 10x this gives +/-6 screen widths for exploring the magnified mechanism.
  panX = (centerX - 0.5) * 1.2 * sceneScale;
  panY = (centerY - 0.5) * 1.2 * sceneScale;
  glowOffset = glow * 6;
  outputBackground = background;
  outputLevel = level;

  // background(0, 9) at the Trails default, generalized to the actual frame
  // duration. The exponential mapping gives the upper knob range useful room.
  var backgroundAlpha60 = Math.pow(0.01, trails);
  var backgroundTransmission = Math.pow(1 - backgroundAlpha60, frameEquivalent);
  var pointAlpha60 = opacity * 0.75;
  pointAlpha = 1 - Math.pow(1 - pointAlpha60, frameEquivalent);

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  for (var cell = 0; cell < GRID_CELLS; ++cell) {
    ink[cell] = outputBackground +
      (ink[cell] - outputBackground) * backgroundTransmission;
  }

  // If Population is reduced, discard the oldest particles first.
  while (particleCount > particleLimit) {
    particleHead = (particleHead + 1) % MAX_PARTICLES;
    --particleCount;
  }

  var birthsPerFrame = birthRate * SOURCE_BIRTHS_PER_FRAME * 2;
  spawnAccumulator += frameEquivalent * birthsPerFrame;
  var births = Math.floor(spawnAccumulator);
  spawnAccumulator -= births;
  for (var born = 0; born < births; ++born) {
    spawnParticle();
  }

  // Fixed source-frame steps keep the nonlinear state update stable. A hitch
  // may catch up several steps, but deltaMs is capped above to bound the work.
  motionAccumulator += frameEquivalent * speed * 2;
  var motionSteps = Math.floor(motionAccumulator);
  motionAccumulator -= motionSteps;
  for (var step = 0; step < motionSteps; ++step) {
    ++clock;
    if (clock >= CLOCK_PERIOD) {
      clock -= CLOCK_PERIOD;
    }
    updateParticles();
  }

  for (var n = 0; n < particleCount; ++n) {
    var index = (particleHead + n) % MAX_PARTICLES;
    var dx = particleX[index] * (119 / 540) * sceneScale;
    var dy = particleY[index] * (119 / 540) * sceneScale;
    var x = dx * cosRotation - dy * sinRotation + 0.5 + panX;
    var y = dx * sinRotation + dy * cosRotation + 0.5 + panY;
    splat(x * GRID_LAST, y * GRID_LAST);
  }
}

/** Adds one p5.Vector.random3D()-equivalent particle to the ring. */
function spawnParticle() {
  var z = Math.random() * 2 - 1;
  var angle = Math.random() * Math.PI * 2;
  var radius = Math.sqrt(Math.max(0, 1 - z * z));
  var x = radius * Math.cos(angle);
  var y = radius * Math.sin(angle);

  var index;
  if (particleCount < particleLimit) {
    index = (particleHead + particleCount) % MAX_PARTICLES;
    ++particleCount;
  } else {
    index = particleHead;
    particleHead = (particleHead + 1) % MAX_PARTICLES;
  }
  particleX[index] = x;
  particleY[index] = y;
}

function updateParticles() {
  for (var n = 0; n < particleCount; ++n) {
    var index = (particleHead + n) % MAX_PARTICLES;
    var x = particleX[index];
    var y = particleY[index];
    var k = x + 5 + y;

    // Parentheses make the compact source's coercion explicit: ^ and & are
    // JavaScript 32-bit integer operators even though their inputs are floats.
    var bucket = ((x * k ^ y * k + clock / 90) & gearMask);
    var r = torqueScale * bucket - 0.1;
    x += Math.sin(r * y) / 119 * driftScale;
    y += Math.cos(x * r) / 119 * driftScale; // Uses the newly updated x.
    particleX[index] = x;
    particleY[index] = y;
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
