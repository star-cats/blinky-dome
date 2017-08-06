package com.github.starcats.blinkydome.model.blinky_dome;

import heronarts.lx.transform.LXVector;

import java.util.Arrays;

/**
 * Creates a {@link BlinkyModel} consisting of 4 triangles arranged in a simple "on the floor" geometry,
 * the geometry being interesting enough to show you how to use {@link BlinkyTriangle#positionIn3DSpace}
 */
public class Meowloween {

  static final public float LEN_SIDE = 18; // lets call it inches
  static final private float DEG_30 = (float) Math.PI / 6.0f;
  static final private float DEG_60 = (float) Math.PI / 3.0f;
  static final private float DEG_180 = (float) Math.PI;


  public static BlinkyModel makeModel() {
    LXVector up = new LXVector(0, 1, 0);

    LXVector right = new LXVector(1, 0, 0);

    LXVector southWallLeft = new LXVector(0, 0, -1);
    LXVector southWallRight = new LXVector(0, 0, 1);

    LXVector windowsRight = new LXVector(1, 0, 0);
    LXVector windowsLeft = new LXVector(-1, 0, 0);

    LXVector loftWindowRight = new LXVector(-1, 0, -1);
    LXVector loftWallRight = new LXVector(1, 0, -1);
    LXVector loftMirrorRight = new LXVector(-1, 0, -1);

    return BlinkyModel.makeModel(Arrays.asList(
            // South Wall 1
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(-117, 87, -19),
                    LEN_SIDE, -DEG_60,
                    up, southWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    0, 1, 0, 0, 0
            ),

            // South Wall 2
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(-117, 87, -27),
                    LEN_SIDE, 0,
                    up, southWallLeft,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    0, 1, BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),

            // South Wall 3
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(-117, 87, -53),
                    LEN_SIDE, -DEG_60,
                    up, southWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    0, 1, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),

            // South Wall 4
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(-117, 87, -58),
                    LEN_SIDE, 0,
                    up, southWallLeft,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    0, 1, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),


            // ------------------
            // West wall / windows

            // windows 1
            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(49, 90, 176),
                    LEN_SIDE, - (float) Math.PI + DEG_60,
                    up, southWallLeft,
                    BlinkyTriangle.V.V3, BlinkyTriangle.V.V2,
                    1, 1, 0 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),

            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(39, 90, 206),
                    LEN_SIDE, 0,
                    up, windowsLeft,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    1, 1, 1 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),

            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(-15, 90, 206),
                    LEN_SIDE, 0,
                    up, windowsRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    1, 1, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),

            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(-15, 90, 206),
                    LEN_SIDE, 0,
                    up, windowsLeft,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    1, 1, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),


            // ------------------
            // Loft

            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(33, 81, 8),
                    LEN_SIDE, 0,
                    up, loftWindowRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    1, 2, 0 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),

            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(22, 95, -19),
                    LEN_SIDE, -DEG_60,
                    up, loftWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    1, 2, 1 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),

            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(22, 95, -26),
                    LEN_SIDE, 0,
                    up, loftWallRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V3,
                    1, 2, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            ),

            BlinkyTriangle.positionIn3DSpace(
                    new LXVector(33, 95, -60),
                    LEN_SIDE, -DEG_60,
                    up, loftMirrorRight,
                    BlinkyTriangle.V.V1, BlinkyTriangle.V.V2,
                    1, 2, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 0, 0
            )

    ));
  }

}
