package com.github.starcats.blinkydome.model.blinky_dome;

import heronarts.lx.transform.LXVector;
import processing.core.PApplet;
import processing.data.Table;
import processing.data.TableRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Model of StarCats dome
 */
public class BlinkyDomeFactory {

  /**
   * Factory to construct a new instance from CSV LED position map
   * @param p processing instance
   * @return new BlinkyModel instance
   */
  public static BlinkyModel makeModel(PApplet p) {

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

    return BlinkyModel.makeModel(allTriangles);
  }
}
