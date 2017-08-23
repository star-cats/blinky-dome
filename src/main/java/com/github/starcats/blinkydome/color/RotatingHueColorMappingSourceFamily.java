package com.github.starcats.blinkydome.color;

import heronarts.lx.LX;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.DiscreteParameter;

/**
 * Created by dlopuch on 8/23/17.
 */
public class RotatingHueColorMappingSourceFamily extends RotatingHueColorMappingSource
    implements ColorMappingSourceFamily
{
  private final DiscreteParameter dummySourceSelect = new DiscreteParameter("source", new String[] {"rainbow"})
      .setDescription("There's only one source in rotating hue -- the rainbow");

  private final BooleanParameter dummyRandomSource = new BooleanParameter("random", false)
      .setMode(BooleanParameter.Mode.MOMENTARY);

  public RotatingHueColorMappingSourceFamily(LX lx) {
    super(lx);
  }

  @Override
  public DiscreteParameter getSourceSelect() {
    return dummySourceSelect;
  }

  @Override
  public BooleanParameter getRandomSourceTrigger() {
    return dummyRandomSource;
  }

  @Override
  public void setRandomSource() {
    // no-op
  }

  @Override
  public int getNumSources() {
    return 1;
  }

  @Override
  public String toString() {
    return "rainbow";
  }
}
