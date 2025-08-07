/**
 * Define knobs and toggles with a variable name, label, description and default value
 * The variable name will be populated for the render method with a value from 0-1 for
 * knobs and true/false for toggles.
 */

knob("cx", "X-Center", "Center X position to fade from", 0.5);
knob("cy", "Y-Center", "Center Y position to fade from", 0.5);
knob("fade", "Fade", "Strength of masking", 0.5);

toggle("invert", "Invert", "Invert the fade amount", false);

/**
 * Return a color value for the given LXPoint
 * @param {LXPoint} point - The point to render
 * @param {number} deltaMs - Milliseconds elapsed since previous frame
 * @param {number} enabledAmount - Depth of effect to apply from 0-1
 * @param {number} inputColor - Input color value for this point
 * @return {number} Color value returned from an LXColor method like hsb/rgb
 */
function renderPoint(point, deltaMs, enabledAmount, inputColor) {
  var level = lerp(1, clamp(1 - 2 * fade * LXUtils.dist(point.xn, point.yn, cx, cy), 0, 1), enabledAmount);  
  return LXColor.multiply(inputColor, gray(100 * (invert ? (1-level) : level)));
}
