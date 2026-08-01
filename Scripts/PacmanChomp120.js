/**
 * PAC-MAN test pattern.
 *
 * One scene: PAC-MAN at the origin with a line of DOT_COUNT food dots feeding
 * into his mouth. Over t = 0 to 1 the whole line advances DOT_COUNT slots, so
 * every dot is eaten by t = 1 and the scene starts over with a full line.
 * One dot per chomp, one chomp per beat.
 */

var BPM = 120;
var BEAT_MS = 60000 / BPM;

var DOT_COUNT = 4;
var CYCLE_MS = BEAT_MS * DOT_COUNT;

var PAC_X = 0.5;
var PAC_Y = 0.5;

// Sizes at scale 1. The far dot sits at DOT_COUNT * DOT_SPACING = 0.44, so the
// whole line fits the frame, and PAC-MAN is about two slots across so the dots
// clear his mouth.
var PAC_RADIUS = 0.2;
var DOT_SPACING = 0.3;
var DOT_RADIUS = 0.04;

var TRANSPARENT = null;

knob("angle", "Angle", "PAC-MAN facing angle", 0.5);

function renderPoint(point, deltaMs) {
  var now = Date.now();
  var t = (now % CYCLE_MS) / CYCLE_MS;
  var color1 = renderScene(point.xn - PAC_X, point.yn - PAC_Y, 1, angle * Math.PI * 2, t);
  var color2 = renderScene(point.xn - PAC_X + (1-t) * DOT_SPACING * DOT_COUNT, point.yn - PAC_Y, 0.3, angle * Math.PI * 2, t);

  if (color2 != TRANSPARENT) {
    return color2;
  }
  if (color1 != TRANSPARENT) {
    return color1;
  }

  return hsb(0, 0, 0);
}

/**
 * Render one PAC-MAN scene, with PAC-MAN at the origin facing `angle`.
 *
 * @param {number} x - Point to shade, relative to PAC-MAN
 * @param {number} y - Point to shade, relative to PAC-MAN
 * @param {number} scale - Size of the scene; 1 is full frame
 * @param {number} angle - Facing direction in radians
 * @param {number} t - Progress through the scene from 0 to 1
 * @return {number} Color, or TRANSPARENT where the scene is empty
 */
function renderScene(x, y, scale, angle, t) {
  var faceX = Math.cos(angle);
  var faceY = Math.sin(angle);
  var radius = PAC_RADIUS * scale;
  var spacing = DOT_SPACING * scale;
  var dotRadius = DOT_RADIUS * scale;

  // Slots travelled so far. Dots are eaten on whole slots, and the jaws shut on
  // whole slots too, so PAC-MAN bites down exactly as a dot reaches him.
  var travel = t * DOT_COUNT;
  var mouthAngle = lerp(0.08, 0.82, Math.abs(Math.sin(travel * Math.PI)));

  if (x * x + y * y <= radius * radius &&
      Math.abs(angleDelta(Math.atan2(y, x), angle)) >= mouthAngle) {
    return hsb(54, 100, 100);
  }

  // Dot k starts in slot k and rides in to the mouth. Spacing is wider than a
  // dot, so only the nearest slot can cover this point.
  var slot = Math.round((x * faceX + y * faceY) / spacing + travel);
  if (slot < 1 || slot > DOT_COUNT - 1) {
    return TRANSPARENT;
  }
  var center = (slot - travel) * spacing;
  if (center <= 0) {
    return TRANSPARENT;
  }
  var ox = x - faceX * center;
  var oy = y - faceY * center;
  return ox * ox + oy * oy <= dotRadius * dotRadius ? hsb(45, 20, 100) : TRANSPARENT;
}

function angleDelta(a, b) {
  var d = a - b;
  while (d > Math.PI) {
    d -= Math.PI * 2;
  }
  while (d < -Math.PI) {
    d += Math.PI * 2;
  }
  return d;
}
