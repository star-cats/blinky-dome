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
 * IDLE suppresses beat triggers and holds the output at zero.
 */
@LXCategory("Blinky Dome")
@LXModulator.Global("Ambient Tracker")
@LXModulator.Device("Ambient Tracker")
@LXComponent.Description("Forecast pulse, triangle, or cosine waveform active outside IDLE")
public class AmbientTracker extends LXModulator implements LXNormalizedParameter, LXTriggerSource {

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
    if (controller == null) {
      this.lastBeatCount = -1;
      return 0;
    }

    long beats = controller.getBeatCount();
    if (controller.getMood() == Mood.IDLE) {
      this.lastBeatCount = beats;
      return 0;
    }

    if (this.lastBeatCount >= 0 && beats != this.lastBeatCount) {
      this.beat.trigger();
    }
    this.lastBeatCount = beats;

    return switch (this.output.getEnum()) {
      case PULSE -> envelope(controller.getSinceBeatMs());
      case TRIANGLE -> triangle(controller.getGridBeats());
      case COS -> cosine(controller.getGridBeats());
    };
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
