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
   *
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

      // TODO: These are demo triangle mappings for a quick pixel pusher harness
      // containing four 'random' triangles
      // Eventually these params should be specified in the CSV itself
      int domeGroup = row.getInt("domeGroup");
      int domeIndex = row.getInt("domeIndex");
      String spAddress = row.getString("spAddress");
      int spPort = row.getInt("spPort");
      int spFirstLedOffset = row.getInt("spFirstLedOffset");

      float vertex1x = row.getFloat("vertex_1_x");
      float vertex1z = row.getFloat("vertex_1_z");
      float vertex1y = row.getFloat("vertex_1_y");
      float vertex2x = row.getFloat("vertex_2_x");
      float vertex2z = row.getFloat("vertex_2_z");
      float vertex2y = row.getFloat("vertex_2_y");
      float vertex3x = row.getFloat("vertex_3_x");
      float vertex3z = row.getFloat("vertex_3_z");
      float vertex3y = row.getFloat("vertex_3_y");

      System.out.println(domeGroup + "\t" + domeIndex + "\t" + spAddress + "\t" + spPort + "\t" + spFirstLedOffset + "\t"
          + vertex1x + "\t" + vertex1z + "\t" + vertex1y + "\t" + vertex2x + "\t" + vertex2z + "\t" + vertex2y + "\t"
          + vertex3x + "\t" + vertex3z + "\t" + vertex3y);

      BlinkyTriangle newTriangle = new BlinkyTriangle(
          // Note: y and z dimensions are switched in mapping...
          // "up/down" should be along y axis per processing 3D conventions, mapped as z.
          // So we swap.
          // HEY DEVELOPER: If changing this, make same change in BlinkyDomeTriangleRotatorPattern::dumpNewCsv()!!!
          new LXVector(vertex1x, vertex1z, vertex1y), new LXVector(vertex2x, vertex2z, vertex2y),
          new LXVector(vertex3x, vertex3z, vertex3y), spAddress, spPort, spFirstLedOffset, domeGroup, domeIndex);

      allTriangles.add(newTriangle);
    }

    return BlinkyModel.makeModel(allTriangles);
  }
}
