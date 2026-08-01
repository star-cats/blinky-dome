/**
 * Simple test pattern: render every point as one solid hue.
 */

knob("hue", "Hue", "Solid color hue", 0);

/**
 * Return a color value for the given LXPoint.
 * @param {LXPoint} point - The point to render
 * @param {number} deltaMs - Milliseconds elapsed since previous frame
 * @return {number} Color value returned from an LXColor method like hsb/rgb
 */
function renderPoint(point, deltaMs) {
  return hsb(hue * 360, 100, 100);
}
