/**
 * Lava lamp metaball pattern, ported from a Shadertoy-style fragment shader.
 */

knob("speed", "Speed", "Blob movement speed", 0.35);
knob("count", "Count", "Number of blobs", 0.45);
knob("size", "Size", "Blob field threshold", 0.45);
knob("motion", "Motion", "Blob travel range", 0.5);
knob("contrast", "Contrast", "Blob edge-to-center luminance contrast", 0.65);
knob("bg", "Bg", "Background luminance", 0.18);
knob("aspect", "Aspect", "Vertical aspect correction", 0.5);
knob("level", "Level", "Overall brightness", 0.8);

var MAX_BLOBS = 16;

function renderPoint(point, deltaMs) {
  var time = Date.now() * 0.001;
  var numBlobs = Math.max(1, Math.round(lerp(2, MAX_BLOBS, count)));
  var threshold = lerp(8000, 800, size);
  var aspectCorrection = lerp(0.5, 1.5, aspect);
  var uvX = point.xn;
  var uvY = point.yn * aspectCorrection;
  var distSum = 0;

  for (var i = 0; i < numBlobs; i++) {
    var blob = getBlob(i, time * 5, speed, motion);
    var radius = blob[2];
    var centerX = blob[0];
    var centerY = blob[1] * aspectCorrection;
    var dx = centerX - uvX;
    var dy = centerY - uvY;
    var distToCenter = Math.max(Math.sqrt(dx * dx + dy * dy) + radius / 2, 0);
    var tmp = distToCenter * distToCenter;
    distSum += 1 / (tmp * tmp);
  }

  if (distSum > threshold) {
    var t = smoothstep(threshold, 0, distSum - threshold);
    return gray(level * lerp(100, 100 * (1 - contrast), t));
  }

  return gray(level * bg * 100);
}

function getBlob(i, time, speedControl, motionControl) {
  var spd = lerp(0.05, 0.55, speedControl);
  var moveRange = lerp(0.08, 0.75, motionControl);
  var centerX = 0.5 + 0.1 * rand(i);
  var centerY = 0.5 + 0.1 * rand(i + 42);

  centerX += moveRange * Math.sin((spd + rand(i) * 0.1) * time * rand(i + 2)) * rand(i + 56);
  centerY += moveRange * -Math.sin((spd + rand(+17) * 0.2) * time) * rand(i * 9);

  return [centerX, centerY, 0.1 * Math.abs(rand(i + 3))];
}

function rand(i) {
  return Math.sin(i * 1.64);
}

function smoothstep(edge0, edge1, x) {
  var t = clamp((x - edge0) / (edge1 - edge0), 0, 1);
  return t * t * (3 - 2 * t);
}
