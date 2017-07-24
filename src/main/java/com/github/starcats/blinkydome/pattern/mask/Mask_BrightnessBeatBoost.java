package com.github.starcats.blinkydome.pattern.mask;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;

/**
 * Mask that applies a base-brightness to all LED's, then on beat detection increases the brightness for a bit
 */
public class Mask_BrightnessBeatBoost extends LXPattern {
  public final BooleanParameter trigger = new BooleanParameter("trigger", false)
      .setDescription("Trigger the brightness boost")
      .setMode(BooleanParameter.Mode.MOMENTARY);

  public final CompoundParameter baseBrightness = new CompoundParameter("baseBrightness", 0.30)
      .setDescription("The no-boost base brightness");

  private final double MIN_T_THRESHOLD = 0.05;

  /**
   * Exponential dropoff of the brightness boost, per ms.
   * eg in 500ms, the brightness boost will be at Math.pow({bright decay}, 500) %
   */
  private final CompoundParameter brightnessBoostDecayPerMs = (CompoundParameter) new CompoundParameter(
      "decay", 0.998, 0.990, 0.999)
      .setDescription("Exponential dropoff of the brightness boost, per ms (eg after 123ms, the brightness boost " +
          "will be at Math.pow({bb decay}, 123))")
      .setFormatter(value -> String.format("%.3f", value));


  private double brightnessBoostT = 0;

  public Mask_BrightnessBeatBoost(LX lx) {
    super(lx);
    addParameter(trigger);
    addParameter(baseBrightness);
    addParameter(brightnessBoostDecayPerMs);
  }

  @Override
  protected void run(double deltaMs) {
    if (trigger.getValueb()) {
      brightnessBoostT = 1.;
    } else if (brightnessBoostT > MIN_T_THRESHOLD) {
      brightnessBoostT *= Math.pow(brightnessBoostDecayPerMs.getValue(), deltaMs);
    } else {
      brightnessBoostT = 0.;
    }

    double brightness = baseBrightness.getValue() + (1. - baseBrightness.getValue()) * brightnessBoostT;

    // scale to .rgb() input range
    int rgb = (int) (255 * brightness);

    for (LXPoint p : this.model.getPoints()) {
      setColor(p.index, LXColor.rgb(rgb, rgb, rgb));
    }
  }
}
