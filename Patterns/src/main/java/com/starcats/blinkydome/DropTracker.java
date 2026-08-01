package com.starcats.blinkydome;

import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.LXTriggerSource;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXNormalizedParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * Fires once, on the drop.
 *
 * Watches for the one transition that matters -- {@link Mood#BUILDING} giving
 * way to {@link Mood#DRIVING} -- and answers with a single trigger plus a linear
 * ramp from 1 down to 0. Nothing else moves it: a track that was already driving
 * and stays driving never sets this off, and neither does drifting from ambient
 * into a groove without a build in front of it.
 *
 * Linear rather than exponential decay because this is a one-shot sweep, not a
 * pulse. Exponential spends most of its life near zero, which is the wrong shape
 * for something meant to hold the room for a few seconds and then hand back.
 */
@LXCategory("Blinky Dome")
@LXModulator.Global("Drop Tracker")
@LXModulator.Device("Drop Tracker")
@LXComponent.Description("One-shot ramp fired by the BUILDING to DRIVING transition")
public class DropTracker extends LXModulator implements LXNormalizedParameter, LXTriggerSource {

  public final CompoundParameter duration =
    (CompoundParameter) new CompoundParameter("Fall", 4, .1, 30)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("How long the ramp takes to fall from 1 back to 0");

  public final TriggerParameter drop =
    new TriggerParameter("Drop")
    .setDescription("Fires once when a build resolves into a drive (output)");

  private long lastDropCount = -1;
  private double ramp = 0;

  public DropTracker() {
    this("Drop Tracker");
  }

  public DropTracker(String label) {
    super(label);
    addParameter("duration", this.duration);
    addParameter("drop", this.drop);
  }

  @Override
  protected double computeValue(double deltaMs) {
    PrimaryController controller = MoodState.get();
    if (controller == null) {
      // Keep falling rather than freezing, so an in-flight ramp finishes
      // gracefully if the controller goes away mid-drop.
      this.lastDropCount = -1;
      return decay(deltaMs);
    }

    long drops = controller.getDropCount();
    if (this.lastDropCount >= 0 && drops != this.lastDropCount) {
      this.ramp = 1;
      this.drop.trigger();
      this.lastDropCount = drops;
      // Full value on the frame of the drop, before any decay is applied.
      return this.ramp;
    }
    this.lastDropCount = drops;
    return decay(deltaMs);
  }

  private double decay(double deltaMs) {
    double seconds = this.duration.getValue();
    if (seconds > 0) {
      this.ramp -= deltaMs / (seconds * 1000);
    } else {
      this.ramp = 0;
    }
    if (this.ramp < 0) {
      this.ramp = 0;
    }
    return this.ramp;
  }

  @Override
  public BooleanParameter getTriggerSource() {
    return this.drop;
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
