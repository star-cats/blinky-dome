package com.starcats.blinkydome;

import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXNormalizedParameter;
import heronarts.lx.parameter.LXParameter;

/**
 * The {@link PrimaryController}'s smoothed intensity, stretched onto a useful
 * range and smoothed again, as something you can drop into a modulator slot and
 * map.
 *
 * Two jobs, and both exist because the controller's intensity is measurement
 * rather than performance. It is honest about the room, which means it rarely
 * uses the whole 0-1 it is scaled to -- a busy floor might live between .16 and
 * .5 all night, and anything mapped straight to it only ever sees the bottom
 * third of its travel. {@link #min} and {@link #max} say which part of that
 * range is the part worth watching, and the output uses all of 0-1 to show it.
 *
 * Then {@link #smooth} slows the result down. The controller already has a
 * follower on the band mix, but it is tuned to keep up with the music -- fast
 * enough that a riser reads as it happens. A slow swell wants the opposite, and
 * one time constant cannot be both, so this is a second, lazier follower on top
 * rather than an argument with the first.
 *
 * On a show with one controller the controller's own value is the input to all
 * this. What this exists for is everywhere that is not enough: a second
 * {@link PrimaryController} dropped in just to have intensity to shape would
 * register itself in {@link MoodState} and start competing to be the one every
 * other tracker follows. Adding one of these instead costs nothing and cannot do
 * that. Add as many as you want, each with its own window and its own lag.
 *
 * With no controller in the project it falls to 0 rather than throwing, the same
 * as the other trackers.
 */
@LXCategory("Blinky Dome")
@LXModulator.Global("Intensity Tracker")
@LXModulator.Device("Intensity Tracker")
@LXComponent.Description("Primary Controller intensity, windowed onto 0-1 and smoothed")
public class IntensityTracker extends LXModulator implements LXNormalizedParameter {

  public final CompoundParameter min =
    new CompoundParameter("Min", .16, 0, 1)
    .setDescription("Controller intensity that reads as 0 here -- anything below is floor");

  public final CompoundParameter max =
    new CompoundParameter("Max", .5, 0, 1)
    .setDescription("Controller intensity that reads as 1 here -- anything above is ceiling");

  public final CompoundParameter smooth =
    new CompoundParameter("Smooth", 1.5, .01, 15)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("Extra smoothing on top of the controller's own; time to close most of a step");

  /** The follower's state, and this modulator's value. */
  private double level = 0;

  /** Last reading off the controller, before the window and the smoothing. */
  private double input = 0;

  /** Where the follower is heading: the windowed input, before the smoothing. */
  private double target = 0;

  public IntensityTracker() {
    this("Intensity Tracker");
  }

  public IntensityTracker(String label) {
    super(label);
    addParameter("min", this.min);
    addParameter("max", this.max);
    addParameter("smooth", this.smooth);
  }

  @Override
  protected double computeValue(double deltaMs) {
    PrimaryController controller = MoodState.get();
    this.input = (controller != null) ? controller.getIntensity() : 0;
    this.target = window(this.input);

    // Exponential approach, framed so the step is right for whatever deltaMs
    // actually was rather than assuming a frame rate.
    double tauMs = this.smooth.getValue() * 1000;
    double alpha = (tauMs <= 0) ? 1 : 1 - Math.exp(-deltaMs / tauMs);
    this.level += (this.target - this.level) * alpha;
    return this.level;
  }

  /**
   * Maps [min, max] onto [0, 1], clamped outside it.
   *
   * Inverting the two -- max below min -- is left working rather than guarded
   * against, since it is a legitimate way to ask for the inverse: bright when the
   * room is quiet. Only the degenerate equal case needs a decision, and it
   * becomes a hard switch at that level, which is the limit the ramp approaches
   * as the window closes anyway.
   */
  private double window(double raw) {
    double lo = this.min.getValue();
    double hi = this.max.getValue();
    if (lo == hi) {
      return (raw >= hi) ? 1 : 0;
    }
    return clamp((raw - lo) / (hi - lo));
  }

  private static double clamp(double v) {
    return (v < 0) ? 0 : (v > 1) ? 1 : v;
  }

  /** The controller's intensity as last read, ahead of the window. Drawn by the UI. */
  public double getInput() {
    return this.input;
  }

  /** The windowed value the follower is chasing. Drawn by the UI. */
  public double getTarget() {
    return this.target;
  }

  @Override
  public double getNormalized() {
    return getValue();
  }

  /** Output only -- intensity is measured, not driven. */
  @Override
  public LXNormalizedParameter setNormalized(double value) {
    return this;
  }

  @Override
  public boolean isWrappable() {
    return false;
  }
}
