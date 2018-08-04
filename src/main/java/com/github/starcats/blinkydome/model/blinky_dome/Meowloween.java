package com.github.starcats.blinkydome.model.blinky_dome;

import heronarts.lx.transform.LXVector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Creates a {@link BlinkyModel} consisting of 4 triangles arranged in a simple "on the floor" geometry,
 * the geometry being interesting enough to show you how to use {@link BlinkyTriangle#positionIn3DSpace}
 *
 *
 */
public class Meowloween {
  static final public float TRIANGLE_SIDE_LENGTH = 18;
  static final private float DEG_30 = (float) Math.PI / 6.0f;
  static final private float DEG_60 = (float) Math.PI / 3.0f;
  static final private float DEG_120 = 2 * DEG_60;
  static final private float DEG_180 = (float) Math.PI;

  private static final LXVector X_UNIT_VECTOR = new LXVector(1, 0, 0);
  private static final LXVector Y_UNIT_VECTOR = new LXVector(0, 1, 0);
  private static final LXVector Z_UNIT_VECTOR = new LXVector(0, 0, 1);

  enum DomeGroup {
    WEST_WALL_CLUSTER(0),
    SOUTH_WALL_RIGHT_CLUSTER(1),
    SOUTH_WALL_LEFT_CLUSTER(2),
    ;

    private final int domeGroup;

    DomeGroup(int domeGroup) {
      this.domeGroup = domeGroup;
    }

    public int getDomeGroup() {
      return domeGroup;
    }
  }

  public static BlinkyModel makeModel() {

    LXVector negativeYUnitVector = new LXVector(0, -1, 0);
    LXVector positiveYUnitVector = new LXVector(0, 1, 0);

    LXVector right = new LXVector(1, 0, 0);

    LXVector southWallLeft = new LXVector(0, 0, -1);
    LXVector southWallRight = new LXVector(0, 0, 1);

    LXVector windowsRight = new LXVector(1, 0, 0);
    LXVector windowsLeft = new LXVector(-1, 0, 0);

    LXVector loftWindowRight = new LXVector(-1, 0, -1);
    LXVector loftWallRight = new LXVector(1, 0, -1);
    LXVector loftMirrorRight = new LXVector(-1, 0, -1);

    LXVector chrisRoomDoorWall = new LXVector(1, 1, 0);
    LXVector chrisRoomDoorWallB = new LXVector(0, 1, 1);

    ArrayList<BlinkyTriangle> triangles = new ArrayList<>();

    /**
     * South Wall
     * Axis: Z
     *
     * This is the big wall with the blacklight crap on it.
     */
    int southWallX = -117;
    int southWallY = 87;

    // Hour glass shape
    // TOP hour glass
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(southWallX, southWallY, -129),
        TRIANGLE_SIDE_LENGTH, DEG_120,
        negativeYUnitVector, southWallLeft,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
        1, 3, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.SOUTH_WALL_LEFT_CLUSTER.getDomeGroup(), 0
    ));
    // bottom hour glass
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(southWallX, southWallY, -129),
        TRIANGLE_SIDE_LENGTH, DEG_180 + DEG_120,
        negativeYUnitVector, southWallLeft,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
        1, 2, 1 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.SOUTH_WALL_LEFT_CLUSTER.getDomeGroup(), 1
    ));

    // Touching bottom hour glass
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(southWallX, southWallY - 31, -129 + 16),
        TRIANGLE_SIDE_LENGTH, DEG_120,
        negativeYUnitVector, southWallLeft,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
        1, 3, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.SOUTH_WALL_LEFT_CLUSTER.getDomeGroup(), 2
    ));
    // Butted up against the one touching the bottom hour glass
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(southWallX, southWallY - 31, -129 + 16),
        TRIANGLE_SIDE_LENGTH, DEG_180,
        negativeYUnitVector, southWallLeft,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
        1, 3, 1 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.SOUTH_WALL_LEFT_CLUSTER.getDomeGroup(), 3
    ));

    // Top of the three big triangles
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(southWallX, southWallY - 16, (int)(-129 + (2.5 * TRIANGLE_SIDE_LENGTH))),
        TRIANGLE_SIDE_LENGTH, DEG_180 + DEG_120,
        negativeYUnitVector, southWallLeft,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
        1, 3, 0 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.SOUTH_WALL_LEFT_CLUSTER.getDomeGroup(), 4
    ));

    // Left bottom big triangle
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(southWallX, southWallY - 49, -129 + 26),
        TRIANGLE_SIDE_LENGTH, DEG_180,
        negativeYUnitVector, southWallLeft,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
        1, 1, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.SOUTH_WALL_LEFT_CLUSTER.getDomeGroup(), 5
    ));

    // Right bottom big triangle
    triangles.add(BlinkyTriangle.positionIn3DSpace(
            new LXVector(southWallX, southWallY - 49, -129 + 64),
            TRIANGLE_SIDE_LENGTH, DEG_180+DEG_60+DEG_180,
            negativeYUnitVector, southWallLeft,
            BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
            1, 2, 0 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
            DomeGroup.SOUTH_WALL_LEFT_CLUSTER.getDomeGroup(), 6
    ));

    // same wall as chris' room door
    triangles.add(BlinkyTriangle.positionIn3DSpace(
            new LXVector(southWallX, southWallY + 15, -129 + 115),
            TRIANGLE_SIDE_LENGTH, DEG_180+DEG_60,
            loftWindowRight, loftMirrorRight,
            BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
            0, 2, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
            DomeGroup.SOUTH_WALL_LEFT_CLUSTER.getDomeGroup(), 7
    ));

//    // same wall as chris' room door
//    triangles.add(BlinkyTriangle.positionIn3DSpace(
//            new LXVector(southWallX, southWallY - 10, -129 + 84),
//            TRIANGLE_SIDE_LENGTH, DEG_180+DEG_60,
//            loftMirrorRight, loftWallRight,
//            BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
//            0, 2, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
//            DomeGroup.SOUTH_WALL_LEFT_CLUSTER.getDomeGroup(), 8
//    ));



    triangles.addAll(getHazardSignShape(new LXVector(southWallX, southWallY, -19),
        new LXVector(-1, 0, 0), DomeGroup.SOUTH_WALL_RIGHT_CLUSTER, 1, 1));

    /*
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(southWallX, southWallY, -19),
        TRIANGLE_SIDE_LENGTH, 0,
        negativeYUnitVector, southWallRight,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
        0, 1, 0,
        DomeGroup.SOUTH_WALL_RIGHT_CLUSTER.getDomeGroup(), 0
    ));
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(southWallX, southWallY, -19),
        TRIANGLE_SIDE_LENGTH, -DEG_60,
        Y_UNIT_VECTOR, southWallRight,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
        0, 1, 0,
        DomeGroup.SOUTH_WALL_RIGHT_CLUSTER.getDomeGroup(), 0
    ));
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(southWallX, southWallY, -27),
        TRIANGLE_SIDE_LENGTH, 0,
        Y_UNIT_VECTOR, southWallLeft,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
        0, 1, BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.SOUTH_WALL_RIGHT_CLUSTER.getDomeGroup(), 1
    ));
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(southWallX, southWallY, -53),
        TRIANGLE_SIDE_LENGTH, -DEG_60,
        Y_UNIT_VECTOR, southWallRight,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
        0, 1, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.SOUTH_WALL_RIGHT_CLUSTER.getDomeGroup(), 2
    ));
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(southWallX, southWallY, -58),
        TRIANGLE_SIDE_LENGTH, 0,
        Y_UNIT_VECTOR, southWallLeft,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
        0, 1, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.SOUTH_WALL_RIGHT_CLUSTER.getDomeGroup(), 3
    ));
    */


            // ------------------
            // West wall / windows

            /*
            // windows 1
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(49, 90, 176),
                TRIANGLE_SIDE_LENGTH, - (float) Math.PI + DEG_60,
                    Y_UNIT_VECTOR, southWallLeft,
                    BlinkyTriangle.V.V3, BlinkyTriangle.V.V2,
                    1, 1, 0 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),
            */

    /**
     * Name: West Wall.
     * Axis: X
     *
     * This is the back nook area in Dore, by the fire escape door.
     */
    int westWallSmallestX = -15;
    int westWallY = 90;
    int westWallZ = 206;

    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(westWallSmallestX + (TRIANGLE_SIDE_LENGTH * 2), westWallY, westWallZ),
        TRIANGLE_SIDE_LENGTH, -DEG_60,
        Y_UNIT_VECTOR, windowsLeft,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
        0, 6, 0 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.WEST_WALL_CLUSTER.getDomeGroup(), 0
    ));
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(westWallSmallestX + (TRIANGLE_SIDE_LENGTH * 2) + 6, westWallY, westWallZ),
        TRIANGLE_SIDE_LENGTH, 0,
        Y_UNIT_VECTOR, windowsRight,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
        0, 6, 1 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.WEST_WALL_CLUSTER.getDomeGroup(), 1
    ));
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(westWallSmallestX + 6, westWallY, westWallZ),
        TRIANGLE_SIDE_LENGTH, 0,
        Y_UNIT_VECTOR, windowsRight,
        BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
        0, 6, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.WEST_WALL_CLUSTER.getDomeGroup(), 2
    ));
    triangles.add(BlinkyTriangle.positionIn3DSpace(
        new LXVector(westWallSmallestX, westWallY, westWallZ),
        TRIANGLE_SIDE_LENGTH, -DEG_60,
        Y_UNIT_VECTOR, windowsLeft,
        BlinkyTriangle.V.V3, BlinkyTriangle.V.V1,
        0, 6, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
        DomeGroup.WEST_WALL_CLUSTER.getDomeGroup(), 3
    ));
            /*

            // ------------------
            // Loft

            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(33, 81, 8),
                TRIANGLE_SIDE_LENGTH, 0,
                    Y_UNIT_VECTOR, loftWindowRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    1, 2, 0 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),

            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(22, 95, -19),
                TRIANGLE_SIDE_LENGTH, -DEG_60,
                    Y_UNIT_VECTOR, loftWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    1, 2, 1 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),

            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(22, 95, -26),
                TRIANGLE_SIDE_LENGTH, 0,
                    Y_UNIT_VECTOR, loftWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    1, 2, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),

            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(33, 95, -60),
                TRIANGLE_SIDE_LENGTH, -DEG_60,
                    Y_UNIT_VECTOR, loftMirrorRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    1, 2, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            )*/

    return BlinkyModel.makeModel(triangles);
  }

  static List<BlinkyTriangle> getHazardSignShape(LXVector centerPoint, LXVector axisToRotateAround, DomeGroup domeGroup, int ppGroup, int ppPort) {
    LXVector rightVector = axisToRotateAround.cross(Y_UNIT_VECTOR);

    return Arrays.asList(
        BlinkyTriangle.positionIn3DSpace(
            centerPoint,
            TRIANGLE_SIDE_LENGTH, DEG_60 * 3,
            Y_UNIT_VECTOR, rightVector,
            BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
            ppGroup, ppPort, 0 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
            domeGroup.getDomeGroup(), 0
        ),
        BlinkyTriangle.positionIn3DSpace(
            centerPoint,
            TRIANGLE_SIDE_LENGTH, -DEG_60,
            Y_UNIT_VECTOR, rightVector,
            BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
            ppGroup, ppPort, 1 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
            domeGroup.getDomeGroup(), 1
        ),
        BlinkyTriangle.positionIn3DSpace(
            centerPoint,
            TRIANGLE_SIDE_LENGTH, DEG_60,
            Y_UNIT_VECTOR, rightVector,
            BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
            ppGroup, ppPort, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE,
            domeGroup.getDomeGroup(), 2
        )
        );
  }
}
