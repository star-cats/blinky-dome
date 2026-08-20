/**
 * Alpha Colorizer
 *
 * Maps a component of the incoming RGB through the active palette gradient,
 * like the native Colorize effect in Palette mode, then composites that mapped
 * RGB over the incoming RGB using the incoming alpha as opacity. The incoming
 * alpha itself is kept.
 *
 * Source selects (from left to right): Brightness, Luminosity, Red, Green,
 * Blue, Min, Average, or Alpha. Source and its eight modes mirror the native
 * Colorize effect. Palette stops use the native effect's default RGB gradient
 * interpolation.
 */

knob("source", "Source", "Brightness, Luminosity, Red, Green, Blue, Min, Average, or Alpha", 0);
knob("depth", "Depth", "Fraction of the gradient traversed by the source", 1);
knob("threshold", "Threshold", "Leave source values below this level unchanged", 0);
knob("cutoff", "Cutoff", "Pass through colors whose alpha is below this level", 0);
knob("amount", "Amount", "Depth of colorization before input alpha is applied", 1);
toggle("invert", "Invert", "Reverse the gradient direction", false);

var SOURCE_BRIGHTNESS = 0;
var SOURCE_LUMINOSITY = 1;
var SOURCE_RED = 2;
var SOURCE_GREEN = 3;
var SOURCE_BLUE = 4;
var SOURCE_MIN = 5;
var SOURCE_AVERAGE = 6;
var SOURCE_ALPHA = 7;

function sourceValue(color) {
  var mode = Math.min(7, Math.floor(clamp(source, 0, 1) * 8));
  var r = (LXColor.red(color) & 0xff) / 255;
  var g = (LXColor.green(color) & 0xff) / 255;
  var b = (LXColor.blue(color) & 0xff) / 255;

  switch (mode) {
  case SOURCE_LUMINOSITY:
    // Matches LXColor.luminosity(), expressed as a normalized value.
    return (0.375 * r) + (0.5 * g) + (0.125 * b);
  case SOURCE_RED:
    return r;
  case SOURCE_GREEN:
    return g;
  case SOURCE_BLUE:
    return b;
  case SOURCE_MIN:
    return Math.min(r, g, b);
  case SOURCE_AVERAGE:
    return (r + g + b) / 3;
  case SOURCE_ALPHA:
    return (LXColor.alpha(color) & 0xff) / 255;
  case SOURCE_BRIGHTNESS:
  default:
    // HSV brightness is the largest RGB component.
    return Math.max(r, g, b);
  }
}

/** Sample all colors in the active LX swatch as one evenly spaced gradient. */
function paletteColor(position) {
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
  var inputAlpha = LXColor.alpha(inputColor) & 0xff;
  if ((inputAlpha / 255) < cutoff) {
    return inputColor;
  }

  var position = sourceValue(inputColor);
  if (position < threshold) {
    return inputColor;
  }

  if (threshold < 1) {
    position = (position - threshold) / (1 - threshold);
  }
  position = clamp(position * depth, 0, 1);
  if (invert) {
    position = 1 - position;
  }

  var mappedColor = paletteColor(1);//position);

  // Give the mapped RGB the input alpha before lerping so the effect changes
  // RGB only. Alpha controls how strongly that RGB is painted, but is retained
  // unchanged for anything later in the pipeline.
  var mappedWithInputAlpha =
    ((inputAlpha << 24) | (mappedColor & 0x00ffffff));
  var blend = clamp(enabledAmount * amount * (inputAlpha / 255), 0, 1);
  return mappedColor;//LXColor.lerp(inputColor, mappedWithInputAlpha, blend);
}
