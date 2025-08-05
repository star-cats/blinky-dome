/**
 * Define knobs and toggles with a variable name, label, description and default value
 * The variable name will be populated for the render method with a value from 0-1 for
 * knobs and true/false for toggles.
 */

knob("hue", "Hue", "Hue to render", 0);
knob("sat", "Saturation", "Saturation to render", 0.5);
knob("brt", "Brightness", "Brightness to render", 0.5);
knob("cx", "X-Center", "Center X position to fade from", 0.5);
knob("cy", "Y-Center", "Center Y position to fade from", 0.5);

toggle("fade", "Fade", "Fade brightness from center position defined on X-Y plane", true);

/**
 * Return a color value for the given LXPoint
 * @param {LXPoint} point - The point to render
 * @param {number} deltaMs - Milliseconds elapsed since previous frame
 * @return {number} Color value returned from an LXColor method like hsb/rgb
 */
function renderPoint(point, deltaMs) {
  var level = brt;
  if (fade) {
    level *= clamp(1-LXUtils.dist(cx, cy, point.xn, point.yn), 0, 1);
  }
  return hsb(hue * 360, sat * 100, level * 100);  
}
