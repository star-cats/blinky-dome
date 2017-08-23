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
    LXVector nextHarnessInc = new LXVector(-LEN_SIDE, 0, 0);
    LXVector negNextHarnessInc = new LXVector(LEN_SIDE, 0, 0);
    LXVector up = new LXVector(0, 1, 0);
    LXVector triangleSpreadDirection = new LXVector(0, 0, 1);
    LXVector negTriangleSpreadDirection = new LXVector(0, 0, -1);

    Boolean FWD = true;
    Boolean AWAY = false;

    makeLineOfTriangles(
            triangles, new boolean[] {FWD, FWD, FWD, FWD},
            harnessStart, triangleSpreadDirection, up,
            0, 1, 0
    );

    harnessStart.add(nextHarnessInc);

    makeLineOfTriangles(
        triangles, new boolean[] {AWAY, FWD, AWAY, FWD},
        harnessStart, triangleSpreadDirection, up,
        0, 2, 0
    );

    harnessStart.add(nextHarnessInc);

    makeLineOfTriangles(
        triangles, new boolean[] {AWAY, FWD, AWAY, AWAY},
        harnessStart, triangleSpreadDirection, up,
        0, 3, 0
    );

    harnessStart.add(nextHarnessInc);

    makeLineOfTriangles(
            triangles, new boolean[] {AWAY, AWAY, AWAY, AWAY},
            harnessStart, triangleSpreadDirection, up,
            0, 4, 0
    );

    harnessStart.add(nextHarnessInc);

    makeLineOfTriangles(
            triangles, new boolean[] {AWAY, FWD, AWAY, FWD},
            harnessStart, triangleSpreadDirection, up,
            0, 5, 0
    );

    harnessStart.add(nextHarnessInc);

    makeLineOfTriangles(
            triangles, new boolean[] {AWAY, AWAY, AWAY, FWD},
            harnessStart, triangleSpreadDirection, up,
            0, 6, 0
    );


    // Start ad-hoc grid

    // 4 inline with grid but offset
    makeLineOfTriangles(
            triangles, new boolean[] {FWD, AWAY, FWD, AWAY},
            harnessStart.add(negNextHarnessInc).add(negNextHarnessInc), negTriangleSpreadDirection, up,
            1, 1, 0
    );

    // 3 inline with grid but offset
    makeLineOfTriangles(
            triangles, new boolean[] {FWD, AWAY, FWD},
            harnessStart.copy().add(negNextHarnessInc), negTriangleSpreadDirection, up,
            1, 4, 0
    );

    // 4 on floor along back
    makeLineOfTriangles(
            triangles, new boolean[] {FWD, FWD, FWD, FWD},
            new LXVector(0, 0, LEN_SIDE * 1.2f * 4f + LEN_SIDE).add(nextHarnessInc.copy().setMag(4 * nextHarnessInc.mag())),
            negNextHarnessInc, up,
            1, 2, 0
    );

    // 4 on windows
    makeLineOfTriangles(
            triangles, new boolean[] {FWD, FWD, FWD, FWD},
            new LXVector(0, 7 * 12, LEN_SIDE * 1.2f * 4f + LEN_SIDE).add(nextHarnessInc.copy().setMag(4 * nextHarnessInc.mag())),
            negNextHarnessInc, up,
            1, 5, 0
    );

    // 4 on loft
    makeLineOfTriangles(
            triangles, new boolean[] {FWD, AWAY, FWD, AWAY},
            harnessStart
                    .add( negTriangleSpreadDirection.copy().setMag(LEN_SIDE * 5f * 1.2f) )
                    .add( new LXVector(0, 7 * 12, 0) ),
            negTriangleSpreadDirection, up,
            1, 3, 0
    );


    return BlinkyModel.makeModel(triangles);

  }

  private static void makeLineOfTriangles(
      List<BlinkyTriangle> triangleList, boolean[] triangleConnectsFacingHarnessStart,
      LXVector start, LXVector direction, LXVector up,
      int ppGroup, int ppPort, int firstPpIndex
  ) {
    start = start.copy();
    LXVector incr = direction.copy().setMag( LEN_SIDE * 1.2f );
    for (int i=0; i<triangleConnectsFacingHarnessStart.length; i++) {
      BlinkyTriangle.V firstV = triangleConnectsFacingHarnessStart[i] ? BlinkyTriangle.V.V1 : BlinkyTriangle.V.V3;
      BlinkyTriangle.V secondV = triangleConnectsFacingHarnessStart[i] ? BlinkyTriangle.V.V3 : BlinkyTriangle.V.V1;
      triangleList.add(
          BlinkyTriangle.positionIn3DSpace(
              start, LEN_SIDE, 0,
              up, direction,
              firstV, secondV,
              ppGroup, ppPort, firstPpIndex, ppGroup * 8 + ppPort, i
          )
      );
      firstPpIndex += BlinkyTriangle.NUM_LEDS_PER_TRIANGLE;
      start.add(incr);
    }
  }
}
