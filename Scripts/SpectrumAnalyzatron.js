/**
 * Spectrum Analyzatron
 *
 * Draws 10-40 frequency bars repeatedly across the horizontal axis. Every bar
 * grows symmetrically up and down from y=0, fading smoothly from 0.2 at the
 * centerline to 1 at its two tips. The spectrum tiles forever along its local
 * X axis, may be set to a fixed angle, and cycles its visible output values by
 * an internal phase.
 *
 * Chromatik's JavaScript wrapper does not expose the audio engine directly, so
 * getAudioMeter() walks back to the owning ScriptPattern once at load and then
 * reads GraphicMeter.getRaw(). No analyzer parameters are changed globally;
 * all gain, slope, frequency selection, and envelope shaping belong to this
 * pattern alone.
 */

var MAX_BARS = 40;
var TAU = Math.PI * 2;

knob("bars", "Bars", "Number of spectrum bars, from 10 to 40", 1.0);
knob("gain", "Gain", "Input gain, from -24 dB to +48 dB", 0.14839843730442226);
knob("slope", "Slope", "Frequency tilt, from -24 to +24 dB per octave", 0.562421876078588);
knob("minFreq", "Min Freq", "Lowest analyzer band included", 0);
knob("maxFreq", "Max Freq", "Highest analyzer band included", 1);
knob("minDb", "Min", "Input floor, from -96 dB to -24 dB", 0.5726953108824091);
knob("maxDb", "Max", "Input ceiling, from -48 dB to 0 dB", 1.0);

knob("attack", "Attack", "Time for a bar to reach a new peak", 0.1807031260151416);
knob("decay", "Decay", "Time for the peak to settle to its sustain level", 0.10179687525233022);
knob("sustain", "Sustain", "Fraction held while that frequency remains present", 0.08085937765892592);
knob("release", "Release", "Time for a silent bar to return to zero", 0.3007812491935329);

knob("zoom", "Zoom", "Spectrum tile scale; center is 1x, ends are 1/4x and 4x", 0.5779687613467104);
knob("angle", "Angle", "Direct spectrum angle; the knob spans one full turn", 0);
knob("panSpeed", "Pan Speed", "Horizontal pan speed; center is still, left/right reverse", 0.5387890615529614);
knob("phaseSpeed", "Phase Speed", "Output phase speed, from stopped to one-half cycle per second", 0.5387890615529614);

var SIGNAL_GATE = 0.004;

var meter = null;
var meterRetry = 0;
var meterErrorLogged = false;
var envelope = [];
var envelopePeak = [];
var envelopePhase = [];
var barHeight = [];

var activeBars = 15;
var panPhase = 0;
var valuePhase = 0;
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
  activeBars = Math.max(10, Math.min(40, Math.round(10 + bars * 30)));

  // Four octaves of camera scale centered at 1x.
  sceneScale = Math.pow(2, (zoom - 0.5) * 4);

  // Angle is an absolute UI value. Only the output phase accumulates over time.
  var radians = angle * TAU;
  sceneCos = Math.cos(radians);
  sceneSin = Math.sin(radians);

  // Independent positional phase: +/- one whole spectrum tile per second.
  panPhase += (panSpeed - 0.5) * 2 * dt;
  panPhase -= Math.floor(panPhase);

  valuePhase += phaseSpeed * 0.5 * dt;
  valuePhase -= Math.floor(valuePhase);

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
    barHeight[bar] = envelope[bar] * 0.48;
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
  // Inverse-rotate into the spectrum's local coordinates. One tile occupies
  // sceneScale corrected units and fractional wrapping repeats it forever.
  var dx = (point.xn - 0.5) * aspectX;
  var dy = point.yn - 0.5;
  var x = (sceneCos * dx + sceneSin * dy) / sceneScale;
  var y = (-sceneSin * dx + sceneCos * dy) / sceneScale;

  var tileX = x - panPhase + 0.5;
  tileX -= Math.floor(tileX);

  // Sample at bar centers and interpolate neighboring heights. Hard black
  // separator gaps alias against discrete LED columns while panning: a gap can
  // momentarily land on every point in a physical column. This continuous
  // reconstruction has no such zero-width traps and wraps across the seam.
  var barPosition = tileX * activeBars - 0.5;
  var leftRaw = Math.floor(barPosition);
  var blend = barPosition - leftRaw;
  blend = blend * blend * (3 - 2 * blend);
  var left = leftRaw % activeBars;
  if (left < 0) {
    left += activeBars;
  }
  var right = (left + 1) % activeBars;

  // Spectrum value is always local Y and is mirrored about its centerline.
  var height = barHeight[left] + (barHeight[right] - barHeight[left]) * blend;
  var distance = Math.abs(y);
  if (height <= 0 || distance > height + 1e-9) {
    return hsb(0, 0, 0);
  }

  var value = 0.2 + 0.8 * Math.min(1, distance / height);
  value = value + valuePhase;
  value -= Math.floor(value);
  return hsb(0, 0, value * 100);
}
