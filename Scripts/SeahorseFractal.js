/**
 * Mandelbrot / Multibrot explorer, aimed at the famous Seahorse Valley.
 *
 * The default view is the famous Seahorse Valley at
 * -0.743643887037151 + 0.131825904205330i. The camera advances exponentially
 * toward it and slowly rotates around it. Zoom and rotation use independent,
 * frame-rate-independent clocks.
 *
 * A finite-precision Mandelbrot cannot zoom inward literally forever. This one
 * overlaps the last two octaves with a fresh, wider recursive pass. The blend
 * reaches the exact first frame before the zoom clock wraps, making the loop
 * continuous instead of cutting to black or visibly resetting. The iteration
 * budget also rises with zoom depth so fine boundary detail does not disappear.
 *
 * Power continuously changes z^2 + c into the wider Multibrot family. Julia
 * Morph continuously moves from Mandelbrot coordinates (z = 0, c = pixel) to
 * a Julia set (z = pixel, c = Julia Re + Julia Im i).
 */

knob("zoomSpeed", "Zoom Speed", "Exponential camera zoom; zero pauses", 0.28);
knob("rotateSpeed", "Rotate Speed", "Camera rotation; center is still, ends reverse direction", 0.56);
knob("centerX", "Center X", "Zoom target across the complex plane", 0.467709004470);
knob("centerY", "Center Y", "Zoom target across the complex plane", 0.547080680073);

knob("power", "Power", "Continuous exponent in z^power + c; 0 is quadratic", 0);
knob("juliaMorph", "Julia Morph", "Continuously morph Mandelbrot into a Julia set", 0);
knob("juliaRe", "Julia Re", "Real part of the Julia constant", 0.4);
knob("juliaIm", "Julia Im", "Imaginary part of the Julia constant", 0.552);
knob("iterations", "Iterations", "Base escape iterations; deep zoom adds more automatically", 0.48);

knob("bands", "Bands", "Monochrome detail frequency across the boundary", 0.38);
knob("level", "Level", "Maximum normalized output level", 0.9);
toggle("autoAspect", "Aspect", "Correct for a non-square model", true);

var TAU = Math.PI * 2;
var LOOP_ZOOM_OCTAVES = 14;
var LOOP_BLEND_OCTAVES = 2;
var BASE_VIEW_WIDTH = 0.035;

var zoomOctaves = 0;
var cameraAngle = 0;
var aspectX = 1;

// Values prepared once per frame rather than once per LED.
var cosCamera = 1;
var sinCamera = 0;
var targetX = -0.743643887037151;
var targetY = 0.131825904205330;
var exponent = 2;
var baseIterations = 100;
var juliaX = -0.8;
var juliaY = 0.156;

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;

  // Logarithmic mappings keep the slow end useful. Zoom is always inward;
  // rotation is bipolar and exactly stationary at the center detent.
  var zoomRate = zoomSpeed <= 0.001 ? 0 : 0.01 * Math.pow(40, zoomSpeed);
  var spin = (rotateSpeed - 0.5) * 2;
  var turnsPerSecond = 0.12 * spin * Math.abs(spin);

  zoomOctaves += dt * zoomRate;
  if (zoomOctaves >= LOOP_ZOOM_OCTAVES) {
    zoomOctaves -= LOOP_ZOOM_OCTAVES;
  }
  cameraAngle = wrapAngle(cameraAngle + dt * turnsPerSecond * TAU);

  cosCamera = Math.cos(cameraAngle);
  sinCamera = Math.sin(cameraAngle);
  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  targetX = lerp(-2.1, 0.8, centerX);
  targetY = lerp(-1.4, 1.4, centerY);
  exponent = lerp(2, 6, power);
  baseIterations = Math.round(lerp(40, 180, iterations));
  juliaX = lerp(-2, 1, juliaRe);
  juliaY = lerp(-1.5, 1.5, juliaIm);
}

function renderPoint(point, deltaMs) {
  var screenX = (point.xn - 0.5) * aspectX;
  var screenY = point.yn - 0.5;

  // Rotate the camera axes while leaving the zoom target fixed.
  var rotatedX = screenX * cosCamera - screenY * sinCamera;
  var rotatedY = screenX * sinCamera + screenY * cosCamera;
  var deepEscape = sampleFractal(rotatedX, rotatedY, zoomOctaves);
  var output = intensityForEscape(deepEscape);

  var blendStart = LOOP_ZOOM_OCTAVES - LOOP_BLEND_OCTAVES;
  if (zoomOctaves <= blendStart) {
    return normalizedGray(output);
  }

  // While the deepest pass fades away, a second pass travels from two octaves
  // outside the opening view to exactly the opening view. At the wrap it is
  // therefore pixel-identical to the new primary pass, including rotation.
  var blend = smoothstep(blendStart, LOOP_ZOOM_OCTAVES, zoomOctaves);
  var recursiveDepth = zoomOctaves - LOOP_ZOOM_OCTAVES;
  var recursiveEscape = sampleFractal(rotatedX, rotatedY, recursiveDepth);
  output = lerp(output, intensityForEscape(recursiveEscape), blend);
  return normalizedGray(output);
}

function sampleFractal(rotatedX, rotatedY, zoomDepth) {
  var viewWidth = BASE_VIEW_WIDTH * Math.pow(2, -zoomDepth);
  var pixelX = targetX + rotatedX * viewWidth;
  var pixelY = targetY + rotatedY * viewWidth;

  // These endpoints are the canonical Mandelbrot and Julia initial states;
  // interpolating both makes every intermediate Morph value continuous.
  var morph = juliaMorph;
  var zx = pixelX * morph;
  var zy = pixelY * morph;
  var cx = lerp(pixelX, juliaX, morph);
  var cy = lerp(pixelY, juliaY, morph);
  var radiusSquared = 0;
  var i = 0;
  var maxIterations = baseIterations + Math.round(Math.max(0, zoomDepth) * 9);

  if (Math.abs(exponent - 2) < 0.0001) {
    // Fast path for the classic Mandelbrot set.
    for (; i < maxIterations; ++i) {
      var zx2 = zx * zx;
      var zy2 = zy * zy;
      if (zx2 + zy2 > 256) {
        radiusSquared = zx2 + zy2;
        break;
      }
      zy = 2 * zx * zy + cy;
      zx = zx2 - zy2 + cx;
    }
  } else {
    // Polar complex power supports non-integer exponents, so the Power knob
    // genuinely morphs instead of snapping between integer fractals.
    for (; i < maxIterations; ++i) {
      radiusSquared = zx * zx + zy * zy;
      if (radiusSquared > 256) {
        break;
      }
      var radiusPower = Math.pow(radiusSquared, exponent * 0.5);
      var argument = Math.atan2(zy, zx) * exponent;
      zx = radiusPower * Math.cos(argument) + cx;
      zy = radiusPower * Math.sin(argument) + cy;
    }
  }

  // Points that did not escape belong to the filled set. A negative sentinel
  // lets the caller color and crossfade without allocating an object per LED.
  if (i >= maxIterations) {
    return -1;
  }

  return i + 1 - Math.log(Math.log(Math.sqrt(radiusSquared))) / Math.log(exponent);
}

function intensityForEscape(smoothIteration) {
  if (smoothIteration < 0) {
    return 0;
  }

  // Fractional escape time removes the hard contour lines of raw iteration
  // counts. The result stays normalized for a downstream recoloring stage.
  var frequency = lerp(0.035, 0.22, bands);
  var band = 0.72 + 0.28 * Math.cos(TAU * smoothIteration * frequency);
  return clamp(level * band, 0, 1);
}

function normalizedGray(value) {
  // Chromatik's gray() takes 0..100 brightness. `value` remains the canonical
  // 0..1 signal and gray() copies it equally into the rendered RGB channels.
  return gray(clamp(value, 0, 1) * 100);
}

function smoothstep(edge0, edge1, value) {
  var t = clamp((value - edge0) / (edge1 - edge0), 0, 1);
  return t * t * (3 - 2 * t);
}

function wrapAngle(value) {
  if (value > Math.PI || value < -Math.PI) {
    value -= Math.floor((value + Math.PI) / TAU) * TAU;
  }
  return value;
}
