/**
 * Theme Colorizer
 *
 * Maps normalized incoming luminosity across every color in the active theme
 * swatch. Gain first multiplies incident luminosity as a raw signal operation.
 * Gained luminosity below Cutoff Threshold still uses its mapped theme color,
 * but that RGB is dimmed by Shade. Input alpha is preserved exactly.
 */

knob("threshold", "Cutoff Threshold", "Shade input whose raw luminosity is below this 0-1 level", 0);
knob("shade", "Shade", "RGB multiplier below the cutoff threshold", 0.3);
// Chromatik script knobs are normalized. 0.2 maps to the requested default 1x.
knob("gain", "Gain", "Raw luminosity gain from 0.5x to 3x; 0.2 is 1x", 0.2);

/** LX luminosity, normalized to 0..1. */
function inputLuminosity(color) {
  var r = (LXColor.red(color) & 0xff) / 255;
  var g = (LXColor.green(color) & 0xff) / 255;
  var b = (LXColor.blue(color) & 0xff) / 255;
  return 0.375 * r + 0.5 * g + 0.125 * b;
}

/** Sample all active theme colors as one evenly spaced RGB gradient. */
function themeColor(position) {
  var numColors = _swatch.numColors;
  if (numColors <= 0) {
    return LXColor.BLACK;
  }
  if (numColors == 1) {
    return _swatch.colors[0].color;
  }

  var scaled = clamp(position, 0, 1) * (numColors - 1);
  var stop = Math.min(numColors - 2, Math.floor(scaled));
  return LXColor.lerp(
    _swatch.colors[stop].color,
    _swatch.colors[stop + 1].color,
    scaled - stop
  );
}

function renderPoint(point, deltaMs, enabledAmount, inputColor) {
  var gainAmount = 0.5 + gain * 2.5;
  var luminosity = inputLuminosity(inputColor) * gainAmount;
  var mapped = themeColor(clamp(luminosity, 0, 1));
  var shadeAmount = luminosity < threshold ? shade : 1;
  var inputAlpha = LXColor.alpha(inputColor) & 0xff;

  var output = rgba(
    Math.round((LXColor.red(mapped) & 0xff) * shadeAmount),
    Math.round((LXColor.green(mapped) & 0xff) * shadeAmount),
    Math.round((LXColor.blue(mapped) & 0xff) * shadeAmount),
    inputAlpha
  );

  // enabledAmount is supplied by the effect host, not exposed as a script UI
  // input. Both endpoints carry inputAlpha, so interpolation retains it.
  return LXColor.lerp(inputColor, output, clamp(enabledAmount, 0, 1));
}
