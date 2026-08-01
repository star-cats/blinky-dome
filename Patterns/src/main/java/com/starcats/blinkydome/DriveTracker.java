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
 * The on-beat pulse for when the track is actually driving.
 *
 * Emits on the {@link PrimaryController}'s beat grid -- no tracking of its own,
 * no tempo of its own -- shaped into exp(-t*k/10) and multiplied by a gate that
 * only opens in {@link Mood#DRIVING}.
 *
 * The gate is an RC follower rather than a switch on purpose. A hard cut on the
 * mood change would slam the whole rig on and off at the exact moment the music
 * is doing something interesting; easing over a couple of seconds lets the drive
 * layer arrive under the drop instead of snapping in on top of it.
 *
 * Usable either way round: {@link #beat} for anything that fires discretely, and
 * the modulator's own value for anything that should follow the decay.
 */
@LXCategory("Blinky Dome")
@LXModulator.Global("Drive Tracker")
@LXModulator.Device("Drive Tracker")
@LXComponent.Description("On-beat pulse gated to the DRIVING mood")
public class DriveTracker extends LXModulator implements LXNormalizedParameter, LXTriggerSource {

  public final CompoundParameter decay =
    new CompoundParameter("Decay", 30, 0, 100)
    .setDescription("How fast the pulse falls after each beat -- reaches 1/e after 10/Decay seconds");

  public final CompoundParameter gateTime =
    (CompoundParameter) new CompoundParameter("Gate", 2, .1, 10)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("How long the gate takes to open on entering DRIVING, or close on leaving");

  public final TriggerParameter beat =
    new TriggerParameter("Beat")
    .setDescription("Fires on each beat while the gate is open (output)");

  private long lastBeatCount = -1;
  private double gate = 0;

  public DriveTracker() {
    this("Drive Tracker");
  }

  public DriveTracker(String label) {
    super(label);
    addParameter("decay", this.decay);
    addParameter("gateTime", this.gateTime);
    addParameter("beat", this.beat);
  }

  @Override
  protected double computeValue(double deltaMs) {
    PrimaryController controller = MoodState.get();
    if (controller == null) {
      // Nothing to follow. Let the gate fall rather than freezing it, so
      // deleting the controller fades this out instead of leaving it stuck on.
      this.gate = approach(this.gate, 0, deltaMs);
      this.lastBeatCount = -1;
      return 0;
    }

    // Counter rather than a per-frame flag, so this is right whichever order the
    // engine happens to run the two modulators in.
    long beats = controller.getBeatCount();
    if (this.lastBeatCount >= 0 && beats != this.lastBeatCount) {
      this.beat.trigger();
    }
    this.lastBeatCount = beats;

    this.gate = approach(this.gate, controller.getMood() == Mood.DRIVING ? 1 : 0, deltaMs);
    return envelope(controller.getSinceBeatMs()) * this.gate;
  }

  private double envelope(double sinceBeatMs) {
    return Math.exp(-(sinceBeatMs / 1000) * this.decay.getValue() / 10);
  }

  private double approach(double current, double target, double deltaMs) {
    double tau = this.gateTime.getValue() * 1000;
    double alpha = (tau <= 0) ? 1 : 1 - Math.exp(-deltaMs / tau);
    return current + (target - current) * alpha;
  }

  /** How far open the mood gate currently is, 0-1. Drawn by the UI. */
  public double getGate() {
    return this.gate;
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
