package com.github.starcats.blinkydome.color;

import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXNormalizedParameter;

/**
 * A {@link ColorMappingSourceClan} of {@link ImageColorSampler}'s
 */
public class ImageColorSamplerClan implements ColorMappingSourceClan {

  private final DiscreteParameter samplerSelector;
  private final ImageColorSampler[] colorSamplers;

  private final int totalNumSources;
  private final double[] weightsBySourceGroup;

  public ImageColorSamplerClan(ImageColorSampler[] colorSamplers) {
    this.colorSamplers = colorSamplers;

    this.samplerSelector = new DiscreteParameter("samplers", colorSamplers);
    this.samplerSelector.setDescription("Select which ImageColorSampler to use");


    // Generate weightsBySourceGroup, which will be used to evenly chose a random sourceGroup according to how many
    // sources they have available.
    int totalNumSources = 0;
    int[] numOptionsBySourceGroup = new int[colorSamplers.length];
    int i=0;
    for (ImageColorSampler sourceGroup : colorSamplers) {
      numOptionsBySourceGroup[i] = sourceGroup.getNumSources();
      totalNumSources += numOptionsBySourceGroup[i];
      i++;
    }
    this.totalNumSources = totalNumSources;
    totalNumSources = 0; // re-use
    double[] weightsBySourceGroup = new double[colorSamplers.length];
    for (i=0; i<colorSamplers.length; i++) {
      totalNumSources += numOptionsBySourceGroup[i];
      weightsBySourceGroup[i] = (double) totalNumSources / this.totalNumSources;
    }
    this.weightsBySourceGroup = weightsBySourceGroup;
  }

  @Override
  public ImageColorSampler[] getGroups() {
    return colorSamplers;
  }

  @Override
  public DiscreteParameter getGroupSelect() {
    return samplerSelector;
  }

  @Override
  public int getColor(double normalizedValue) {
    return ((ImageColorSampler) samplerSelector.getObject()).getColor(normalizedValue);
  }

  @Override
  public int getColor(LXNormalizedParameter normalizedParameter) {
    return getColor(normalizedParameter.getNormalized());
  }

  @Override
  public DiscreteParameter getSourceSelect() {
    return ((ImageColorSampler) samplerSelector.getObject()).getSourceSelect();
  }

  @Override
  public void setRandomSource() {
    ((ImageColorSampler) samplerSelector.getObject()).setRandomSource();
  }

  @Override
  public void setRandomGroupAndSource() {
    double rand = Math.random();
    int i=0;
    for (; i < weightsBySourceGroup.length - 1 /* stop 1 early so final incr lands on last */; i++) {
      if (rand < weightsBySourceGroup[i]) {
        break;
      }
    }
    samplerSelector.setValue(i);
    setRandomSource();
  }

  @Override
  public int getNumSources() {
    return totalNumSources;
  }
}
