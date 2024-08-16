package com.github.starcats.blinkydome.pattern;

import heronarts.lx.LX;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameterListener;

import java.util.Collections;
import java.util.List;

/**
 * Pattern that selects and colors fixtures on a model
 */
public abstract class AbstractFixtureSelectorPattern<M extends LXModel, E extends Enum> extends AbstractSimplePattern {

  protected M model;

  protected final EnumParameter<E> fixtureFamily;
  private final DiscreteParameter fixtureSelector;

  private E currentlySelectedFamily;

  private List<? extends LXFixture> currentFixtures = Collections.emptyList();

  protected AbstractFixtureSelectorPattern(LX lx, M model) {
    super(lx);

    this.model = model;

    fixtureFamily = makeFixtureFamilyParameter();
    if (fixtureFamily.getDescription() == null) {
      fixtureFamily.setDescription("Select the type of model fixtures to select");
    }
    addParameter(fixtureFamily);

    fixtureSelector = new DiscreteParameter("fixture", getFixtureKeysForFamily(fixtureFamily.getEnum()));
    fixtureSelector.setDescription("Select fixture(s)");
    addParameter(fixtureSelector);


    // Wire up action listeners
    LXParameterListener onFixtureSelectorChange = parameter -> updateFixtures();

    fixtureFamily.addListener(parameter -> {
      currentlySelectedFamily = ((EnumParameter<E>) parameter).getEnum();
      Object[] keys = getFixtureKeysForFamily(currentlySelectedFamily);
      fixtureSelector.setObjects(keys);
      fixtureSelector.setValue(keys[0]);

      onFixtureSelectorChange.onParameterChanged(fixtureSelector);
        // LX doesn't call listeners updates unless the value is different.  If toggling between families, the value
        // is always set to 0, so though it represents a different underlying object, LX thinks nothing has changed.
    });
    fixtureSelector.addListener(onFixtureSelectorChange);

    // Select defaults
    currentlySelectedFamily = fixtureFamily.getEnum();
    updateFixtures();
  }

  protected void updateFixtures() {
    currentFixtures = getFixturesByKey(currentlySelectedFamily, fixtureSelector.getObject());
  }

  public List<? extends LXFixture> getCurrentFixtures() {
    return this.currentFixtures;
  }

  public void run(double deltaMs) {
    for (LXPoint point : model.points) {
      setColor(point.index, LX.hsb(0, 0, 0));
    }

    for (LXFixture fixture : currentFixtures) {
      for (LXPoint point : fixture.getPoints()) {
        setColor(point.index, LX.rgb(255, 255, 255));
      }
    }
  }

  /**
   * After configureAgainst()'ing the appropriate model type, should provide an EnumParameter for selecting the appropriate fixture
   * types in the model
   */
  abstract protected EnumParameter<E> makeFixtureFamilyParameter();

  /**
   * Get the list of fixture selectors for a given family of fixtures
   * @return List of keys to select fixtures
   */
  abstract protected Object[] getFixtureKeysForFamily(E fixtureFamily);

  abstract protected List<? extends LXFixture> getFixturesByKey(E fixtureFamily, Object key);


}
