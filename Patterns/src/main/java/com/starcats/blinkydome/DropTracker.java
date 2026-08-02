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
 * Fires once when DRIVING returns directly from AMBIENT.
 *
 * Watches for {@link Mood#AMBIENT} giving way to {@link Mood#DRIVING} -- bass
 * returning after a stretch without it -- and answers with a single trigger plus
 * a linear ramp from 1 down to 0.
 *
 * The {@link #cooldown} is what keeps that meaningful. Bass detection is not
 * perfect, and a track that drops a beat or two mid-set can flicker to ambient
 * and back inside a few seconds; without a holdoff, every one of those flickers
 * would read as a drop and the biggest gesture in the rig would become the most
 * common thing it does. A minute between drops means the ones that land are the
 * ones worth watching.
 *
 * Linear rather than exponential decay because this is a one-shot sweep, not a
 * pulse. Exponential spends most of its life near zero, which is the wrong shape
 * for something meant to hold the room for a few seconds and then hand back.
 */
@LXCategory("Blinky Dome")
@LXModulator.Global("Drop Tracker")
@LXModulator.Device("Drop Tracker")
@LXComponent.Description("One-shot ramp fired when DRIVING returns from AMBIENT")
public class DropTracker extends LXModulator implements LXNormalizedParameter, LXTriggerSource {

  public final CompoundParameter duration =
    (CompoundParameter) new CompoundParameter("Fall", 4, .1, 30)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("How long the ramp takes to fall from 1 back to 0");

  public final CompoundParameter cooldown =
    (CompoundParameter) new CompoundParameter("Reset", 60, 0, 300)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("Minimum time between drops -- a return to driving inside this window is ignored");

  public final TriggerParameter drop =
    new TriggerParameter("Drop")
    .setDescription("Fires once when DRIVING returns from AMBIENT (output)");

  private long lastDriveCount = -1;
  private double ramp = 0;

  /**
   * Time since the last drop was allowed through. Starts high so the first drop
   * of a set is never swallowed by a cooldown that has not had a chance to run.
   */
  private double sinceDropMs = Double.MAX_VALUE / 4;

  public DropTracker() {
    this("Drop Tracker");
  }

  public DropTracker(String label) {
    super(label);
    addParameter("duration", this.duration);
    addParameter("cooldown", this.cooldown);
    addParameter("drop", this.drop);
  }

  @Override
  protected double computeValue(double deltaMs) {
    this.sinceDropMs += deltaMs;

    PrimaryController controller = MoodState.get();
    if (controller == null) {
      // Keep falling rather than freezing, so an in-flight ramp finishes
      // gracefully if the controller goes away mid-drop.
      this.lastDriveCount = -1;
      return decay(deltaMs);
    }

    long drives = controller.getDriveCount();
    boolean entered = this.lastDriveCount >= 0 && drives != this.lastDriveCount;
    this.lastDriveCount = drives;

    // The counter is consumed either way: a transition inside the cooldown is
    // ignored, not deferred, so the holdoff cannot leave a drop queued up to
    // fire the moment it expires.
    if (entered && this.sinceDropMs >= this.cooldown.getValue() * 1000) {
      this.sinceDropMs = 0;
      this.ramp = 1;
      this.drop.trigger();
      // Full value on the frame of the drop, before any decay is applied.
      return this.ramp;
    }
    return decay(deltaMs);
  }

  /** Seconds until another drop is allowed, 0 when ready. Drawn by the UI. */
  public double getCooldownRemaining() {
    return Math.max(0, this.cooldown.getValue() - this.sinceDropMs / 1000);
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
