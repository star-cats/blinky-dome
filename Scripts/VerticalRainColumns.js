/**
 * Vertical rain columns.
 *
 * The frame is divided into 10 to 40 vertical bins. Rain is emitted into a
 * random bin at a steady rate and falls from the top under constant
 * acceleration, so its position is quadratic in time. Each drop leaves a
 * white trail that ramps from 1 at the drop to 0 at the tail.
 *
 * A drop adds water when it reaches the surface in its own column. Water is
 * drawn at 0.4, with a bright 0.85 top edge. Once a column reaches the top it
 * enters a linear drain cycle; impacts during that cycle do not interrupt it,
 * and the column starts accepting water again when it is empty.
 */

var MIN_BINS = 10;
var MAX_BINS = 40;
var MAX_DROPS = 128;

// Length of the fade behind a falling drop, as a fraction of frame height.
var TRAIL_LENGTH = 0.2;

// Thickness of the bright surface on a water column.
var WATER_EDGE = 0.018;

knob("bins", "Bins", "Integer column count from 10 to 40", 1);
knob("rainRate", "Rain Rate", "Automatic rain emission rate", 0.35);
knob("fill", "Fill / Drop", "How much water one landed drop adds", 0.3);
knob("acceleration", "Acceleration", "Downward acceleration of each drop", 0.42);
knob("drain", "Drain Time", "Full-column drain time; default is one second", 0.2);

trigger("cue", "Cue Rain", "Emit one drop in a random column", onCueRain);

var water = [];
var draining = [];

var dropActive = [];
var dropColumn = [];
var dropAge = [];
var dropY = [];

var activeBins = MAX_BINS;
var emissionAccumulator = 0;
var pendingCues = 0;

// Values resolved once per frame rather than once per LED.
var dropsPerSecond = 2;
var fillPerDrop = 0.067;
var rainAcceleration = 2;
var drainSeconds = 1;

function init() {
  for (var i = 0; i < MAX_BINS; ++i) {
    water[i] = 0;
    draining[i] = false;
  }
  for (var d = 0; d < MAX_DROPS; ++d) {
    dropActive[d] = false;
    dropColumn[d] = 0;
    dropAge[d] = 0;
    dropY[d] = 1;
  }
}

function onCueRain() {
  // Spend triggers in preRender so all simulation state changes in one place.
  ++pendingCues;
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (water.length == 0) {
    init();
  }

  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;

  activeBins = clampInt(
    Math.round(lerp(MIN_BINS, MAX_BINS, bins)),
    MIN_BINS,
    MAX_BINS
  );

  // These mappings leave useful room at both ends. Drain Time is linear and
  // its default value (0.2) maps exactly to one second.
  dropsPerSecond = lerp(0.2, 30, rainRate);
  fillPerDrop = lerp(0.01, 0.2, fill);
  rainAcceleration = lerp(0.25, 6, acceleration);
  drainSeconds = lerp(0.1, 4.6, drain);

  while (pendingCues > 0) {
    --pendingCues;
    emitDrop();
  }

  emissionAccumulator += dt * dropsPerSecond;
  // A long engine stall should not release an enormous catch-up cloud.
  if (emissionAccumulator > 4) {
    emissionAccumulator = 4;
  }
  while (emissionAccumulator >= 1) {
    emissionAccumulator -= 1;
    emitDrop();
  }

  advanceWater(dt);
  advanceDrops(dt);
}

function emitDrop() {
  for (var i = 0; i < MAX_DROPS; ++i) {
    if (!dropActive[i]) {
      dropActive[i] = true;
      dropColumn[i] = Math.floor(Math.random() * activeBins);
      dropAge[i] = 0;
      dropY[i] = 1;
      return;
    }
  }
}

function advanceWater(dt) {
  var drainStep = dt / drainSeconds;
  for (var i = 0; i < MAX_BINS; ++i) {
    if (i >= activeBins) {
      // Inactive bins retain no hidden state if the count is turned down.
      water[i] = 0;
      draining[i] = false;
    } else if (draining[i]) {
      water[i] -= drainStep;
      if (water[i] <= 0) {
        water[i] = 0;
        draining[i] = false;
      }
    }
  }
}

function advanceDrops(dt) {
  for (var i = 0; i < MAX_DROPS; ++i) {
    if (!dropActive[i]) {
      continue;
    }

    var column = dropColumn[i];
    if (column >= activeBins) {
      dropActive[i] = false;
      continue;
    }

    dropAge[i] += dt;
    var y = 1 - 0.5 * rainAcceleration * dropAge[i] * dropAge[i];
    dropY[i] = y;

    if (y <= water[column]) {
      dropActive[i] = false;
      if (!draining[column]) {
        water[column] += fillPerDrop;
        if (water[column] >= 1) {
          water[column] = 1;
          draining[column] = true;
        }
      }
    }
  }
}

function renderPoint(point, deltaMs) {
  var column = clampInt(Math.floor(point.xn * activeBins), 0, activeBins - 1);
  var y = clamp(point.yn, 0, 1);
  var value = 0;
  var surface = water[column];

  if (surface > 0 && y <= surface) {
    value = y >= Math.max(0, surface - WATER_EDGE) ? 0.85 : 0.4;
  }

  // Multiple drops may share a column. Max-compositing preserves the defined
  // 0..1 trail instead of making overlaps exceed full brightness.
  for (var i = 0; i < MAX_DROPS; ++i) {
    if (!dropActive[i] || dropColumn[i] != column) {
      continue;
    }
    var behind = y - dropY[i];
    if (behind >= 0 && behind <= TRAIL_LENGTH) {
      var trail = 1 - behind / TRAIL_LENGTH;
      if (trail > value) {
        value = trail;
      }
    }
  }

  return hsb(0, 0, value * 100);
}

function clampInt(value, low, high) {
  return Math.max(low, Math.min(high, value | 0));
}
