package com.github.starcats.blinkydome.pattern.fibonocci_petals;

import com.github.starcats.blinkydome.model.FibonocciPetalsModel;
import com.github.starcats.blinkydome.pattern.mask.Mask_RandomFixtureSelector;
import heronarts.lx.LX;
import heronarts.lx.model.LXFixture;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameterListener;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 */
public class Mask_RandomPetalsSelector extends Mask_RandomFixtureSelector {

  private final FibonocciPetalsModel model;

  private List<? extends LXFixture> allFixtures = Collections.emptyList();

  public enum FixtureType {
    SPIRALS,
    PETALS,
    CW_PTS,
    CCW_PTS
  };

  public Mask_RandomPetalsSelector(LX lx, FibonocciPetalsModel model) {
    super(lx, Collections.emptyList());

    this.model = model;

    EnumParameter<FixtureType> fixtureTypeSelector = new EnumParameter<>("Type", FixtureType.SPIRALS);
    LXParameterListener onSetFixtureType = param -> {
      FixtureType value = ((EnumParameter<FixtureType>) param).getEnum();
      if (value == FixtureType.SPIRALS) {
        allFixtures = model.cwSpirals;
      } else if (value == FixtureType.PETALS) {
        allFixtures = model.allPetals;
      } else if (value == FixtureType.CW_PTS) {
        allFixtures = model.allPetals.stream().map(p -> p.getCwSide()).collect(Collectors.toList());
      } else if (value == FixtureType.CCW_PTS) {
        allFixtures = model.allPetals.stream().map(p -> p.getCcwSide()).collect(Collectors.toList());
      }
    };
    fixtureTypeSelector
        .setDescription("Select the type of random fixtures to select")
        .addListener(onSetFixtureType);
    addParameter(fixtureTypeSelector);

    // trigger the listeners
    onSetFixtureType.onParameterChanged(fixtureTypeSelector);
    selectRandomFixturesTrigger.setValue(true);
    selectRandomFixturesTrigger.setValue(false);
  }

  @Override
  public List<? extends LXFixture> getAllFixtures() {
    return allFixtures;
  }
}
