/**
 * PAC-MAN test pattern.
 *
 * A fixed 120 BPM chomp animation with dots moving in along the mouth vector.
 */

var BPM = 120;
var BEAT_MS = 60000 / BPM;

var PAC_X = 0.5;
var PAC_Y = 0.5;
var PAC_RADIUS = 0.18;

var DOT_SPACING = 0.18;
var DOT_SPEED = 0.22;
var DOT_RADIUS = 0.027;
var DOT_MAX_DISTANCE = 1.5;
var DOT_EAT_DISTANCE = PAC_RADIUS * 0.4;

knob("angle", "Angle", "PAC-MAN facing angle", 0.5);

function renderPoint(point, deltaMs) {
  var now = Date.now();
  var beat = (now % BEAT_MS) / BEAT_MS;
  var chomp = Math.abs(Math.sin(beat * Math.PI));
  var mouthAngle = lerp(0.08, 0.82, chomp);
  var facing = angle * Math.PI * 2;
  var faceX = Math.cos(facing);
  var faceY = Math.sin(facing);

  var x = point.xn;
  var y = point.yn;
  var dx = x - PAC_X;
  var dy = y - PAC_Y;
  var dist = Math.sqrt(dx * dx + dy * dy);
  var pointAngle = Math.atan2(dy, dx);
  var inMouth = Math.abs(angleDelta(pointAngle, facing)) < mouthAngle;
  var dotColor = isDot(x, y, now, faceX, faceY) ? hsb(45, 20, 100) : hsb(0, 0, 0);

  if (dist <= PAC_RADIUS) {
    if (inMouth) {
      return dotColor;
    }
    return hsb(54, 100, 100);
  }

  return dotColor;
}

function isDot(x, y, now, faceX, faceY) {
  var offset = (now * 0.001 * DOT_SPEED) % DOT_SPACING;
  var dotDistance = DOT_MAX_DISTANCE - offset;

  while (dotDistance > DOT_EAT_DISTANCE) {
    var dotX = PAC_X + faceX * dotDistance;
    var dotY = PAC_Y + faceY * dotDistance;
    var dx = x - dotX;
    var dy = y - dotY;
    if (Math.sqrt(dx * dx + dy * dy) <= DOT_RADIUS) {
      return true;
    }
    dotDistance -= DOT_SPACING;
  }

  return false;
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
