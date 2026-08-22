/**
 * Spectral rain.
 *
 * The falling kinematics are VerticalRainColumns': drops are emitted into one
 * of 10 to 40 vertical bins at the top of the frame, fall under constant
 * acceleration so position is quadratic in time, and drag a trail that ramps
 * from 1 at the head to 0 at the tail. Nothing accumulates here — the ground is
 * a bare line and every drop leaves the frame.
 *
 * There are seven kinds of drop. One is a soft gray background rain, emitted at
 * a steady rate with no reference to audio. The other six are ROYGBV, and each
 * belongs to one frequency band. The bands tile the span between Min Hz and Max
 * Hz at equal ratios — that is, they are evenly spaced in log frequency — so
 * red is the lowest sixth and violet the highest. Width follows the same axis:
 * red drops are the fattest, violet the thinnest, and the gray background rain
 * is thinner still.
 *
 * A band emits nothing until its level clears Threshold. At exactly threshold it
 * rains at Min Rate; at full scale it rains at Max Rate, interpolating between.
 * Program material carries far less energy up high than down low, and averaging
 * a wide Hz span dilutes a narrow peak more than a wide one dilutes a narrow
 * band, so both effects push the blue and violet bands under the threshold.
 * Slope tilts the bands in decibels per octave about the geometric center of the
 * selected range to pay that back.
 *
 * A drop's head stops mattering at the ground line, but the drop is not dropped
 * from the render list there — its position keeps integrating below ground while
 * only the part above ground is drawn, so the trail visibly sinks in, and the
 * slot is freed once the tail has also passed the line. Each impact throws three
 * splash particles upward with randomized velocity, colored from randomized
 * half-valued RGB channels, living half a second under the same gravity as the
 * rain.
 *
 * Splash geometry is the one part of this pattern that is aspect corrected.
 * Drops are columns and belong in normalized X by definition, but a splash is a
 * small round thing, and on a model several times wider than it is tall a radius
 * measured half in X and half in Y comes out a flat sliver thinner than the LED
 * pitch. Splash sizes and velocities are therefore in units of model height, and
 * X is converted through aspectX wherever the two meet.
 *
 * Chromatik's JavaScript wrapper does not expose the audio engine, so
 * getAudioMeter() walks back to the owning ScriptPattern once at load. Levels
 * are read from GraphicMeter's FourierTransform rather than from the meter's own
 * octave bands, because only the transform can be asked for an arbitrary Hz
 * range. No analyzer parameters are changed globally; gain, range, band edges
 * and smoothing all belong to this pattern alone.
 */

var TYPES = 7;
var GRAY = 0;
var BANDS = 6;

var MIN_BINS = 10;
var MAX_BINS = 40;

var MAX_DROPS = 128;
var MAX_SPLASH = 96;

// ROYGBV at full saturation and value, plus the gray background rain in slot 0.
// Precomputed because renderPoint runs once per LED and must not do HSB math.
var typeR = [1, 1, 1.000, 1, 0, 0, 0.667];
var typeG = [1, 0, 0.500, 1, 1, 0, 0.000];
var typeB = [1, 0, 0.000, 0, 0, 1, 1.000];

// Relative drop width, fattest red to thinnest violet, with the background rain
// finer than any of them. Multiplied by the bin width and the Drop Width knob.
// The gray floor is well above zero on purpose: a drop narrower than the gap
// between two LED columns falls straight through the model without lighting it.
var typeWidth = [0.45, 1.0, 0.86, 0.72, 0.58, 0.44, 0.30];

// The background rain reads as a soft wash rather than as white drops.
var typeLevel = [0.45, 1, 1, 1, 1, 1, 1];

// Horizontal antialiasing width for a drop edge, in normalized X.
var DROP_EDGE = 0.0035;

var SPLASH_PER_IMPACT = 3;
var SPLASH_LIFE = 0.5;
// Splash trails are defined in time rather than distance, so a fast particle
// draws a longer streak than a slow one.
var SPLASH_TRAIL_SECONDS = 0.07;

knob("bins", "Bins", "Integer column count from 10 to 40", 1);
knob("dropWidth", "Drop Width", "Scales every drop width; red stays fattest, violet thinnest", 0.4);
knob("acceleration", "Acceleration", "Downward acceleration of each drop", 0.42);
knob("trail", "Trail", "Trail length behind a drop, as a fraction of frame height", 0.4);
knob("ground", "Ground", "Ground line height above the bottom of the frame, up to 0.45", 0.444);
knob("grayRate", "Gray Rate", "Base rate of the gray background rain, 0 to 25 drops per second", 0.3);

knob("minHz", "Min Hz", "Lower edge of the red band, from 20 Hz to 2 kHz", 0.150515);
knob("maxHz", "Max Hz", "Upper edge of the violet band, from 500 Hz to 20 kHz", 0.861515);
knob("gain", "Gain", "Input gain, from -24 dB to +48 dB", 0.333333);
knob("slope", "Slope", "Frequency tilt, from -24 to +24 dB per octave; raise to bring up blue and violet", 0.625);
knob("range", "Range", "Decibels below full scale that map to zero, from 12 to 96", 0.428571);
knob("smoothing", "Smoothing", "Fall time of a band level; rises are instant", 0.3);

knob("threshold", "Threshold", "Band level that starts the rain", 0.25);
knob("minRate", "Min Rate", "Per-band emission rate exactly at threshold", 0.15);
knob("maxRate", "Max Rate", "Per-band emission rate at full level", 0.5);

knob("splashSpeed", "Splash Speed", "Launch speed of the splash particles", 0.45);
knob("splashSize", "Splash Size", "Splash particle radius, as a fraction of frame height", 0.33);

trigger("cue", "Cue Rain", "Emit one gray drop in a random column", onCueRain);

var meter = null;
var fft = null;
var meterRetry = 0;
var meterErrorLogged = false;

var bandLevel = [];
var emissionAccumulator = [];

var dropActive = [];
var dropType = [];
var dropX = [];
var dropY = [];
var dropAge = [];
var dropSplashed = [];

var splashActive = [];
var splashX = [];
var splashY = [];
var splashTailX = [];
var splashTailY = [];
var splashVX = [];
var splashVY = [];
var splashAge = [];
var splashR = [];
var splashG = [];
var splashB = [];

var pendingCues = 0;

// Values resolved once per frame rather than once per LED.
var activeBins = MAX_BINS;
var halfWidth = [];
var trailLength = 0.21;
var rainAcceleration = 2.66;
var groundY = 0.06;
var splashScale = 1;
var splashRadius = 0.035;
var splashEdge = 0.014;
var aspectX = 1;

// Compacted indices of the live drops and splashes. renderPoint runs once per
// LED against tens of thousands of points, so it walks these rather than
// scanning both fixed pools and rejecting mostly dead slots.
var liveDrops = [];
var liveDropCount = 0;
var liveSplashes = [];
var liveSplashCount = 0;

function init() {
  for (var t = 0; t < TYPES; ++t) {
    emissionAccumulator[t] = 0;
    halfWidth[t] = 0.01;
  }
  for (var b = 0; b < BANDS; ++b) {
    bandLevel[b] = 0;
  }
  for (var d = 0; d < MAX_DROPS; ++d) {
    dropActive[d] = false;
    dropType[d] = GRAY;
    dropX[d] = 0.5;
    dropY[d] = 1;
    dropAge[d] = 0;
    dropSplashed[d] = false;
  }
  for (var s = 0; s < MAX_SPLASH; ++s) {
    splashActive[s] = false;
    splashX[s] = 0;
    splashY[s] = 0;
    splashTailX[s] = 0;
    splashTailY[s] = 0;
    splashVX[s] = 0;
    splashVY[s] = 0;
    splashAge[s] = 0;
    splashR[s] = 0;
    splashG[s] = 0;
    splashB[s] = 0;
  }
  bindAudioMeter();
}

/**
 * Recover the owning LX instance from the deliberately narrow script adapter,
 * and hold onto the meter's transform. This is resolved only once; neither
 * preRender nor renderPoint ever performs reflection.
 */
function bindAudioMeter() {
  try {
    var ownerField = _device.getClass().getDeclaredField("this$0");
    ownerField.setAccessible(true);
    var scriptEngine = ownerField.get(_device);

    var deviceField = scriptEngine.getClass().getDeclaredField("device");
    deviceField.setAccessible(true);
    var scriptPattern = deviceField.get(scriptEngine);

    meter = scriptPattern.getLX().engine.audio.meter;
    fft = meter.fft;
  } catch (error) {
    // Audio may be unavailable during script construction. preRender retries,
    // but report the problem once so a broken binding is not just gray rain.
    if (!meterErrorLogged) {
      System.err.println("SpectralRain: cannot access audio meter: " + error);
      meterErrorLogged = true;
    }
    meter = null;
    fft = null;
  }
}

function onCueRain() {
  // Spend triggers in preRender so all simulation state changes in one place.
  ++pendingCues;
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (dropActive.length == 0) {
    init();
  }

  // Construction normally finds the meter immediately. If audio is still
  // coming online, retry slowly rather than reflecting on every frame.
  if (!fft) {
    meterRetry -= isFinite(deltaMs) ? deltaMs : 0;
    if (meterRetry <= 0) {
      bindAudioMeter();
      meterRetry = 1000;
    }
  }

  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;

  activeBins = clampInt(
    Math.round(lerp(MIN_BINS, MAX_BINS, bins)),
    MIN_BINS,
    MAX_BINS
  );

  trailLength = lerp(0.05, 0.45, trail);
  rainAcceleration = lerp(0.25, 6, acceleration);
  // Splashes only exist at the ground line, so the line wants to sit where the
  // model actually has LEDs. The bottom edge of a hanging rig is usually its
  // sparsest region, which is why the default floats the ground well up.
  groundY = lerp(0, 0.45, ground);
  splashScale = lerp(0.3, 2, splashSpeed);

  // Splash sizes and speeds are in units of model height; see the header note.
  splashRadius = lerp(0.008, 0.09, splashSize);
  splashEdge = Math.max(0.004, splashRadius * 0.4);

  aspectX = 1;
  if (model && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  // One bin is the widest a drop ever gets, so a fat red drop just fills its
  // column and a thin violet one sits inside it.
  var binHalf = 0.5 / activeBins;
  var widthScale = lerp(0.5, 2, dropWidth);
  for (var t = 0; t < TYPES; ++t) {
    halfWidth[t] = binHalf * typeWidth[t] * widthScale;
  }

  updateBands(dt);
  emit(dt);
  advanceDrops(dt);
  advanceSplashes(dt);
  compactLiveLists();
}

function compactLiveLists() {
  liveDropCount = 0;
  for (var i = 0; i < MAX_DROPS; ++i) {
    if (dropActive[i]) {
      liveDrops[liveDropCount++] = i;
    }
  }
  liveSplashCount = 0;
  for (var s = 0; s < MAX_SPLASH; ++s) {
    if (splashActive[s]) {
      liveSplashes[liveSplashCount++] = s;
    }
  }
}

/**
 * Read six log-spaced frequency bands straight out of the transform. The
 * transform's own octave bands cannot be used: their edges are fixed by the
 * sample rate, and the whole point of Min Hz / Max Hz is to place the edges.
 */
function updateBands(dt) {
  var releaseSeconds = expMap(0.02, 1.5, smoothing);

  // The transform reports its Nyquist only indirectly. Its octave ratio is
  // log2(nyquist / 65.41) spread across numBands - 1, which inverts exactly,
  // and is zero until the audio engine has delivered a first buffer.
  var octaveRatio = fft ? fft.getBandOctaveRatio() : 0;
  var fftBands = fft ? fft.getNumBands() : 0;
  if (!fft || !(octaveRatio > 0) || fftBands < 2) {
    for (var i = 0; i < BANDS; ++i) {
      bandLevel[i] = approach(bandLevel[i], 0, dt, releaseSeconds);
    }
    return;
  }

  var nyquist = 65.41 * Math.pow(2, octaveRatio * (fftBands - 1));
  var size = fft.getSize();

  // getAverage indexes the amplitude table without bounds checks, so the top
  // edge must not be allowed past Nyquist.
  var low = clamp(expMap(20, 2000, minHz), 20, nyquist * 0.5);
  var high = clamp(expMap(500, 20000, maxHz), low * 1.2, nyquist);

  var gainDb = -24 + gain * 72;
  var slopeDb = (slope - 0.5) * 48;
  var rangeDb = 12 + range * 84;
  var ratio = Math.pow(high / low, 1 / BANDS);

  // The tilt pivots on the geometric center of the selected range, which is its
  // true midpoint in log frequency. Pivoting there means Slope redistributes the
  // bands against each other without also acting as a second gain control.
  var pivot = Math.sqrt(low * high);

  var f0 = low;
  for (var band = 0; band < BANDS; ++band) {
    var f1 = f0 * ratio;

    // Normalizing by the transform size matches what LX's own meter does, so
    // the Gain and Range knobs here read in the same decibels as the mixer's.
    var amplitude = fft.getAverage(f0, f1) / size;
    var decibels = amplitude > 1e-9 ? 20 * Math.log(amplitude) / Math.LN10 : -180;

    var octaves = Math.log(Math.sqrt(f0 * f1) / pivot) / Math.LN2;
    var target = clamp((decibels + gainDb + slopeDb * octaves + rangeDb) / rangeDb, 0, 1);

    // Rises are instant so a transient triggers rain on the frame it lands;
    // Smoothing controls only how long the band keeps raining afterward.
    if (target >= bandLevel[band]) {
      bandLevel[band] = target;
    } else {
      bandLevel[band] = approach(bandLevel[band], target, dt, releaseSeconds);
    }

    f0 = f1;
  }
}

function emit(dt) {
  while (pendingCues > 0) {
    --pendingCues;
    emitDrop(GRAY);
  }

  accumulate(GRAY, lerp(0, 25, grayRate), dt);

  var gate = clamp(threshold, 0, 1);
  var span = Math.max(1e-4, 1 - gate);
  var slowest = lerp(0, 10, minRate);
  var fastest = lerp(0, 40, maxRate);

  for (var band = 0; band < BANDS; ++band) {
    var level = bandLevel[band];
    if (level <= gate) {
      // Nothing pending from a band that has gone quiet; the next hit starts
      // from zero rather than firing a stale fraction of a drop.
      emissionAccumulator[band + 1] = 0;
      continue;
    }
    var over = clamp((level - gate) / span, 0, 1);
    accumulate(band + 1, lerp(slowest, fastest, over), dt);
  }
}

function accumulate(type, dropsPerSecond, dt) {
  emissionAccumulator[type] += dt * dropsPerSecond;
  // A long engine stall should not release an enormous catch-up cloud.
  if (emissionAccumulator[type] > 4) {
    emissionAccumulator[type] = 4;
  }
  while (emissionAccumulator[type] >= 1) {
    emissionAccumulator[type] -= 1;
    emitDrop(type);
  }
}

function emitDrop(type) {
  for (var i = 0; i < MAX_DROPS; ++i) {
    if (!dropActive[i]) {
      dropActive[i] = true;
      dropType[i] = type;
      // Bin centers, so drop spacing stays even. X is absolute from here on:
      // changing the bin count must not slide drops already in the air.
      dropX[i] = (Math.floor(Math.random() * activeBins) + 0.5) / activeBins;
      dropY[i] = 1;
      dropAge[i] = 0;
      dropSplashed[i] = false;
      return;
    }
  }
}

function advanceDrops(dt) {
  for (var i = 0; i < MAX_DROPS; ++i) {
    if (!dropActive[i]) {
      continue;
    }

    dropAge[i] += dt;
    var y = 1 - 0.5 * rainAcceleration * dropAge[i] * dropAge[i];
    dropY[i] = y;

    if (!dropSplashed[i] && y <= groundY) {
      dropSplashed[i] = true;
      emitSplash(dropX[i]);
    }

    // The head keeps integrating below the ground line while renderPoint draws
    // only what is above it, which is what makes the trail sink in. The slot is
    // reclaimed once the tail has crossed too.
    if (y + trailLength < groundY) {
      dropActive[i] = false;
    }
  }
}

function emitSplash(x) {
  for (var n = 0; n < SPLASH_PER_IMPACT; ++n) {
    var slot = -1;
    for (var i = 0; i < MAX_SPLASH; ++i) {
      if (!splashActive[i]) {
        slot = i;
        break;
      }
    }
    if (slot < 0) {
      return;
    }

    splashActive[slot] = true;
    splashX[slot] = x;
    splashY[slot] = groundY;
    splashTailX[slot] = x;
    splashTailY[slot] = groundY;
    // Both components are in model heights per second, so a splash arcs the
    // same way regardless of how wide the model happens to be.
    splashVX[slot] = (Math.random() * 2 - 1) * 0.5 * splashScale;
    splashVY[slot] = (0.5 + Math.random() * 0.5) * splashScale;
    splashAge[slot] = 0;

    // Randomized half-valued RGB: each channel independently on at half scale
    // or off, rerolled off black. Eight combinations minus one, so a splash is
    // some dim primary or secondary rather than a wash of the drop's own hue.
    var r, g, b;
    do {
      r = Math.random() < 0.5 ? 0 : 0.5;
      g = Math.random() < 0.5 ? 0 : 0.5;
      b = Math.random() < 0.5 ? 0 : 0.5;
    } while (r + g + b == 0);
    splashR[slot] = r;
    splashG[slot] = g;
    splashB[slot] = b;
  }
}

function advanceSplashes(dt) {
  for (var i = 0; i < MAX_SPLASH; ++i) {
    if (!splashActive[i]) {
      continue;
    }

    splashAge[i] += dt;
    if (splashAge[i] >= SPLASH_LIFE) {
      splashActive[i] = false;
      continue;
    }

    // Same gravity as the rain, so a splash arcs on the curve the drops fall on.
    // X is stored normalized for rendering, so the horizontal velocity converts
    // out of model heights on the way in.
    splashVY[i] -= rainAcceleration * dt;
    splashX[i] += splashVX[i] * dt / aspectX;
    splashY[i] += splashVY[i] * dt;

    if (splashY[i] < groundY && splashVY[i] < 0) {
      splashActive[i] = false;
      continue;
    }

    splashTailX[i] = splashX[i] - splashVX[i] * SPLASH_TRAIL_SECONDS / aspectX;
    splashTailY[i] = splashY[i] - splashVY[i] * SPLASH_TRAIL_SECONDS;
  }
}

function renderPoint(point, deltaMs) {
  var x = point.xn;
  var y = clamp(point.yn, 0, 1);

  // Below the ground line nothing exists — no accumulation, and this is also
  // what clips the sinking trails and the splash tails.
  if (y < groundY) {
    return rgba(0, 0, 0, 0);
  }

  var r = 0;
  var g = 0;
  var b = 0;

  // Drops of different hues overlap. Compositing per channel by maximum keeps
  // every contribution inside 0..1 instead of letting overlaps blow past full.
  for (var n = 0; n < liveDropCount; ++n) {
    var i = liveDrops[n];
    var behind = y - dropY[i];
    if (behind < 0 || behind > trailLength) {
      continue;
    }

    var type = dropType[i];
    var half = halfWidth[type];
    var dx = Math.abs(x - dropX[i]);
    if (dx > half + DROP_EDGE) {
      continue;
    }

    var coverage = clamp((half - dx) / DROP_EDGE + 0.5, 0, 1);
    var value = coverage * (1 - behind / trailLength) * typeLevel[type];
    if (value <= 0) {
      continue;
    }

    var cr = typeR[type] * value;
    var cg = typeG[type] * value;
    var cb = typeB[type] * value;
    if (cr > r) { r = cr; }
    if (cg > g) { g = cg; }
    if (cb > b) { b = cb; }
  }

  var reachY = splashRadius + splashEdge;
  var reachX = reachY / aspectX;

  for (var m = 0; m < liveSplashCount; ++m) {
    var s = liveSplashes[m];
    var ax = splashX[s];
    var ay = splashY[s];
    var bx = splashTailX[s];
    var by = splashTailY[s];

    // A cheap segment box rejects nearly every splash before any projection.
    if (x < Math.min(ax, bx) - reachX || x > Math.max(ax, bx) + reachX ||
        y < Math.min(ay, by) - reachY || y > Math.max(ay, by) + reachY) {
      continue;
    }

    // Project in model-height units, where the splash is actually round. Both
    // the point and the streak are taken relative to the live head, which is
    // also the origin the taper measures from.
    var px = (x - ax) * aspectX;
    var py = y - ay;
    var sx = (bx - ax) * aspectX;
    var sy = by - ay;
    var length2 = sx * sx + sy * sy;
    var u = 0;
    if (length2 > 1e-12) {
      u = clamp((px * sx + py * sy) / length2, 0, 1);
    }

    var ex = px - sx * u;
    var ey = py - sy * u;
    var distance = Math.sqrt(ex * ex + ey * ey);

    // u is zero at the live particle and one at the end of its streak, so the
    // stroke narrows and fades along the same coordinate.
    var radius = splashRadius * (1 - 0.6 * u);
    var cover = clamp((radius - distance) / splashEdge + 0.5, 0, 1);
    var fade = cover * (1 - u) * (1 - splashAge[s] / SPLASH_LIFE);
    if (fade <= 0) {
      continue;
    }

    var pr = splashR[s] * fade;
    var pg = splashG[s] * fade;
    var pb = splashB[s] * fade;
    if (pr > r) { r = pr; }
    if (pg > g) { g = pg; }
    if (pb > b) { b = pb; }
  }

  // Alpha tracks the brightest channel, so unlit frame stays transparent and
  // this layers over other patterns rather than painting black over them.
  var a = Math.max(r, Math.max(g, b));
  return rgba(byteOf(r), byteOf(g), byteOf(b), byteOf(a));
}

function byteOf(value) {
  return clampInt(Math.round(value * 255), 0, 255);
}

function clampInt(value, low, high) {
  return Math.max(low, Math.min(high, value | 0));
}

function approach(value, target, dt, seconds) {
  if (dt <= 0) {
    return value;
  }
  var amount = 1 - Math.exp(-dt / Math.max(0.0001, seconds));
  return value + (target - value) * amount;
}

function expMap(minimum, maximum, amount) {
  return minimum * Math.pow(maximum / minimum, clamp(amount, 0, 1));
}
