/**
 * Curved, texture-mapped warp tunnel.
 *
 * Each point casts a ray into a cylindrical tunnel. The tunnel centerline bends
 * with depth, so high X/Y Curvature moves the black far aperture off-center and
 * lets the near wall naturally occlude it. Manifold and Perlin are seamless:
 * they wrap around the wall and travel in opposite directions along its axis.
 */

var ImageIO = Java.type("javax.imageio.ImageIO");
var JFile = Java.type("java.io.File");
var System = Java.type("java.lang.System");
var IntArray = Java.type("int[]");

var IMAGE_DIR = "Images/Noise";
var MANIFOLD_FILE = "Manifold_14-128x128.png";
var PERLIN_FILE = "Perlin_14-128x128.png";
var MEDIA_ROOT = null;

var TAU = Math.PI * 2;
var MARCH_STEPS = 30;
var REFINE_STEPS = 5;

knob("speedIn", "Speed In", "Manifold speed traveling into the tunnel", 0.62);
knob("speedOut", "Speed Out", "Perlin speed traveling out of the tunnel", 0.38);
knob("curveX", "X Curvature", "Horizontal bend; center is straight, ends are -1 and +1", 0.5);
knob("curveY", "Y Curvature", "Vertical bend; center is straight, ends are -1 and +1", 0.5);
knob("bend", "Bend", "Strength of the X/Y curvature and aperture occlusion", 0.72);
knob("iris", "Iris", "Apparent size of the perfectly black far aperture", 0.42);
knob("fov", "FOV", "Perspective spread from telephoto to wide angle", 0.45);
knob("length", "Length", "Texture repeats along the tunnel", 0.55);
knob("around", "Around", "Texture repeats around the tunnel wall", 0.32);
knob("detail", "Texture Mix", "Perlin contribution over the Manifold texture", 0.34);
knob("manifoldContrast", "Manifold Contrast", "Manifold contrast multiplier; center is 1x", 0.5);
knob("manifoldLevel", "Manifold Level", "Manifold level multiplier: center is 1x, maximum is 4x", 0.5);
knob("perlinContrast", "Perlin Contrast", "Perlin contrast multiplier; center is 1x", 0.5);
knob("perlinLevel", "Perlin Level", "Perlin level multiplier: center is 1x, maximum is 4x", 0.5);
knob("twist", "Twist", "Spiral twist accumulated with tunnel depth", 0.5);
knob("rim", "Iris Rim", "Thickness of the solid white band around the aperture", 0.46);
knob("hue", "Hue", "Tunnel tint hue", 0.59);
knob("sat", "Saturation", "Tunnel tint saturation; zero preserves grayscale", 0.24);
knob("level", "Level", "Overall tunnel brightness", 0.86);
toggle("autoAspect", "Aspect", "Correct the projection for a non-square model", true);

var manifoldImage = null;
var perlinImage = null;
var resolvedRoot;

// Per-frame values. Keeping these here avoids recalculating knob mappings for
// every LED, and integrating phases prevents speed changes from causing jumps.
var phaseIn = 0;
var phaseOut = 0;
var aspectX = 1;
var tunnelDepth = 3;
var raySpread = 1;
var bendX = 0;
var bendY = 0;
var axialRepeats = 3;
var circularRepeats = 1;
var twistRate = 0;
var manifoldContrastAmount = 1;
var manifoldLevelAmount = 1;
var perlinContrastAmount = 1;
var perlinLevelAmount = 1;

function init() {
  manifoldImage = loadTexture(MANIFOLD_FILE);
  perlinImage = loadTexture(PERLIN_FILE);
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;
  phaseIn = wrap01(phaseIn + dt * lerp(0.02, 1.8, speedIn));
  phaseOut = wrap01(phaseOut + dt * lerp(0.02, 1.8, speedOut));

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  // A near end plane makes a large iris; a distant plane shrinks it.
  tunnelDepth = lerp(7.5, 1.35, iris);
  raySpread = lerp(0.7, 1.65, fov);
  var bendStrength = lerp(0, 7.25, bend);
  bendX = (curveX - 0.5) * 2 * bendStrength;
  bendY = (curveY - 0.5) * 2 * bendStrength;
  // The old Length range was 0.7..8 repeats. In practice its useful ceiling
  // was about 18% (2.014 repeats), so that point is now the top of the knob.
  // The 0% endpoint is 0.175 repeats for exceptionally long axial streaks.
  axialRepeats = lerp(0.175, 2.014, length);
  circularRepeats = lerp(0.5, 4, around);
  twistRate = (twist - 0.5) * 2.5;
  manifoldContrastAmount = Math.pow(4, (manifoldContrast - 0.5) * 2);
  manifoldLevelAmount = levelMultiplier(manifoldLevel);
  perlinContrastAmount = Math.pow(4, (perlinContrast - 0.5) * 2);
  perlinLevelAmount = levelMultiplier(perlinLevel);
}

function renderPoint(point, deltaMs) {
  if (manifoldImage == null || perlinImage == null) {
    return rgb(0, 0, 0);
  }

  // Ray position at depth z is (screenX * z, screenY * z). Tunnel radius is
  // one world unit, and its centerline follows a quadratic bend.
  var screenX = (point.xn - 0.5) * 2 * aspectX * raySpread;
  var screenY = (point.yn - 0.5) * 2 * raySpread;
  var previousZ = 0;
  var hitZ = -1;

  for (var i = 1; i <= MARCH_STEPS; ++i) {
    var z = tunnelDepth * i / MARCH_STEPS;
    if (wallFunction(screenX, screenY, z) >= 0) {
      var low = previousZ;
      var high = z;
      for (var j = 0; j < REFINE_STEPS; ++j) {
        var middle = (low + high) * 0.5;
        if (wallFunction(screenX, screenY, middle) >= 0) {
          high = middle;
        } else {
          low = middle;
        }
      }
      hitZ = (low + high) * 0.5;
      break;
    }
    previousZ = z;
  }

  // A ray that reaches the far end is inside the aperture. It is deliberately
  // absolute black, independent of Level, tint, texture, or rim controls.
  if (hitZ < 0) {
    return rgb(0, 0, 0);
  }

  var progress = hitZ / tunnelDepth;
  var centerX = bendX * progress * progress;
  var centerY = bendY * progress * progress;
  var localX = screenX * hitZ - centerX;
  var localY = screenY * hitZ - centerY;
  var angle = Math.atan2(localY, localX) / TAU;

  // A hard, unshaded white collar separates the textured wall from the black
  // aperture. It deliberately bypasses every color and brightness control.
  var rimWidth = lerp(0.06, 0.38, rim);
  if (progress >= 1 - rimWidth) {
    return rgb(255, 255, 255);
  }

  // Increasing z points into the page. These signs make Manifold move deeper
  // while Perlin moves toward the viewer, without changing their spatial map.
  var u = angle * circularRepeats + twistRate * progress;
  var axial = hitZ * axialRepeats;
  var manifoldValue = sampleGray(manifoldImage, u, axial - phaseIn);
  var perlinValue = sampleGray(perlinImage, u, axial + phaseOut);

  manifoldValue = clamp(
    (manifoldValue - 0.5) * manifoldContrastAmount + 0.5,
    0,
    1
  ) * manifoldLevelAmount;
  perlinValue = clamp(
    (perlinValue - 0.5) * perlinContrastAmount + 0.5,
    0,
    1
  ) * perlinLevelAmount;

  var value = lerp(manifoldValue, perlinValue, detail);
  value = clamp(value, 0, 1);

  var depthShade = lerp(0.58, 1, progress);
  var brightness = clamp(value * depthShade, 0, 1);
  return hsb(hue * 360, sat * 100, brightness * level * 100);
}

/** Signed squared distance from a ray point to the bent unit-radius wall. */
function wallFunction(screenX, screenY, z) {
  var progress = z / tunnelDepth;
  var centerX = bendX * progress * progress;
  var centerY = bendY * progress * progress;
  var dx = screenX * z - centerX;
  var dy = screenY * z - centerY;
  return dx * dx + dy * dy - 1;
}

function wrap01(value) {
  return value - Math.floor(value);
}

/** 0 at the bottom, 1x at center, and an expanded 4x ceiling. */
function levelMultiplier(value) {
  return value <= 0.5 ? value * 2 : 1 + (value - 0.5) * 6;
}

/** Bilinear grayscale sampling with seamless wrapping on both axes. */
function sampleGray(image, u, v) {
  u = wrap01(u);
  v = wrap01(v);
  var x = u * image.w;
  var y = v * image.h;
  var x0 = Math.floor(x) % image.w;
  var y0 = Math.floor(y) % image.h;
  var x1 = (x0 + 1) % image.w;
  var y1 = (y0 + 1) % image.h;
  var tx = x - Math.floor(x);
  var ty = y - Math.floor(y);
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
    throw new Error("WarpDrive.js: could not find " + IMAGE_DIR + " — set MEDIA_ROOT");
  }
  var file = new JFile(new JFile(root, IMAGE_DIR), name);
  var buffered = ImageIO.read(file);
  if (buffered == null) {
    throw new Error("WarpDrive.js: could not read " + file);
  }
  var w = buffered.getWidth();
  var h = buffered.getHeight();
  return {
    w: w,
    h: h,
    px: buffered.getRGB(0, 0, w, h, new IntArray(w * h), 0, w)
  };
}
