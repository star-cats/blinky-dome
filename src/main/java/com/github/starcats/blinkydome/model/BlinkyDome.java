package com.github.starcats.blinkydome.model;

import com.github.starcats.blinkydome.pattern.LayerTestPattern;
import com.github.starcats.blinkydome.pattern.RainbowPattern;
import com.github.starcats.blinkydome.pattern.RainbowZPattern;
import com.github.starcats.blinkydome.util.DeferredLxOutputProvider;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.model.LXAbstractFixture;
import processing.core.PApplet;
import processing.data.Table;
import processing.data.TableRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Model of StarCats dome
 */
public class BlinkyDome extends StarcatsLxModel {
  List<LED> leds;

  public static BlinkyDome makeModel(PApplet p, boolean hasGui, DeferredLxOutputProvider outputProvider) {
    // If not gui, then model is running headless on raspi.  Get GPIO inputs.
//    if (!hasGui) {
//      RaspiGpio.init(outputProvider);
//    }

    Table ledTable = p.loadTable("led-locations.csv", "header,csv");
    if (ledTable == null) {
      throw new RuntimeException("Error: could not load LED position data");
    }

    Table vertexTable = p.loadTable("vertex-locations.csv", "header,csv");
    if (vertexTable == null) {
      throw new RuntimeException("Error: could not load vertex locations");
    }

    List<LED> allLeds = new ArrayList<LED>();
    for (TableRow row : ledTable.rows()) {
      allLeds.add(new LED(row));
    }

    return new BlinkyDome(allLeds, hasGui);
  }

  private BlinkyDome(List<LED> allLEDs, boolean hasGui) {
    super(allLEDs, hasGui);
    this.leds = allLEDs;
  }

  public static class Fixture extends LXAbstractFixture {
    Fixture(List<LED> allLEDs) {
      allLEDs.forEach(this::addPoint);
    }
  }

  @Override
  public List<LXPattern> configPatterns(LX lx, PApplet p) {
    LXPattern defaultPattern = new RainbowZPattern(lx);

    List<LXPattern> patterns = new ArrayList<>(Arrays.asList(
        defaultPattern,
        new RainbowPattern(lx),
        new LayerTestPattern(lx)
        // Include other pattern implementations:
//        new PerlinNoisePattern(lx, p, icosaFft),
//        new RainbowSpreadPattern(lx)
    ));

    // Can also add GUI-specific patterns for non-headless modes only:
    if (hasGui) {
      // patterns.add(new LedSelectorPattern(lx));
    }

    return patterns;
  }
}
