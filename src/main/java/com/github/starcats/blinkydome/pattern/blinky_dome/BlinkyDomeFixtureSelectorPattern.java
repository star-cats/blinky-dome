package com.github.starcats.blinkydome.pattern.blinky_dome;

import com.github.starcats.blinkydome.model.blinky_dome.BlinkyDome;
import com.github.starcats.blinkydome.model.blinky_dome.BlinkyTriangle;
import com.github.starcats.blinkydome.pattern.AbstractFixtureSelectorPattern;
import heronarts.lx.LX;
import heronarts.lx.model.LXFixture;
import heronarts.lx.parameter.EnumParameter;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by dlopuch on 6/28/17.
 */
public class BlinkyDomeFixtureSelectorPattern
    extends AbstractFixtureSelectorPattern<BlinkyDome, BlinkyDomeFixtureSelectorPattern.BlinkyDomeFixtureType>
{
  public enum BlinkyDomeFixtureType {
    GROUP,
    TRIANGLE
  }

  public BlinkyDomeFixtureSelectorPattern(LX lx, BlinkyDome model) {
    super(lx, model);
  }

  @Override
  protected EnumParameter<BlinkyDomeFixtureType> makeFixtureFamilyParameter() {
    return new EnumParameter<>("class", BlinkyDomeFixtureType.GROUP);
  }

  @Override
  protected Object[] getFixtureKeysForFamily(BlinkyDomeFixtureType fixtureFamily) {
    Set<Integer> keys;
    if (fixtureFamily == BlinkyDomeFixtureType.GROUP) {
      keys = model.getTriangleGroupsKeys();
    } else if (fixtureFamily == BlinkyDomeFixtureType.TRIANGLE) {
      keys = new HashSet<>();
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
    if (fixtureFamily == BlinkyDomeFixtureType.GROUP) {
      return model.getTrianglesByGroup(key);

    } else if (fixtureFamily == BlinkyDomeFixtureType.TRIANGLE) {
      BlinkyTriangle triangle = model.allTriangles.get(key);
      System.out.println("Selected triangle: " + triangle.toString());
      return Collections.singletonList(triangle);

    } else {
      throw new RuntimeException("Unsupported fixture type: " + fixtureFamily);
    }
  }


}
