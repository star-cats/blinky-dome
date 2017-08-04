package com.github.starcats.blinkydome.model.blinky_dome;

import com.github.starcats.blinkydome.pixelpusher.PixelPushableLED;
import com.github.starcats.blinkydome.pixelpusher.PixelPushableModel;
import heronarts.lx.model.LXModel;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;
import processing.data.Table;
import processing.data.TableRow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Model of StarCats dome
 */
public class BlinkyDome extends LXModel implements PixelPushableModel {
  /** Like LXModel.points, but up-cast to our LED class */
  public final List<BlinkyLED> leds;

  public final List<BlinkyTriangle> allTriangles;

  private final Map<Integer, List<BlinkyTriangle>> trianglesByGroup;

  /**
   * Factory to construct a new instance from CSV LED position map
   * @param p processing instance
   * @return new BlinkyDome instance
   */
  public static BlinkyDome makeModel(PApplet p) {

    Table triangleTable = p.loadTable("led-vertex-locations.csv", "header,csv");
    if (triangleTable == null) {
      throw new RuntimeException("Error: could not load LED position data");
    }

    List<BlinkyTriangle> allTriangles = new ArrayList<>(triangleTable.getRowCount());
    for (TableRow row : triangleTable.rows()) {

      // TODO: These are demo triangle mappings for a quick pixel pusher harness containing four 'random' triangles
      // Eventually these params should be specified in the CSV itself
      int domeGroup = row.getInt("index");
      int domeGroupIndex = row.getInt("sub_index");
      int ppGroup;
      int ppPort;
      int ppIndex;
      if (domeGroup == 1 && domeGroupIndex == 9) {
        ppGroup = 0;
        ppPort = 1;
        ppIndex = 0;

      } else if (domeGroup == 1 && domeGroupIndex == 5) {
        ppGroup = 0;
        ppPort = 1;
        ppIndex = BlinkyTriangle.NUM_LEDS_PER_TRIANGLE;

      } else if (domeGroup == 0 && domeGroupIndex == 1) {
        ppGroup = 0;
        ppPort = 1;
        ppIndex = 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE;

      } else if (domeGroup == 0 && domeGroupIndex == 9) {
        ppGroup = 0;
        ppPort = 1;
        ppIndex = 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE;

      } else {
        ppGroup = -1;
        ppPort = -1;
        ppIndex = -1;
      }
      // END TODO


      allTriangles.add(new BlinkyTriangle(
          // Note: y and z dimensions are switched in mapping...
          // "up/down" should be along y axis per processing 3D conventions, mapped as z.  So we swap.
          new LXVector( row.getFloat("vertex_1_x"), row.getFloat("vertex_1_z"), row.getFloat("vertex_1_y") ),
          new LXVector( row.getFloat("vertex_2_x"), row.getFloat("vertex_2_z"), row.getFloat("vertex_2_y") ),
          new LXVector( row.getFloat("vertex_3_x"), row.getFloat("vertex_3_z"), row.getFloat("vertex_3_y") ),
          ppGroup,
          ppPort,
          ppIndex,
          domeGroup,
          domeGroupIndex
      ));
    }

    Map<Integer, List<BlinkyTriangle>> trianglesByGroup = allTriangles.stream()
        .collect(Collectors.groupingBy(triangle -> triangle.domeGroup));

    // Separate LED's into a single flat list
    List<BlinkyLED> allLeds = allTriangles.stream()
        .map(BlinkyTriangle::getPointsTyped)

        .flatMap(Collection::stream)
        .collect(Collectors.toList());

    // Sort by globally unique order (probably like this already?)
    allLeds.sort(Comparator.comparingInt(blinkyLed -> blinkyLed.index));

    return new BlinkyDome(allLeds, allTriangles, trianglesByGroup, p);
  }


  /** Private constructor; use {@link #makeModel} factory */
  private BlinkyDome(List<BlinkyLED> allLEDs,
                     List<BlinkyTriangle> allTriangles,
                     Map<Integer, List<BlinkyTriangle>> trianglesByGroup,
                     PApplet p) {
    super(new ArrayList<>(allLEDs)); // dupe LXPoint-typed ArrayList needed for java generics type inference ಠ_ಠ

    this.leds = Collections.unmodifiableList(allLEDs);


    // Make fixture definitions immutable:

    this.allTriangles = Collections.unmodifiableList(allTriangles);


    Map<Integer, List<BlinkyTriangle>> immutableTrianglesByGroup = new HashMap<>(); // (the *values* will be immutable)
    trianglesByGroup.forEach(
        (index, triangles) -> immutableTrianglesByGroup.put(index, Collections.unmodifiableList(triangles))
    );
    this.trianglesByGroup = Collections.unmodifiableMap(immutableTrianglesByGroup);
  }

  public List<BlinkyTriangle> getTrianglesByGroup(Integer index) {
    return trianglesByGroup.get(index);
  }

  public Set<Integer> getTriangleGroupsKeys() {
    return trianglesByGroup.keySet();
  }

  @Override
  public List<? extends PixelPushableLED> getPpLeds() {
    return leds;
  }
}
