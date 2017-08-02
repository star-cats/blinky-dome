package com.github.starcats.blinkydome.color;

import heronarts.lx.LX;
import heronarts.lx.LXComponent;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXNormalizedParameter;

/**
 * A grouping or {@link ColorMappingSourceClan} of {@link ImageColorSampler}'s
 */
public class ImageColorSamplerGroup extends LXComponent implements ColorMappingSourceClan {

  /** Selects which {@link ImageColorSampler} from the colorSamplers passed to constructor is active */
  public final DiscreteParameter samplerSelector;

  private final BooleanParameter shuffle = new BooleanParameter("shuffle")
      .setDescription("Select a random source from the currently-selected sampler")
      .setMode(BooleanParameter.Mode.MOMENTARY);

  private final BooleanParameter fullShuffle = new BooleanParameter("full shuffle")
      .setDescription("Select a random source across all groups")
      .setMode(BooleanParameter.Mode.MOMENTARY);

  private final ImageColorSampler[] colorSamplers;

  private final int totalNumSources;
  private final double[] weightsBySourceGroup;

  public ImageColorSamplerGroup(LX lx, String label, ImageColorSampler[] colorSamplers) {
    super(lx, label);
    this.colorSamplers = colorSamplers;

    this.samplerSelector = new DiscreteParameter("samplers", colorSamplers);
    this.samplerSelector.setDescription("Select which ImageColorSampler to use");
    addParameter(this.samplerSelector);

    this.shuffle.addListener(param -> {
      if (param.getValue() == 0) return;

      setRandomSourceInFamily();
    });
    addParameter(this.shuffle);

    this.fullShuffle.addListener(param -> {
      if (param.getValue() == 0) return;

      setRandomFamilyAndSource();
    });
    addParameter(this.fullShuffle);


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
  public ImageColorSampler[] getFamilies() {
    return colorSamplers;
  }

  @Override
  public DiscreteParameter getFamilySelect() {
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
  public BooleanParameter getRandomSourceTrigger() {
    return fullShuffle;
  }

  @Override
  public BooleanParameter getRandomSourceInFamilyTrigger() {
    return shuffle;
  }

  private void setRandomFamilyAndSource() {
    double rand = Math.random();
    int i=0;
    for (; i < weightsBySourceGroup.length - 1 /* stop 1 early so final incr lands on last */; i++) {
      if (rand < weightsBySourceGroup[i]) {
        break;
      }
    }
    samplerSelector.setValue(i);
    setRandomSourceInFamily();
  }

  private void setRandomSourceInFamily() {
    ((ImageColorSampler) samplerSelector.getObject()).setRandomSource();
  }

  @Override
  public int getNumSources() {
    return totalNumSources;
  }
}
