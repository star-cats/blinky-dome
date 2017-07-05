package com.github.starcats.blinkydome.pattern.blinky_dome;

import com.github.starcats.blinkydome.model.BlinkyDome;
import com.github.starcats.blinkydome.pattern.AbstractFixtureSelectorPattern;
import heronarts.lx.LX;
import heronarts.lx.model.LXFixture;
import heronarts.lx.parameter.EnumParameter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by dlopuch on 6/28/17.
 */
public class BlinkyDomeFixtureSelectorPattern
    extends AbstractFixtureSelectorPattern<BlinkyDome, BlinkyDomeFixtureSelectorPattern.BlinkyDomeFixtureType>
{
  public enum BlinkyDomeFixtureType {
    LAYER,
    INDEX,
    TRIANGLE
  }

  public BlinkyDomeFixtureSelectorPattern(LX lx, BlinkyDome model) {
    super(lx, model);
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
    } else if (fixtureFamily == BlinkyDomeFixtureType.TRIANGLE) {
      keys = new LinkedHashSet<>();
      for (int i=0; i<model.allTriangles.size(); i++) {
        keys.add(i);
      }
    } else {
      throw new RuntimeException("Unsupported fixture type: " + fixtureFamily);
    }

    return keys.toArray();
  }

  @Override
  protected List<? extends LXFixture> getFixturesByKey(BlinkyDomeFixtureType fixtureFamily, Object keyObj) {
    Integer key = (Integer) keyObj;
    if (fixtureFamily == BlinkyDomeFixtureType.LAYER) {
      return model.getTrianglesByLayer(key);

    } else if (fixtureFamily == BlinkyDomeFixtureType.INDEX) {
      return model.getTrianglesByIndex(key);

    } else if (fixtureFamily == BlinkyDomeFixtureType.TRIANGLE) {
      return Collections.singletonList(model.allTriangles.get(key));

    } else {
      throw new RuntimeException("Unsupported fixture type: " + fixtureFamily);
    }
  }


}
