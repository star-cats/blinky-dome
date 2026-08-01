package com.starcats.blinkydome;

import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.parameter.LXNormalizedParameter;

/**
 * The {@link PrimaryController}'s smoothed intensity, on its own, as something
 * you can drop into a modulator slot and map.
 *
 * No analysis of its own and nothing to tune -- the smoothing, the band weights
 * and the charge and release times all live on the controller, where they are
 * set once for the whole show. This is a wire.
 *
 * The controller's own value is already this figure, so on a show with one
 * controller in reach the two are interchangeable. What this exists for is
 * everywhere that is not true: a second {@link PrimaryController} dropped in
 * just to have intensity to hand would register itself in {@link MoodState} and
 * start competing to be the one every other tracker follows. Adding one of these
 * instead costs nothing and cannot do that.
 *
 * With no controller in the project it reads 0 rather than throwing, the same as
 * the other trackers.
 */
@LXCategory("Blinky Dome")
@LXModulator.Global("Intensity Tracker")
@LXModulator.Device("Intensity Tracker")
@LXComponent.Description("Follows the Primary Controller's smoothed intensity")
public class IntensityTracker extends LXModulator implements LXNormalizedParameter {

  public IntensityTracker() {
    this("Intensity Tracker");
  }

  public IntensityTracker(String label) {
    super(label);
  }

  @Override
  protected double computeValue(double deltaMs) {
    PrimaryController controller = MoodState.get();
    return (controller != null) ? controller.getIntensity() : 0;
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
