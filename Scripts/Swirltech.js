/**
 * Swirltech, ported from a compact Processing particle sketch.
 *
 * Random 3D vectors flow through an index-dependent singular field, flatten in
 * Z, and draw around canvas position (270,270). The source periodically prunes
 * its oldest vectors after crossing 3,000 particles; a ring buffer preserves
 * that lifecycle without continuous array allocation. Translucent background
 * fading provides the long, interwoven trails.
 */

knob("speed", "Speed", "Rate of the vector simulation and particle lifecycle", 0.5);
knob("birthRate", "Birth Rate", "How many new vectors enter each source frame", 0.5);
knob("population", "Population", "Particle threshold before old vectors are pruned", 1);
knob("flow", "Flow", "Strength of X/Y movement through the singular field", 0.5);
knob("warp", "Warp", "Strength of the radial secant distortion", 0.5);
knob("flatten", "Flatten", "How quickly particle depth collapses toward zero", 0.5);

knob("zoom", "Zoom", "Swirl zoom from 0.1x to 10x; center is 1x", 0.5);
knob("rotation", "Rotation", "Rotate the complete swirl; center is upright", 0.5);
knob("centerX", "Center X", "Horizontal swirl position", 0.5);
knob("centerY", "Center Y", "Vertical swirl position", 0.5);

knob("trails", "Trails", "Persistence of previous particle positions", 0.814194465025);
knob("glow", "Glow", "Radius of the particle glow", 0.375);
knob("opacity", "Opacity", "Opacity of each particle", 0.5);
knob("hue", "Hue", "Particle color", 0);
knob("saturation", "Saturation", "Color saturation; zero is the original white", 0);
knob("background", "Background", "Background brightness", 0);
knob("level", "Level", "Overall output brightness", 1);
toggle("autoAspect", "Aspect", "Keep the swirl circular on non-square models", true);

// Birth Rate reaches 30 at maximum, so the backing ring allows one such batch
// beyond the source's 3,000-particle threshold.
var MAX_PARTICLES = 3030;
var SOURCE_BIRTHS_PER_FRAME = 15;
var REFERENCE_FPS = 60;

var GRID_SIZE = 256;
var GRID_LAST = GRID_SIZE - 1;
var GRID_CELLS = GRID_SIZE * GRID_SIZE;

var ink = null;
var particleX = null;
var particleY = null;
var particleZ = null;
var particleHead = 0;
var particleCount = 0;

var simulationAccumulator = 0;
var frameClock = 0;
var aspectX = 1;

var particleThreshold = 3000;
var birthsPerStep = 15;
var flowScale = 1;
var warpScale = 9;
var flattenAmount = 1 / 3;
var sceneScale = 1;
var cosRotation = 1;
var sinRotation = 0;
var panX = 0;
var panY = 0;
var glowOffset = 2.25;
var pointAlpha = 62 / 255;
var outputBackground = 0;
var outputLevel = 1;

function init() {
  ink = new Array(GRID_CELLS);
  particleX = new Array(MAX_PARTICLES);
  particleY = new Array(MAX_PARTICLES);
  particleZ = new Array(MAX_PARTICLES);

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

  particleThreshold = Math.round(300 + population * 2700);
  birthsPerStep = Math.round(birthRate * SOURCE_BIRTHS_PER_FRAME * 2);
  flowScale = flow * 2;
  warpScale = warp * 18;
  flattenAmount = flatten * (2 / 3);
  sceneScale = Math.pow(10, (zoom - 0.5) * 2);

  var angle = (rotation - 0.5) * Math.PI * 2;
  cosRotation = Math.cos(angle);
  sinRotation = Math.sin(angle);
  panX = (centerX - 0.5) * 1.2 * sceneScale;
  panY = (centerY - 0.5) * 1.2 * sceneScale;
  glowOffset = glow * 6;
  pointAlpha = opacity * (124 / 255);
  outputBackground = background;
  outputLevel = level;

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  // background(0,6) at the default. Fade remains tied to wall time even when
  // Speed pauses the vector simulation.
  var backgroundAlpha60 = Math.pow(0.01, trails);
  var backgroundTransmission = Math.pow(1 - backgroundAlpha60, frameEquivalent);
  for (var cell = 0; cell < GRID_CELLS; ++cell) {
    ink[cell] = outputBackground +
      (ink[cell] - outputBackground) * backgroundTransmission;
  }

  simulationAccumulator += frameEquivalent * speed * 2;
  var simulationSteps = Math.floor(simulationAccumulator);
  simulationAccumulator -= simulationSteps;
  for (var step = 0; step < simulationSteps; ++step) {
    ++frameClock;
    updateAndDrawParticles();
    updateLifecycle();
  }
}

/** Updates and draws the current array, matching the source's map() order. */
function updateAndDrawParticles() {
  for (var n = 0; n < particleCount; ++n) {
    var index = (particleHead + n) % MAX_PARTICLES;
    var x = particleX[index];
    var y = particleY[index];
    var z = particleZ[index];
    var magnitude = Math.sqrt(x * x + y * y + z * z);
    var denominator = Math.cos(magnitude * 2 - frameClock / 99);
    var r = 4 + warpScale / denominator;

    // The source divides by array index. At n=0 this intentionally creates a
    // non-finite oldest vector; splat() clips it like an off-canvas point.
    x += Math.sin(y * r) / n * 9 * flowScale;
    y += Math.cos(r * x) / n * 9 * flowScale; // Uses the newly updated x.
    z -= z * flattenAmount;
    particleX[index] = x;
    particleY[index] = y;
    particleZ[index] = z;

    var dx = x / 6 * sceneScale;
    var dy = y / 6 * sceneScale;
    var screenX = dx * cosRotation - dy * sinRotation + 0.5 + panX;
    var screenY = dx * sinRotation + dy * cosRotation + 0.5 + panY;
    splat(screenX * GRID_LAST, screenY * GRID_LAST);
  }
}

/** Implements `$[3000] ? $.slice(-2985) : [...$, ...15 new vectors]`. */
function updateLifecycle() {
  if (particleCount > particleThreshold) {
    var keep = Math.max(0, particleThreshold - SOURCE_BIRTHS_PER_FRAME);
    var remove = particleCount - keep;
    particleHead = (particleHead + remove) % MAX_PARTICLES;
    particleCount = keep;
    return;
  }

  for (var born = 0; born < birthsPerStep; ++born) {
    spawnParticle();
  }
}

function spawnParticle() {
  var z = Math.random() * 2 - 1;
  var angle = Math.random() * Math.PI * 2;
  var radius = Math.sqrt(Math.max(0, 1 - z * z));
  var x = radius * Math.cos(angle);
  var y = radius * Math.sin(angle);

  var index;
  if (particleCount < MAX_PARTICLES) {
    index = (particleHead + particleCount) % MAX_PARTICLES;
    ++particleCount;
  } else {
    index = particleHead;
    particleHead = (particleHead + 1) % MAX_PARTICLES;
  }
  particleX[index] = x;
  particleY[index] = y;
  particleZ[index] = z;
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
