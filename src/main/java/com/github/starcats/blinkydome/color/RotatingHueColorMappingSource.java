package com.github.starcats.blinkydome.color;

import heronarts.lx.LX;
import heronarts.lx.LXModulatorComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.modulator.SawLFO;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXNormalizedParameter;

/**
 * A ColorMappingSource that just rotates some hue according to a modulator
 */
public class RotatingHueColorMappingSource extends LXModulatorComponent implements ColorMappingSource {

  /** How long this CMS takes to rotate through all hues */
  public final DiscreteParameter huePeriodMs = new DiscreteParameter("h period", 10000, 1000, 30000)
      .setDescription("How long this CMS takes to rotate through all hues");

  private final LX lx;
  private SawLFO hueOffset = new SawLFO(0, 360, huePeriodMs);

  public RotatingHueColorMappingSource(LX lx) {
    super(lx, "rotating hue color mapping source");
    addParameter("period", this.huePeriodMs);
    startModulator(hueOffset);

    this.lx = lx;
    lx.engine.addLoopTask(this);
  }

  @Override
  public int getColor(double normalizedValue) {
    return LXColor.hsb(
        (hueOffset.getValuef() + 360f * normalizedValue) % 360,
        100,
        100
    );
  }

  @Override
  public int getColor(LXNormalizedParameter normalizedParameter) {
    return getColor(normalizedParameter.getNormalized());
  }

  @Override
  public void dispose() {
    super.dispose();
    lx.engine.removeLoopTask(this);
  }
}
