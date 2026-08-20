package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.audio.GraphicMeter;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Spectrum Analyzatron, ported from Scripts/SpectrumAnalyzatron.js.
 *
 * Draws 10-40 frequency bars repeatedly across the horizontal axis. Every bar
 * grows symmetrically up and down from y=0, fading smoothly from 0.2 at the
 * centerline to 1 at its two tips. The spectrum tiles forever along its local X
 * axis, may be set to a fixed angle, and cycles its visible output values by an
 * internal phase.
 *
 * No analyzer parameters are changed globally; all gain, slope, frequency
 * selection, and envelope shaping belong to this pattern alone, so two copies
 * on two channels can be tuned independently off the one shared meter.
 *
 * The one place this is simpler than the script is getting at that meter. A
 * script has no handle on Chromatik, so the original reflects its way back from
 * the script adapter to the owning pattern to the LX instance, retrying on a
 * timer because audio may not be up yet. A pattern is handed the LX instance in
 * its constructor: lx.engine.audio.meter, no reflection and nothing to retry.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Spectrum Analyzatron")
@LXComponent.Description("Tiling mirrored spectrum bars with per-band ADSR")
public class SpectrumAnalyzatronPattern extends LXPattern {

  private static final int MAX_BARS = 40;
  private static final double TAU = Math.PI * 2;

  private static final double SIGNAL_GATE = .004;

  public final CompoundParameter bars =
    new CompoundParameter("Bars", 1, 0, 1)
    .setDescription("Number of spectrum bars, from 10 to 40");

  public final CompoundParameter gain =
    new CompoundParameter("Gain", 0.14839843730442226, 0, 1)
    .setDescription("Input gain, from -24 dB to +48 dB");

  public final CompoundParameter slope =
    new CompoundParameter("Slope", 0.562421876078588, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Frequency tilt, from -24 to +24 dB per octave");

  public final CompoundParameter minFreq =
    new CompoundParameter("Min Freq", 0, 0, 1)
    .setDescription("Lowest analyzer band included");

  public final CompoundParameter maxFreq =
    new CompoundParameter("Max Freq", 1, 0, 1)
    .setDescription("Highest analyzer band included");

  public final CompoundParameter minDb =
    new CompoundParameter("Min", 0.5726953108824091, 0, 1)
    .setDescription("Input floor, from -96 dB to -24 dB");

  public final CompoundParameter maxDb =
    new CompoundParameter("Max", 1, 0, 1)
    .setDescription("Input ceiling, from -48 dB to 0 dB");

  public final CompoundParameter attack =
    new CompoundParameter("Attack", 0.1807031260151416, 0, 1)
    .setDescription("Time for a bar to reach a new peak");

  public final CompoundParameter decay =
    new CompoundParameter("Decay", 0.10179687525233022, 0, 1)
    .setDescription("Time for the peak to settle to its sustain level");

  public final CompoundParameter sustain =
    new CompoundParameter("Sustain", 0.08085937765892592, 0, 1)
    .setDescription("Fraction held while that frequency remains present");

  public final CompoundParameter release =
    new CompoundParameter("Release", 0.3007812491935329, 0, 1)
    .setDescription("Time for a silent bar to return to zero");

  public final CompoundParameter zoom =
    new CompoundParameter("Zoom", 0.5779687613467104, 0, 1)
    .setDescription("Spectrum tile scale; center is 1x, ends are 1/4x and 4x");

  public final CompoundParameter angle =
    new CompoundParameter("Angle", 0, 0, 1)
    .setDescription("Direct spectrum angle; the knob spans one full turn");

  public final CompoundParameter panSpeed =
    new CompoundParameter("Pan Speed", 0.5387890615529614, 0, 1)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Horizontal pan speed; center is still, left/right reverse");

  public final CompoundParameter phaseSpeed =
    new CompoundParameter("Phase Speed", 0.5387890615529614, 0, 1)
    .setDescription("Output phase speed, from stopped to one-half cycle per second");

  private final double[] envelope = new double[MAX_BARS];
  private final double[] envelopePeak = new double[MAX_BARS];
  private final int[] envelopePhase = new int[MAX_BARS];
  private final double[] barHeight = new double[MAX_BARS];

  private int activeBars = 15;
  private double panPhase = 0;
  private double valuePhase = 0;
  private double sceneScale = 1;
  private double sceneCos = 1;
  private double sceneSin = 0;
  private double aspectX = 1;

  public SpectrumAnalyzatronPattern(LX lx) {
    super(lx);
    addParameter("bars", this.bars);
    addParameter("gain", this.gain);
    addParameter("slope", this.slope);
    addParameter("minFreq", this.minFreq);
    addParameter("maxFreq", this.maxFreq);
    addParameter("minDb", this.minDb);
    addParameter("maxDb", this.maxDb);
    addParameter("attack", this.attack);
    addParameter("decay", this.decay);
    addParameter("sustain", this.sustain);
    addParameter("release", this.release);
    addParameter("zoom", this.zoom);
    addParameter("angle", this.angle);
    addParameter("panSpeed", this.panSpeed);
    addParameter("phaseSpeed", this.phaseSpeed);
  }

  @Override
  protected void run(double deltaMs) {
    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs * .001, 0, .1) : 0;
    this.activeBars = Math.max(10,
      Math.min(40, (int) Math.round(10 + this.bars.getValue() * 30)));

    // Four octaves of camera scale centered at 1x.
    this.sceneScale = Math.pow(2, (this.zoom.getValue() - .5) * 4);

    // Angle is an absolute UI value. Only the output phase accumulates over
    // time.
    double radians = this.angle.getValue() * TAU;
    this.sceneCos = Math.cos(radians);
    this.sceneSin = Math.sin(radians);

    // Independent positional phase: +/- one whole spectrum tile per second.
    this.panPhase += (this.panSpeed.getValue() - .5) * 2 * dt;
    this.panPhase -= Math.floor(this.panPhase);

    this.valuePhase += this.phaseSpeed.getValue() * .5 * dt;
    this.valuePhase -= Math.floor(this.valuePhase);

    if (this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    } else {
      this.aspectX = 1;
    }

    updateSpectrum(dt);
    draw();
  }

  private void updateSpectrum(double dt) {
    GraphicMeter meter = this.lx.engine.audio.meter;
    int sourceBands = (meter != null) ? meter.getNumBands() : 0;

    double low = clamp(this.minFreq.getValue(), 0, .98);
    double high = clamp(this.maxFreq.getValue(), .02, 1);
    if (high < low + .02) {
      high = Math.min(1, low + .02);
      low = Math.max(0, high - .02);
    }

    double gainDb = -24 + this.gain.getValue() * 72;
    double slopeDb = (this.slope.getValue() - .5) * 48;
    double floorDb = -96 + this.minDb.getValue() * 72;
    double ceilingDb = -48 + this.maxDb.getValue() * 48;
    if (ceilingDb < floorDb + 6) {
      ceilingDb = floorDb + 6;
    }

    double attackSec = expMap(.006, .6, this.attack.getValue());
    double decaySec = expMap(.015, 1.8, this.decay.getValue());
    double releaseSec = expMap(.02, 3.5, this.release.getValue());
    double sustainAmount = this.sustain.getValue();

    for (int bar = 0; bar < MAX_BARS; ++bar) {
      double target = 0;
      if (bar < this.activeBars && sourceBands > 0) {
        double f0 = low + (high - low) * bar / this.activeBars;
        double f1 = low + (high - low) * (bar + 1) / this.activeBars;
        double inputDb = readBandRange(meter, f0 * sourceBands, f1 * sourceBands, sourceBands);

        // Pivot the octave slope at the middle of the selected frequency range.
        double center = Math.max(.5, (f0 + f1) * sourceBands * .5);
        double pivot = Math.max(.5, (low + high) * sourceBands * .5);
        double octaves = Math.log(center / pivot) / Math.log(2);
        inputDb += gainDb + slopeDb * octaves;
        target = clamp((inputDb - floorDb) / (ceilingDb - floorDb), 0, 1);
      }

      advanceEnvelope(bar, target, dt, attackSec, decaySec, releaseSec, sustainAmount);
      this.barHeight[bar] = this.envelope[bar] * .48;
    }
  }

  /**
   * Average the raw decibel values of every analyzer band touched by
   * [start, end).
   */
  private static double readBandRange(GraphicMeter meter, double start, double end,
      int sourceBands) {
    int first = Math.max(0, Math.min(sourceBands - 1, (int) Math.floor(start)));
    int last = Math.max(first, Math.min(sourceBands - 1, (int) Math.ceil(end) - 1));
    double total = 0;
    int count = 0;
    for (int i = first; i <= last; ++i) {
      float rawDb = meter.getDecibelsf(i);
      if (Float.isFinite(rawDb)) {
        total += rawDb;
      } else {
        total += -120;
      }
      ++count;
    }
    return (count > 0) ? total / count : 0;
  }

  /**
   * Per-frequency ADSR. A newly rising bin attacks to its peak, decays to the
   * sustain fraction while audio remains present, and releases only after the
   * raw bin falls below the gate.
   */
  private void advanceEnvelope(int index, double target, double dt, double attackSec,
      double decaySec, double releaseSec, double sustainAmount) {
    int phase = this.envelopePhase[index]; // 0 release, 1 attack, 2 decay/sustain
    double value = this.envelope[index];

    if (target > SIGNAL_GATE) {
      if (phase == 0 || target > this.envelopePeak[index]) {
        phase = 1;
        this.envelopePeak[index] = target;
      }

      if (phase == 1) {
        value = approach(value, this.envelopePeak[index], dt, attackSec);
        if (value >= this.envelopePeak[index] * .995) {
          phase = 2;
        }
      } else {
        // The live input can lift sustain immediately; decay controls only
        // falls.
        double held = Math.max(target, this.envelopePeak[index] * sustainAmount);
        if (held >= value) {
          value = held;
        } else {
          value = approach(value, held, dt, decaySec);
        }
      }
    } else {
      phase = 0;
      value = approach(value, 0, dt, releaseSec);
      if (value < .0001) {
        value = 0;
        this.envelopePeak[index] = 0;
      }
    }

    this.envelope[index] = clamp(value, 0, 1);
    this.envelopePhase[index] = phase;
  }

  private static double approach(double value, double target, double dt, double seconds) {
    if (dt <= 0) {
      return value;
    }
    double amount = 1 - Math.exp(-dt / Math.max(.0001, seconds));
    return value + (target - value) * amount;
  }

  private static double expMap(double minimum, double maximum, double amount) {
    return minimum * Math.pow(maximum / minimum, amount);
  }

  private void draw() {
    final int black = LXColor.hsb(0, 0, 0);

    for (LXPoint point : this.model.points) {
      // Inverse-rotate into the spectrum's local coordinates. One tile occupies
      // sceneScale corrected units and fractional wrapping repeats it forever.
      double dx = (point.xn - .5) * this.aspectX;
      double dy = point.yn - .5;
      double x = (this.sceneCos * dx + this.sceneSin * dy) / this.sceneScale;
      double y = (-this.sceneSin * dx + this.sceneCos * dy) / this.sceneScale;

      double tileX = x - this.panPhase + .5;
      tileX -= Math.floor(tileX);

      // Sample at bar centers and interpolate neighboring heights. Hard black
      // separator gaps alias against discrete LED columns while panning: a gap
      // can momentarily land on every point in a physical column. This
      // continuous reconstruction has no such zero-width traps and wraps across
      // the seam.
      double barPosition = tileX * this.activeBars - .5;
      double leftRaw = Math.floor(barPosition);
      double blend = barPosition - leftRaw;
      blend = blend * blend * (3 - 2 * blend);
      int left = (int) (leftRaw % this.activeBars);
      if (left < 0) {
        left += this.activeBars;
      }
      int right = (left + 1) % this.activeBars;

      // Spectrum value is always local Y and is mirrored about its centerline.
      double height = this.barHeight[left]
        + (this.barHeight[right] - this.barHeight[left]) * blend;
      double distance = Math.abs(y);
      if (height <= 0 || distance > height + 1e-9) {
        this.colors[point.index] = black;
        continue;
      }

      double value = .2 + .8 * Math.min(1, distance / height);
      value = value + this.valuePhase;
      value -= Math.floor(value);
      this.colors[point.index] = LXColor.hsb(0, 0, value * 100);
    }
  }

  private static double clamp(double v, double low, double high) {
    return (v < low) ? low : (v > high) ? high : v;
  }
}
