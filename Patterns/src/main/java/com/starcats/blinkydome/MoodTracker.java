package com.starcats.blinkydome;

import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXNormalizedParameter;
import heronarts.lx.parameter.LXParameter;

/** Smooth 0-1 gate for one configurable controller mood. */
@LXCategory("Blinky Dome")
@LXModulator.Global("Mood Tracker")
@LXModulator.Device("Mood Tracker")
@LXComponent.Description("Smooth gate that approaches 1 while its selected mood is active")
public class MoodTracker extends LXModulator implements LXNormalizedParameter {

  public final EnumParameter<Mood> mood =
    new EnumParameter<Mood>("Mood", Mood.DRIVING)
    .setDescription("Controller mood that opens this tracker");

  public final CompoundParameter decay =
    new CompoundParameter("Decay", .5, .01, 10)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("Exponential time constant for approaching the active or inactive value");

  private double smoothed = 0;

  public MoodTracker() {
    this("Mood Tracker");
  }

  public MoodTracker(String label) {
    super(label);
    addParameter("mood", this.mood);
    addParameter("decay", this.decay);
  }

  @Override
  protected double computeValue(double deltaMs) {
    PrimaryController controller = MoodState.get();
    double target = controller != null && controller.getMood() == this.mood.getEnum() ? 1 : 0;
    double tauMs = this.decay.getValue() * 1000;
    double alpha = (tauMs <= 0) ? 1 : 1 - Math.exp(-deltaMs / tauMs);
    this.smoothed += (target - this.smoothed) * alpha;
    return this.smoothed;
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
