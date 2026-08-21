package com.starcats.blinkydome;

import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.LXTriggerSource;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXNormalizedParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * Configurable forecast beat waveform active whenever the controller is not idle.
 *
 * DRIVING and AMBIENT both follow the controller's current best BPM and phase.
 * IDLE suppresses beat triggers and targets zero. Triangle and cosine are slew-
 * limited at more than their fastest natural rate: ordinary motion has no lag,
 * while a discontinuous clock correction can never jump the published output.
 */
@LXCategory("Blinky Dome")
@LXModulator.Global("Ambient Tracker")
@LXModulator.Device("Ambient Tracker")
@LXComponent.Description("Forecast pulse, triangle, or cosine waveform active outside IDLE")
public class AmbientTracker extends LXModulator implements LXNormalizedParameter, LXTriggerSource {

  private static final double FALLBACK_BPM = 120;

  /**
   * Extra rate above each waveform's steepest natural slope. This lets the
   * output close phase-correction error without delaying normal waveform motion.
   */
  private static final double SLEW_HEADROOM = 2;

  public enum Output {
    PULSE("Pulse"),
    TRIANGLE("Triangle"),
    COS("Cos");

    private final String label;

    private Output(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  public final EnumParameter<Output> output =
    new EnumParameter<Output>("Output", Output.PULSE)
    .setDescription("Waveform generated from the controller's forecast beat grid");

  public final CompoundParameter decay =
    new CompoundParameter("Decay", 30, 0, 100)
    .setDescription("Pulse decay speed -- reaches 1/e after 10/Decay seconds");

  public final TriggerParameter beat =
    new TriggerParameter("Beat")
    .setDescription("Fires on each beat (output)");

  private long lastBeatCount = -1;
  private double publishedValue = 0;

  public AmbientTracker() {
    this("Ambient Tracker");
  }

  public AmbientTracker(String label) {
    super(label);
    addParameter("output", this.output);
    addParameter("decay", this.decay);
    addParameter("beat", this.beat);
  }

  @Override
  protected double computeValue(double deltaMs) {
    PrimaryController controller = MoodState.get();
    Output selectedOutput = this.output.getEnum();
    double target = 0;

    if (controller == null) {
      this.lastBeatCount = -1;
    } else {
      long beats = controller.getBeatCount();
      if (controller.getMood() == Mood.IDLE) {
        this.lastBeatCount = beats;
      } else {
        if (this.lastBeatCount >= 0 && beats != this.lastBeatCount) {
          this.beat.trigger();
        }
        this.lastBeatCount = beats;

        target = switch (selectedOutput) {
          case PULSE -> envelope(controller.getSinceBeatMs());
          case TRIANGLE -> triangle(controller.getGridBeats());
          case COS -> cosine(controller.getGridBeats());
        };
      }
    }

    // Pulse is deliberately a hard attack. The continuous modes instead follow
    // the target through a linear slew. Their limit exceeds the waveform's own
    // maximum slope, so an uncorrected clock passes through with no latency.
    if (selectedOutput == Output.PULSE) {
      this.publishedValue = target;
    } else {
      double bpm = (controller != null && controller.getBpm() > 0)
        ? controller.getBpm()
        : FALLBACK_BPM;
      double naturalRate = bpm / 60;
      if (selectedOutput == Output.COS) {
        // d/db [.5 - .5 cos(pi*b)] peaks at pi/2 per beat.
        naturalRate *= Math.PI / 2;
      }
      double dt = Double.isFinite(deltaMs) ? Math.max(0, deltaMs) * .001 : 0;
      this.publishedValue = moveToward(
        this.publishedValue,
        target,
        naturalRate * SLEW_HEADROOM * dt
      );
    }
    return this.publishedValue;
  }

  private double envelope(double sinceBeatMs) {
    return Math.exp(-(sinceBeatMs / 1000) * this.decay.getValue() / 10);
  }

  private static double triangle(double gridBeats) {
    double phase = gridBeats - 2 * Math.floor(gridBeats / 2);
    return phase <= 1 ? phase : 2 - phase;
  }

  private static double cosine(double gridBeats) {
    double phase = gridBeats - 2 * Math.floor(gridBeats / 2);
    return .5 - .5 * Math.cos(Math.PI * phase);
  }

  /** Move no farther than maxDelta toward target, without overshoot. */
  private static double moveToward(double current, double target, double maxDelta) {
    double difference = target - current;
    if (Math.abs(difference) <= maxDelta) {
      return target;
    }
    return current + Math.copySign(maxDelta, difference);
  }

  @Override
  public BooleanParameter getTriggerSource() {
    return this.beat;
  }

  @Override
  public double getNormalized() {
    return getValue();
  }

  @Override
  public LXNormalizedParameter setNormalized(double value) {
    return this;
  }

  @Override
  public boolean isWrappable() {
    return false;
  }
}
