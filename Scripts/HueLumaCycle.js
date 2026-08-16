/**
 * Cycles the hue and luminosity of the current input colors independently.
 *
 * Both controls are measured in cycles per second. Hue wraps into [0, 1) with
 * modular arithmetic. Each input luminosity is advanced by a global phase and
 * wrapped into [0, 0.65). As the wrapped luminosity rises, saturation moves
 * linearly from the input saturation toward 1. Alpha is preserved from the
 * input color.
 */

knob("hueSpeed", "Hue Speed", "Hue cycles per second", 0);
knob("lumaSpeed", "Luma Speed", "Luminosity cycles per second", 0);

var huePhase = 0;
var lumaPhase = 0;
var LUMA_MAX = 0.65;

function wrap01(value) {
  return ((value % 1) + 1) % 1;
}

function wrap0ToMax(value, maxValue) {
  return ((value % maxValue) + maxValue) % maxValue;
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;
  huePhase = wrap01(huePhase + hueSpeed * dt);
  lumaPhase = wrap0ToMax(lumaPhase + lumaSpeed * dt * LUMA_MAX, LUMA_MAX);
}

/**
 * Return the input RGB color as normalized HSL.
 */
function rgbToHsl(color) {
  var r = (LXColor.red(color) & 0xff) / 255;
  var g = (LXColor.green(color) & 0xff) / 255;
  var b = (LXColor.blue(color) & 0xff) / 255;
  var maxChannel = Math.max(r, g, b);
  var minChannel = Math.min(r, g, b);
  var chroma = maxChannel - minChannel;
  var lightness = (maxChannel + minChannel) * 0.5;
  var hue = 0;
  var saturation = 0;

  if (chroma > 0) {
    saturation = chroma / (1 - Math.abs(2 * lightness - 1));
    if (maxChannel == r) {
      hue = ((g - b) / chroma) / 6;
    } else if (maxChannel == g) {
      hue = ((b - r) / chroma + 2) / 6;
    } else {
      hue = ((r - g) / chroma + 4) / 6;
    }
    hue = wrap01(hue);
  }

  return [hue, saturation, lightness];
}

/**
 * Convert normalized HSL back to an LX color while retaining input alpha.
 */
function hslToColor(hue, saturation, lightness, alpha) {
  var chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
  var sector = hue * 6;
  var secondary = chroma * (1 - Math.abs((sector % 2) - 1));
  var r = 0;
  var g = 0;
  var b = 0;

  if (sector < 1) {
    r = chroma;
    g = secondary;
  } else if (sector < 2) {
    r = secondary;
    g = chroma;
  } else if (sector < 3) {
    g = chroma;
    b = secondary;
  } else if (sector < 4) {
    g = secondary;
    b = chroma;
  } else if (sector < 5) {
    r = secondary;
    b = chroma;
  } else {
    r = chroma;
    b = secondary;
  }

  var match = lightness - chroma * 0.5;
  return rgba(
    Math.round((r + match) * 255),
    Math.round((g + match) * 255),
    Math.round((b + match) * 255),
    alpha
  );
}

/**
 * Transform the current color for one point.
 */
function renderPoint(point, deltaMs, enabledAmount, inputColor) {
  var hsl = rgbToHsl(inputColor);
  var outputLuma = wrap0ToMax(hsl[2] + lumaPhase, LUMA_MAX);
  var lumaPosition = outputLuma / LUMA_MAX;
  var outputSaturation = lerp(hsl[1], 1, lumaPosition);
  var outputColor = hslToColor(
    wrap01(hsl[0] + huePhase),
    outputSaturation,
    outputLuma,
    LXColor.alpha(inputColor) & 0xff
  );
  return LXColor.lerp(inputColor, outputColor, clamp(enabledAmount, 0, 1));
}
