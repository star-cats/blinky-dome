package com.github.starcats.blinkydome.pattern.mask;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXFixture;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Masking pattern that selects random fixtures on a trigger
 */
public class Mask_RandomFixtureSelector extends LXPattern {
  public final BooleanParameter selectRandomFixturesTrigger;

  /** Param from 0-1 indicating probability each fixture should be selected on a trigger event */
  public final CompoundParameter probabilityToSelect;

  public final CompoundParameter brightness;


  private final List<LXFixture> allFixtures;
  private List<LXFixture> selectedFixtures;

  public Mask_RandomFixtureSelector(LX lx, List<? extends LXFixture> fixturesToSelect) {
    super(lx);

    selectRandomFixturesTrigger = new BooleanParameter("Trigger", false);
    selectRandomFixturesTrigger
        .setMode(BooleanParameter.Mode.MOMENTARY)
        .setDescription("Trigger random fixtures to be selected");
    addParameter(selectRandomFixturesTrigger);

    probabilityToSelect = new CompoundParameter("probability", 0.25, 0, 1);
    probabilityToSelect
        .setDescription("Probability that each fixture will be selected on a trigger event (ie 0.25 == 1/4 of all " +
            "fixtures will be selected on each trigger");
    addParameter(probabilityToSelect);

    brightness = new CompoundParameter("brightness", 100, 0, 100);
    brightness
        .setDescription("Brightness of mask (try modulating me!)");
    addParameter(brightness);

    allFixtures = new ArrayList<>(fixturesToSelect);


    // add trigger handler
    selectRandomFixturesTrigger.addListener(param -> {
      if (! (((BooleanParameter) param).getValueb())) {
        return;
      }

      selectedFixtures = allFixtures.stream()
          .filter(fixture -> Math.random() < probabilityToSelect.getValue())
          .collect(Collectors.toList());

      // TODO: remove if https://github.com/heronarts/LX/pull/9 gets accepted
      ((BooleanParameter) param).setValue(false); // Triggers don't change this back to low
    });
    selectRandomFixturesTrigger.setValue(true);
  }

  @Override
  protected void run(double deltaMs) {
    for (LXFixture fixture : allFixtures) {
      setColor(fixture, LXColor.BLACK);
    }

    for (LXFixture fixture : selectedFixtures) {
      setColor(fixture, LXColor.hsb(0, 0, brightness.getValue()));
    }

  }
}
