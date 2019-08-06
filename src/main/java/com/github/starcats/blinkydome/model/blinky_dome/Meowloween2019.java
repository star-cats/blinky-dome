package com.github.starcats.blinkydome.model.blinky_dome;

import heronarts.lx.transform.LXVector;

import java.util.Arrays;

/**
 * Creates a {@link BlinkyModel} consisting of 4 triangles arranged in a simple "on the floor" geometry,
 * the geometry being interesting enough to show you how to use {@link BlinkyTriangle#positionIn3DSpace}
 */
public class Meowloween2019 {

  static final public float LEN_SIDE = 23; // lets call it inches
  static final private float DEG_30 = (float) Math.PI / 6.0f;
  static final private float DEG_60 = (float) Math.PI / 3.0f;
  static final private float DEG_90 = (float) Math.PI / 2.0f;
  static final private float DEG_120 = DEG_90 + DEG_30;
  static final private float DEG_150 = DEG_120 + DEG_30;
  static final private float DEG_180 = (float) Math.PI;


  public static BlinkyModel makeModel() {
    LXVector up = new LXVector(0, 1, 0);

    LXVector right = new LXVector(1, 0, 0);

    LXVector southWallLeft = new LXVector(0, 0, -1);
    LXVector southWallRight = new LXVector(0, 0, 1);

    int southWallX = -69;

    LXVector chrisRoomWallLeft = new LXVector(-1, 0, -1);
    LXVector chrisRoomWallRight = new LXVector(1, 0, 1);

    LXVector windowsRight = new LXVector(1, 0, 0);
    LXVector windowsLeft = new LXVector(-1, 0, 0);

    LXVector loftWindowRight = new LXVector(-1, 0, -1);
    LXVector loftWallRight = new LXVector(1, 0, -1);
    LXVector loftMirrorRight = new LXVector(-1, 0, -1);

    int PP_GROUP_SOUTH_WALL = 0;

    return BlinkyModel.makeModel(Arrays.asList(
            // South Wall Port 2
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(southWallX, 102, -33),
                    LEN_SIDE, DEG_90,
                    up, southWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    0, 2, 0
            ),
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(southWallX, 100, -36),
                    LEN_SIDE, -DEG_90,
                    up, southWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    0, 2, 1
            ),
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(southWallX, 98, -40),
                    LEN_SIDE, DEG_30,
                    up, southWallLeft,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    0, 2, 2
            ),
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(southWallX, 94, -35),
                    LEN_SIDE, DEG_90,
                    up, southWallLeft,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    0, 2, 3
            ),


            // Port 3: Chris room wall (from the left)
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(-69, 113, -8),
                    LEN_SIDE, DEG_150,
                    up, chrisRoomWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    0, 4, 2
            ),
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(-64, 113, -4),
                    LEN_SIDE, DEG_120,
                    up, chrisRoomWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    0, 4, 1
            ),
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(-58, 113, 2),
                    LEN_SIDE, DEG_30,
                    up, chrisRoomWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    0, 4, 0
            ),

            // Port 1: Chris room wall cont
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(-33, 97, 40),
                    LEN_SIDE, DEG_30,
                    up, chrisRoomWallLeft,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    0, 1, 0
            ),
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(-27, 92, 47),
                    LEN_SIDE, -DEG_90,
                    up, chrisRoomWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    0, 1, 1
            ),
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(-25, 87, 50),
                    LEN_SIDE, -DEG_90,
                    up, chrisRoomWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    0, 1, 2
            ),

            // PORT 4: Final
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(0, 87, 80),
                    LEN_SIDE, -DEG_90,
                    up, chrisRoomWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    0, 4, 0
            )


//            // South Wall 2
//            BlinkyTriangle.positionIn3DSpace(
//                    new LXVector(-117, 87, -27),
//                    LEN_SIDE, 0,
//                    up, southWallLeft,
//                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
//                    0, 1, BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
//            ),
//
//            // South Wall 3
//            BlinkyTriangle.positionIn3DSpace(
//                    new LXVector(-117, 87, -53),
//                    LEN_SIDE, -DEG_60,
//                    up, southWallRight,
//                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
//                    0, 1, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
//            ),
//
//            // South Wall 4
//            BlinkyTriangle.positionIn3DSpace(
//                    new LXVector(-117, 87, -58),
//                    LEN_SIDE, 0,
//                    up, southWallLeft,
//                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
//                    0, 1, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
//            ),
//
//
//            // ------------------
//            // West wall / windows
//
//            // windows 1
//            BlinkyTriangle.positionIn3DSpace(
//                    new LXVector(49, 90, 176),
//                    LEN_SIDE, - (float) Math.PI + DEG_60,
//                    up, southWallLeft,
//                    BlinkyTriangle.V.V3, BlinkyTriangle.V.V2,
//                    1, 1, 0 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
//            ),
//
//            BlinkyTriangle.positionIn3DSpace(
//                    new LXVector(39, 90, 206),
//                    LEN_SIDE, 0,
//                    up, windowsLeft,
//                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
//                    1, 1, 1 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
//            ),
//
//            BlinkyTriangle.positionIn3DSpace(
//                    new LXVector(-15, 90, 206),
//                    LEN_SIDE, 0,
//                    up, windowsRight,
//                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
//                    1, 1, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
//            ),
//
//            BlinkyTriangle.positionIn3DSpace(
//                    new LXVector(-15, 90, 206),
//                    LEN_SIDE, 0,
//                    up, windowsLeft,
//                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
//                    1, 1, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
//            ),
//
//
//            // ------------------
//            // Loft
//
//            BlinkyTriangle.positionIn3DSpace(
//                    new LXVector(33, 81, 8),
//                    LEN_SIDE, 0,
//                    up, loftWindowRight,
//                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
//                    1, 2, 0 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
//            ),
//
//            BlinkyTriangle.positionIn3DSpace(
//                    new LXVector(22, 95, -19),
//                    LEN_SIDE, -DEG_60,
//                    up, loftWallRight,
//                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
//                    1, 2, 1 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
//            ),
//
//            BlinkyTriangle.positionIn3DSpace(
//                    new LXVector(22, 95, -26),
//                    LEN_SIDE, 0,
//                    up, loftWallRight,
//                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
//                    1, 2, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
//            ),
//
//            BlinkyTriangle.positionIn3DSpace(
//                    new LXVector(33, 95, -60),
//                    LEN_SIDE, -DEG_60,
//                    up, loftMirrorRight,
//                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
//                    1, 2, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
//            )

    ));
  }

}
