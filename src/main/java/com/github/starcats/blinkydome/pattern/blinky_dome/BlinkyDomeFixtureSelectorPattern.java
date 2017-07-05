package com.github.starcats.blinkydome.pattern.blinky_dome;

import com.github.starcats.blinkydome.model.BlinkyDome;
import com.github.starcats.blinkydome.pattern.AbstractFixtureSelectorPattern;
import heronarts.lx.LX;
import heronarts.lx.model.LXFixture;
import heronarts.lx.parameter.EnumParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Created by dlopuch on 6/28/17.
 */
public class BlinkyDomeFixtureSelectorPattern
    extends AbstractFixtureSelectorPattern<BlinkyDomeFixtureSelectorPattern.BlinkyDomeFixtureType>
{
  public enum BlinkyDomeFixtureType {
    LAYER,
    INDEX
  }

  private BlinkyDome model;

  public BlinkyDomeFixtureSelectorPattern(LX lx) {
    super(lx);
  }

  @Override
  public void configureAgainst(BlinkyDome model) {
    this.model = model;
  }

  @Override
  protected EnumParameter<BlinkyDomeFixtureType> makeFixtureFamilyParameter() {
    return new EnumParameter<>("class", BlinkyDomeFixtureType.LAYER);
  }

  @Override
  protected Object[] getFixtureKeysForFamily(BlinkyDomeFixtureType fixtureFamily) {
    Set<Integer> keys;
    if (fixtureFamily == BlinkyDomeFixtureType.LAYER) {
      keys = model.getLayerKeys();
    } else if (fixtureFamily == BlinkyDomeFixtureType.INDEX) {
      keys = model.getTriangleIndexKeys();
    } else {
      throw new RuntimeException("Unsupported fixture type: " + fixtureFamily);
    }

    return keys.toArray();
  }

  @Override
  protected List<LXFixture> getFixturesByKey(BlinkyDomeFixtureType fixtureFamily, Object keyObj) {
    Integer key = (Integer) keyObj;
    if (fixtureFamily == BlinkyDomeFixtureType.LAYER) {
      return new ArrayList<>(model.getTrianglesByLayer(key));

    } else if (fixtureFamily == BlinkyDomeFixtureType.INDEX) {
      return new ArrayList<>(model.getTrianglesByIndex(key));

    } else {
      throw new RuntimeException("Unsupported fixture type: " + fixtureFamily);
    }
  }


}
