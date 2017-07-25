package com.github.starcats.blinkydome.pattern.perlin;

import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.SawLFO;
import heronarts.lx.parameter.DiscreteParameter;

import java.util.Arrays;
import java.util.List;

/**
 * Colors points based on a random hue offset through a rotating rainbow
 */
public class RotatingHueColorizer extends PerlinNoiseColorizer {

  /** How long this colorizer takes to rotate through all hues */
  public final DiscreteParameter huePeriodMs = new DiscreteParameter("h period", 10000, 1000, 30000);

  private SawLFO hueOffset = new SawLFO(0, 360, huePeriodMs);

  public RotatingHueColorizer(PerlinNoiseExplorer noiseSource) {
    super(noiseSource);
    hueOffset.start();
  }

  @Override
  public List<LXModulator> getModulators() {
    return Arrays.asList(hueOffset);
  }

  @Override
  public PerlinNoiseColorizer rotate() {
    double newHue = (hueOffset.getValue() + Math.random() * 360 - 180) % 360;
    hueOffset.setValue(newHue > 0 ? newHue : 360 + newHue);
    return this;
  }

  @Override
  public int getColor(LXPoint point) {
    return LXColor.hsb(
        (hueOffset.getValuef() + 360 * noiseSource.getNoise(point.index)) % 360,
        100,
        100
    );
  }
}
