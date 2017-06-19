package com.github.starcats.blinkydome.model;

import com.github.starcats.blinkydome.pattern.RainbowPattern;
import com.github.starcats.blinkydome.util.DeferredLxOutputProvider;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.model.LXAbstractFixture;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXPoint;
import processing.core.PApplet;
import processing.data.Table;
import processing.data.TableRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Model of StarCats dome
 */
public class BlinkyDome extends StarcatsLxModel {

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

    LXAbstractFixture allLeds = new LXAbstractFixture() { };
    for (TableRow row : ledTable.rows()) {
      allLeds.addPoint(new DomeLedFromTableEntry(row));
    }

    return new BlinkyDome(Collections.singletonList(allLeds), hasGui);
  }

  private static class DomeLedFromTableEntry extends LXPoint {
    final int triangleIndex;
    final int triangleSubindex;
    final int ledIndex;

    private DomeLedFromTableEntry(TableRow row) {
      super(row.getFloat("x"), row.getFloat("z"), -row.getFloat("y"));
      this.triangleIndex = row.getInt("index");
      this.triangleSubindex = row.getInt("sub_index");
      this.ledIndex = row.getInt("led_num");
    }
  }

  private BlinkyDome(List<LXFixture> allFixtures, boolean hasGui) {
    super(allFixtures.toArray(new LXFixture[allFixtures.size()]), hasGui);
  }

  @Override
  public List<LXPattern> configPatterns(LX lx, PApplet p) {
    LXPattern defaultPattern = new RainbowPattern(lx);

    List<LXPattern> patterns = new ArrayList<>(Arrays.asList(
        defaultPattern
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
