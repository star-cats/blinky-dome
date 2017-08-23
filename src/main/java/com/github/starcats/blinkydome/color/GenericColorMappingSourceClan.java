package com.github.starcats.blinkydome.color;

import com.github.starcats.blinkydome.util.AudioDetector;
import ddf.minim.analysis.BeatDetect;
import heronarts.lx.LX;
import heronarts.lx.LXModulatorComponent;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXNormalizedParameter;

/**
 * A generic implementation of {@link ColorMappingSourceClan}
 *
 * Also supports changing to a random new source on a beat (see {@link #probabilityRandom})
 */
public class GenericColorMappingSourceClan extends LXModulatorComponent implements ColorMappingSourceClan {

  /** Selects which {@link ColorMappingSourceFamily} from the sources passed to constructor is active */
  public final DiscreteParameter familySelector;

  public final CompoundParameter probabilityRandom = (CompoundParameter) new CompoundParameter("p(rand)", 0.05, 0, 1)
      .setDescription("Probability we change to a random source on a beat (set to 0 to disable)")
      .setExponent(2);

  private final BooleanParameter shuffle = new BooleanParameter("shuffle")
      .setDescription("Select a random source from the currently-selected source family")
      .setMode(BooleanParameter.Mode.MOMENTARY);

  private final BooleanParameter fullShuffle = new BooleanParameter("full shuffle")
      .setDescription("Select a random source across all families")
      .setMode(BooleanParameter.Mode.MOMENTARY);

  private final ColorMappingSourceFamily[] cmsFamilies;

  private final int totalNumSources;
  private final double[] weightsBySourceFamily;

  private final BeatDetect beat;
  private double timeSinceLastRotate = 0;
  private final LX lx;

  public GenericColorMappingSourceClan(LX lx, String label, ColorMappingSourceFamily[] cmsFamilies, BeatDetect beat) {
    super(lx, label);
    this.cmsFamilies = cmsFamilies;

    this.beat = beat;
    this.lx = lx;
    lx.engine.addLoopTask(this);

    this.familySelector = new DiscreteParameter("families", cmsFamilies);
    this.familySelector.setDescription("Select which source family to use");
    addParameter(this.familySelector);

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


    // Generate weightsBySourceFamily, which will be used to evenly chose a random sourceGroup according to how many
    // sources they have available.
    int totalNumSources = 0;
    int[] numOptionsBySourceGroup = new int[cmsFamilies.length];
    int i=0;
    for (ColorMappingSourceFamily sourceFamily : cmsFamilies) {
      numOptionsBySourceGroup[i] = sourceFamily.getNumSources();
      totalNumSources += numOptionsBySourceGroup[i];
      i++;
    }
    this.totalNumSources = totalNumSources;
    totalNumSources = 0; // re-use
    double[] weightsBySourceFamily = new double[cmsFamilies.length];
    for (i=0; i< cmsFamilies.length; i++) {
      totalNumSources += numOptionsBySourceGroup[i];
      weightsBySourceFamily[i] = (double) totalNumSources / this.totalNumSources;
    }
    this.weightsBySourceFamily = weightsBySourceFamily;
  }

  @Override
  public void loop(double deltaMs) {
    super.loop(deltaMs);

    boolean doRotate;
    if (AudioDetector.LINE_IN.isRunning()) {
      doRotate = beat.isKick() && Math.random() < probabilityRandom.getValue();
    } else {
      // No audio?  Rotate probabilistically
      doRotate = Math.random() * 1_000_000 < timeSinceLastRotate;
    }
    if (doRotate) {
      this.setRandomFamilyAndSource();
      timeSinceLastRotate = 0;
    } else {
      timeSinceLastRotate += deltaMs;
    }
  }

  @Override
  public void dispose() {
    super.dispose();
    lx.engine.removeLoopTask(this);
  }

  @Override
  public ColorMappingSourceFamily[] getFamilies() {
    return cmsFamilies;
  }

  @Override
  public DiscreteParameter getFamilySelect() {
    return familySelector;
  }

  @Override
  public int getColor(double normalizedValue) {
    return ((ColorMappingSourceFamily) familySelector.getObject()).getColor(normalizedValue);
  }

  @Override
  public int getColor(LXNormalizedParameter normalizedParameter) {
    return getColor(normalizedParameter.getNormalized());
  }

  @Override
  public DiscreteParameter getSourceSelect() {
    return ((ColorMappingSourceFamily) familySelector.getObject()).getSourceSelect();
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
    for (; i < weightsBySourceFamily.length - 1 /* stop 1 early so final incr lands on last */; i++) {
      if (rand < weightsBySourceFamily[i]) {
        break;
      }
    }
    familySelector.setValue(i);
    setRandomSourceInFamily();
  }

  private void setRandomSourceInFamily() {
    ((ColorMappingSourceFamily) familySelector.getObject()).setRandomSource();
  }

  @Override
  public void setRandomSource() {
    this.setRandomFamilyAndSource();
  }

  @Override
  public int getNumSources() {
    return totalNumSources;
  }
}
