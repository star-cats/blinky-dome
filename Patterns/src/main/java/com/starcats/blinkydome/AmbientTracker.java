package com.starcats.blinkydome;

import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.LXTriggerSource;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXNormalizedParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * The same on-beat pulse as {@link DriveTracker}, scaled by how much energy is
 * actually in the room.
 *
 * Deliberately ungated: where DriveTracker is switched off outside its mood,
 * this one is always live and lets the {@link PrimaryController}'s smoothed
 * intensity do the shaping. That means there is never a hole -- during a
 * breakdown it goes quiet because the room is quiet, not because a state machine
 * decided to mute it, and it swells back on its own as a build climbs.
 *
 * The beat grid still comes from the controller, so this stays in time with
 * everything else even while the bass is absent and the clock is free-running.
 */
@LXCategory("Blinky Dome")
@LXModulator.Global("Ambient Tracker")
@LXModulator.Device("Ambient Tracker")
@LXComponent.Description("On-beat pulse scaled by smoothed intensity, never gated")
public class AmbientTracker extends LXModulator implements LXNormalizedParameter, LXTriggerSource {

  public final CompoundParameter decay =
    new CompoundParameter("Decay", 20, 0, 100)
    .setDescription("How fast the pulse falls after each beat -- reaches 1/e after 10/Decay seconds");

  public final CompoundParameter depth =
    new CompoundParameter("Depth", 1, 0, 1)
    .setDescription("How much the intensity scaling applies; at 0 the pulse ignores intensity entirely");

  public final TriggerParameter beat =
    new TriggerParameter("Beat")
    .setDescription("Fires on each beat (output)");

  private long lastBeatCount = -1;
  private double lastIntensity = 0;

  public AmbientTracker() {
    this("Ambient Tracker");
  }

  public AmbientTracker(String label) {
    super(label);
    addParameter("decay", this.decay);
    addParameter("depth", this.depth);
    addParameter("beat", this.beat);
  }

  @Override
  protected double computeValue(double deltaMs) {
    PrimaryController controller = MoodState.get();
    if (controller == null) {
      this.lastBeatCount = -1;
      this.lastIntensity = 0;
      return 0;
    }

    long beats = controller.getBeatCount();
    if (this.lastBeatCount >= 0 && beats != this.lastBeatCount) {
      this.beat.trigger();
    }
    this.lastBeatCount = beats;
    this.lastIntensity = controller.getIntensity();

    // Depth blends between "intensity scales the pulse" and "it does not", so a
    // fixed-brightness ambient layer is still one knob away.
    double scale = 1 - this.depth.getValue() * (1 - this.lastIntensity);
    return envelope(controller.getSinceBeatMs()) * scale;
  }

  private double envelope(double sinceBeatMs) {
    return Math.exp(-(sinceBeatMs / 1000) * this.decay.getValue() / 10);
  }

  /** The intensity last read from the controller, for the UI meter. */
  public double getIntensity() {
    return this.lastIntensity;
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
