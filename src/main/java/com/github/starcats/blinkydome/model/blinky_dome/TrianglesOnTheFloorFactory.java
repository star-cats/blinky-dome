package com.github.starcats.blinkydome.model.blinky_dome;

import heronarts.lx.transform.LXVector;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates a {@link BlinkyModel} consisting of all the dome triangles arranged in lines on the floor.
 *
 * Used to QA all the harnesses.
 */
public class TrianglesOnTheFloorFactory {
  static final public float LEN_SIDE = 18; // inches

  public static BlinkyModel makeModel() {
    List<BlinkyTriangle> triangles = new ArrayList<>();

    LXVector harnessStart = new LXVector(0, 0, 0);
    LXVector nextHarnessInc = new LXVector(LEN_SIDE, 0, 0);
    LXVector up = new LXVector(0, 1, 0);
    LXVector triangleSpreadDirection = new LXVector(0, 0, -1);

    makeLineOfTriangles(
        triangles, 4,
        harnessStart, triangleSpreadDirection, up,
        0, 1, 0
    );

    harnessStart.add(nextHarnessInc);

    makeLineOfTriangles(
        triangles, 3,
        harnessStart, triangleSpreadDirection, up,
        0, 2, 0
    );

    harnessStart.add(nextHarnessInc);

    makeLineOfTriangles(
        triangles, 2,
        harnessStart, triangleSpreadDirection, up,
        0, 3, 0
    );

    return BlinkyModel.makeModel(triangles);

  }

  private static void makeLineOfTriangles(
      List<BlinkyTriangle> triangleList, int num,
      LXVector start, LXVector direction, LXVector up,
      int ppGroup, int ppPort, int firstPpIndex
  ) {
    start = start.copy();
    LXVector incr = direction.copy().setMag( LEN_SIDE * 1.2f );
    for (int i=0; i<num; i++) {
      triangleList.add(
          BlinkyTriangle.positionIn3DSpace(
              start, LEN_SIDE, 0,
              up, direction,
              BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
              ppGroup, ppPort, firstPpIndex, ppGroup * 8 + ppPort, i
          )
      );
      firstPpIndex += BlinkyTriangle.NUM_LEDS_PER_TRIANGLE;
      start.add(incr);
    }
  }
}
