/**
 * Spectrum Analyzatron
 *
 * Draws 10-20 horizontal frequency bars stacked symmetrically up and down the
 * local x=0 centerline. Each bar grows equally left and right from that line:
 * its body is exactly 0.75, its two outer caps are exactly 1, and every point
 * outside the visualizer is 0.
 *
 * Chromatik's JavaScript wrapper does not expose the audio engine directly, so
 * getAudioMeter() walks back to the owning ScriptPattern once at load and then
 * reads GraphicMeter.getRaw(). No analyzer parameters are changed globally;
 * all gain, slope, frequency selection, and envelope shaping belong to this
 * pattern alone.
 */

var TAU = Math.PI * 2;
var MAX_BARS = 20;

knob("bars", "Bars", "Number of spectrum bars, from 10 to 20", 0.5);
knob("gain", "Gain", "Input gain, from -24 dB to +48 dB; default is 0 dB", 1 / 3);
knob("slope", "Slope", "Frequency tilt, from -24 to +24 dB per octave", 0.5);
knob("minFreq", "Min Freq", "Lowest analyzer band included", 0);
knob("maxFreq", "Max Freq", "Highest analyzer band included", 1);
knob("minDb", "Min", "Input floor, from -96 dB to -24 dB", 0.44);
knob("maxDb", "Max", "Input ceiling, from -48 dB to 0 dB", 0.75);

knob("attack", "Attack", "Time for a bar to reach a new peak", 0.12);
knob("decay", "Decay", "Time for the peak to settle to its sustain level", 0.2);
knob("sustain", "Sustain", "Fraction held while that frequency remains present", 0.72);
knob("release", "Release", "Time for a silent bar to return to zero", 0.28);

knob("zoom", "Zoom", "Visualizer scale; center is 1x, ends are 1/4x and 4x", 0.5);
knob("rotate", "Rotate", "Angular speed; center is still, ends reverse direction", 0.5);

// Small gaps make adjacent bars distinct. Cap is the bright row at each end.
var BAR_FILL = 0.78;
var CAP_THICKNESS = 0.012;
var SIGNAL_GATE = 0.004;

var meter = null;
var meterRetry = 0;
var meterErrorLogged = false;
var envelope = [];
var envelopePeak = [];
var envelopePhase = [];
var barHeight = [];

var activeBars = 15;
var angle = 0;
var sceneScale = 1;
var sceneCos = 1;
var sceneSin = 0;
var aspectX = 1;

function init() {
  for (var i = 0; i < MAX_BARS; ++i) {
    envelope[i] = 0;
    envelopePeak[i] = 0;
    envelopePhase[i] = 0;
    barHeight[i] = 0;
  }
  meter = getAudioMeter();
}

/**
 * Recover the owning LX instance from the deliberately narrow script adapter.
 * This is resolved only once; renderPoint never performs reflection.
 */
function getAudioMeter() {
  try {
    var ownerField = _device.getClass().getDeclaredField("this$0");
    ownerField.setAccessible(true);
    var scriptEngine = ownerField.get(_device);

    var deviceField = scriptEngine.getClass().getDeclaredField("device");
    deviceField.setAccessible(true);
    var scriptPattern = deviceField.get(scriptEngine);

    return scriptPattern.getLX().engine.audio.meter;
  } catch (error) {
    // Audio may be unavailable during script construction. preRender retries,
    // but report the problem once so a broken binding is not just a black frame.
    if (!meterErrorLogged) {
      System.err.println("SpectrumAnalyzatron: cannot access audio meter: " + error);
      meterErrorLogged = true;
    }
    return null;
  }
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (envelope.length == 0) {
    init();
  }

  // Construction normally finds the meter immediately. If audio is still
  // coming online, retry slowly rather than reflecting on every frame.
  if (!meter) {
    meterRetry -= isFinite(deltaMs) ? deltaMs : 0;
    if (meterRetry <= 0) {
      meter = getAudioMeter();
      meterRetry = 1000;
    }
  }

  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.1) : 0;
  activeBars = Math.max(10, Math.min(20, Math.round(10 + bars * 10)));

  // Four octaves of camera scale centered at 1x.
  sceneScale = Math.pow(2, (zoom - 0.5) * 4);

  // The knob is velocity, not position: +/- one revolution per second.
  var turnsPerSecond = (rotate - 0.5) * 2;
  angle += turnsPerSecond * TAU * dt;
  angle -= Math.floor(angle / TAU) * TAU;
  sceneCos = Math.cos(angle);
  sceneSin = Math.sin(angle);

  if (model && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  } else {
    aspectX = 1;
  }

  updateSpectrum(dt);
}

function updateSpectrum(dt) {
  var sourceBands = meter ? meter.getNumBands() : 0;
  var low = clamp(minFreq, 0, 0.98);
  var high = clamp(maxFreq, 0.02, 1);
  if (high < low + 0.02) {
    high = Math.min(1, low + 0.02);
    low = Math.max(0, high - 0.02);
  }

  var gainDb = -24 + gain * 72;
  var slopeDb = (slope - 0.5) * 48;
  var floorDb = -96 + minDb * 72;
  var ceilingDb = -48 + maxDb * 48;
  if (ceilingDb < floorDb + 6) {
    ceilingDb = floorDb + 6;
  }

  var attackSec = expMap(0.006, 0.6, attack);
  var decaySec = expMap(0.015, 1.8, decay);
  var releaseSec = expMap(0.02, 3.5, release);

  for (var bar = 0; bar < MAX_BARS; ++bar) {
    var target = 0;
    if (bar < activeBars && sourceBands > 0) {
      var f0 = low + (high - low) * bar / activeBars;
      var f1 = low + (high - low) * (bar + 1) / activeBars;
      var inputDb = readBandRange(f0 * sourceBands, f1 * sourceBands, sourceBands);

      // Pivot the octave slope at the middle of the selected frequency range.
      var center = Math.max(0.5, (f0 + f1) * sourceBands * 0.5);
      var pivot = Math.max(0.5, (low + high) * sourceBands * 0.5);
      var octaves = Math.log(center / pivot) / Math.LN2;
      inputDb += gainDb + slopeDb * octaves;
      target = clamp((inputDb - floorDb) / (ceilingDb - floorDb), 0, 1);
    }

    advanceEnvelope(bar, target, dt, attackSec, decaySec, releaseSec);
    // Leave room for both horizontal halves and their bright outer caps.
    barHeight[bar] = envelope[bar] * (0.48 - CAP_THICKNESS);
  }
}

/** Average the raw decibel values of every analyzer band touched by [start, end). */
function readBandRange(start, end, sourceBands) {
  var first = Math.max(0, Math.min(sourceBands - 1, Math.floor(start)));
  var last = Math.max(first, Math.min(sourceBands - 1, Math.ceil(end) - 1));
  var total = 0;
  var count = 0;
  for (var i = first; i <= last; ++i) {
    var rawDb = meter.getDecibelsf(i);
    if (isFinite(rawDb)) {
      total += rawDb;
    } else {
      total += -120;
    }
    ++count;
  }
  return count > 0 ? total / count : 0;
}

/**
 * Per-frequency ADSR. A newly rising bin attacks to its peak, decays to the
 * sustain fraction while audio remains present, and releases only after the
 * raw bin falls below the gate.
 */
function advanceEnvelope(index, target, dt, attackSec, decaySec, releaseSec) {
  var phase = envelopePhase[index]; // 0 release, 1 attack, 2 decay/sustain
  var value = envelope[index];

  if (target > SIGNAL_GATE) {
    if (phase == 0 || target > envelopePeak[index]) {
      phase = 1;
      envelopePeak[index] = target;
    }

    if (phase == 1) {
      value = approach(value, envelopePeak[index], dt, attackSec);
      if (value >= envelopePeak[index] * 0.995) {
        phase = 2;
      }
    } else {
      // The live input can lift sustain immediately; decay controls only falls.
      var held = Math.max(target, envelopePeak[index] * sustain);
      if (held >= value) {
        value = held;
      } else {
        value = approach(value, held, dt, decaySec);
      }
    }
  } else {
    phase = 0;
    value = approach(value, 0, dt, releaseSec);
    if (value < 0.0001) {
      value = 0;
      envelopePeak[index] = 0;
    }
  }

  envelope[index] = clamp(value, 0, 1);
  envelopePhase[index] = phase;
}

function approach(value, target, dt, seconds) {
  if (dt <= 0) {
    return value;
  }
  var amount = 1 - Math.exp(-dt / Math.max(0.0001, seconds));
  return value + (target - value) * amount;
}

function expMap(minimum, maximum, amount) {
  return minimum * Math.pow(maximum / minimum, amount);
}

function renderPoint(point, deltaMs) {
  // Center screen space, correct its aspect, then inverse-transform the scene.
  var dx = (point.xn - 0.5) * aspectX;
  var dy = point.yn - 0.5;
  var x = (sceneCos * dx + sceneSin * dy) / sceneScale;
  var y = (-sceneSin * dx + sceneCos * dy) / sceneScale;

  // Horizontal bars are stacked evenly and symmetrically along local x=0.
  if (y < -0.5 || y >= 0.5) {
    return hsb(0, 0, 0);
  }

  var slot = (y + 0.5) * activeBars;
  var bar = Math.floor(slot);
  var within = slot - bar;
  if (within < (1 - BAR_FILL) * 0.5 || within > (1 + BAR_FILL) * 0.5) {
    return hsb(0, 0, 0);
  }

  var halfLength = barHeight[bar];
  var distance = Math.abs(x);
  if (halfLength <= 0 || distance > halfLength + CAP_THICKNESS) {
    return hsb(0, 0, 0);
  }

  var value = distance >= halfLength - CAP_THICKNESS ? 1 : 0.75;
  return hsb(0, 0, value * 100);
}
