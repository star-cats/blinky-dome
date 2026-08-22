/**
 * Hivespark, ported from a compact Processing particle sketch.
 *
 * Random unit vectors accumulate into a 4,000-particle binary flow field. The
 * source's changing RGB strokes are converted to normalized Rec.709 luminance,
 * retaining their rhythmic variation as a strictly monochrome black-to-white
 * signal. A persistent buffer reproduces background(0, 9) motion trails.
 */

knob("speed", "Speed", "Rate of the particle-flow simulation", 0.5);
knob("birthRate", "Birth Rate", "How quickly new sparks enter the hive", 0.5);
knob("population", "Population", "Maximum number of live sparks", 1);
knob("drift", "Drift", "Distance sparks move on each simulation step", 0.5);
knob("xCells", "X Cells", "Horizontal frequency of the binary flow field", 0.43);
knob("yCells", "Y Cells", "Vertical frequency of the binary flow field", 0.13);

knob("zoom", "Zoom", "Hive zoom from 0.1x to 10x; center is 1x", 0.5);
knob("rotation", "Rotation", "Rotate the complete hive; center is upright", 0.5);
knob("centerX", "Center X", "Horizontal hive position", 0.5);
knob("centerY", "Center Y", "Vertical hive position", 0.5);

knob("trails", "Trails", "Persistence of previous spark positions", 0.726);
knob("glow", "Glow", "Radius of the spark glow", 0.375);
knob("opacity", "Opacity", "Opacity of each monochrome spark", 1);
knob("contrast", "Contrast", "Contrast of the black-to-white particle sequence", 0.5);
knob("background", "Background", "Background brightness", 0);
knob("level", "Level", "Overall output brightness", 1);
toggle("autoAspect", "Aspect", "Keep the hive circular on non-square models", true);

var MAX_PARTICLES = 4000;
var SOURCE_BIRTHS_PER_FRAME = 15;
var REFERENCE_FPS = 60;
var MAX_SOURCE_LUMA = 0.8230737254901961;

var GRID_SIZE = 256;
var GRID_LAST = GRID_SIZE - 1;
var GRID_CELLS = GRID_SIZE * GRID_SIZE;

var ink = null;
var particleX = null;
var particleY = null;
var particleZ = null;
var particleHead = 0;
var particleCount = 0;

var spawnAccumulator = 0;
var motionAccumulator = 0;
var clock = 0;
var aspectX = 1;

var particleLimit = MAX_PARTICLES;
var driftScale = 1;
var fieldX = 4;
var fieldY = 2;
var sceneScale = 1;
var cosRotation = 1;
var sinRotation = 0;
var panX = 0;
var panY = 0;
var glowOffset = 2.25;
var pointAlpha = 1;
var lumaGamma = 1;
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

  particleLimit = Math.round(200 + population * (MAX_PARTICLES - 200));
  driftScale = drift * 2;
  fieldX = 1 + Math.floor(xCells * 7.999999);
  fieldY = 1 + Math.floor(yCells * 7.999999);
  sceneScale = Math.pow(10, (zoom - 0.5) * 2);
  lumaGamma = Math.pow(4, (contrast - 0.5) * 2);

  var angle = (rotation - 0.5) * Math.PI * 2;
  cosRotation = Math.cos(angle);
  sinRotation = Math.sin(angle);
  // Pan in source space so its range grows with magnification.
  panX = (centerX - 0.5) * 1.2 * sceneScale;
  panY = (centerY - 0.5) * 1.2 * sceneScale;
  glowOffset = glow * 6;
  outputBackground = background;
  outputLevel = level;

  var backgroundAlpha60 = Math.pow(0.01, trails);
  var backgroundTransmission = Math.pow(1 - backgroundAlpha60, frameEquivalent);
  pointAlpha = 1 - Math.pow(1 - opacity, frameEquivalent);

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  for (var cell = 0; cell < GRID_CELLS; ++cell) {
    ink[cell] = outputBackground +
      (ink[cell] - outputBackground) * backgroundTransmission;
  }

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

  motionAccumulator += frameEquivalent * speed * 2;
  var motionSteps = Math.floor(motionAccumulator);
  motionAccumulator -= motionSteps;
  for (var step = 0; step < motionSteps; ++step) {
    ++clock;
    updateParticles();
  }

  // Original color sequence, converted to perceptual luminance and normalized
  // so its brightest RGB triplet becomes white rather than pale gray.
  for (var n = 0; n < particleCount; ++n) {
    var index = (particleHead + n) % MAX_PARTICLES;
    var red = (n % 15) * 17;
    var green = (n % 5) * 50;
    var blue = (n % 10) * 25;
    var sourceLuma = (0.2126 * red + 0.7152 * green + 0.0722 * blue) /
      (255 * MAX_SOURCE_LUMA);
    sourceLuma = Math.pow(sourceLuma, lumaGamma);

    var dx = particleX[index] / 6 * sceneScale;
    var dy = particleY[index] / 6 * sceneScale;
    var x = dx * cosRotation - dy * sinRotation + 0.5 + panX;
    var y = dx * sinRotation + dy * cosRotation + 0.5 + panY;
    splat(x * GRID_LAST, y * GRID_LAST, sourceLuma);
  }
}

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
  particleZ[index] = z;
}

function updateParticles() {
  var sineTime = Math.sin(clock / 99);
  for (var n = 0; n < particleCount; ++n) {
    var index = (particleHead + n) % MAX_PARTICLES;
    var x = particleX[index];
    var y = particleY[index];
    var z = particleZ[index];
    var polarity = (((x * fieldX + 9 ^ y * fieldY + 9) & 1) * 2 - 1);

    // sin(polarity*t/99) is polarity*sin(t/99), while cos(polarity) is
    // cos(1) for both signs. Expanding those identities removes all per-point
    // trig calls without altering the binary field.
    x += polarity * sineTime / 99 * z * driftScale;
    y += Math.cos(1) / 99 * z * driftScale;
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
    return gray(outputBackground * outputLevel * 100);
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

  return gray(Math.min(1, outputBackground + signal) * outputLevel * 100);
}

/** Alpha-composites one grayscale point into four anti-aliased cells. */
function splat(x, y, sourceLuma) {
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

  compositeCell(y0 * GRID_SIZE + x0, sourceLuma,
    pointAlpha * (1 - fx) * (1 - fy));
  compositeCell(y0 * GRID_SIZE + x1, sourceLuma,
    pointAlpha * fx * (1 - fy));
  compositeCell(y1 * GRID_SIZE + x0, sourceLuma,
    pointAlpha * (1 - fx) * fy);
  compositeCell(y1 * GRID_SIZE + x1, sourceLuma,
    pointAlpha * fx * fy);
}

function compositeCell(index, sourceLuma, alpha) {
  ink[index] += (sourceLuma - ink[index]) * alpha;
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
