package com.github.starcats.blinkydome.model.blinky_dome;

import heronarts.lx.transform.LXVector;

import java.util.Arrays;

/**
 * Creates a {@link BlinkyModel} consisting of 4 triangles arranged in a simple "on the floor" geometry,
 * the geometry being interesting enough to show you how to use {@link BlinkyTriangle#positionIn3DSpace}
 */
public class TestHarnessFactory {

  static final public float LEN_SIDE = 18; // lets call it inches

  public static BlinkyModel makeModel() {
    LXVector up = new LXVector(0, 1, 0);
    LXVector right = new LXVector(1, 0, 0);

    return BlinkyModel.makeModel(Arrays.asList(
        BlinkyTriangle.positionIn3DSpace(
            new LXVector(0, 0, 0),
            LEN_SIDE, 0,
            up, right,
            true, 0, 1, 0, 0, 0
        ),

        // Overlapping 1st and 3rd, a bit forward
        BlinkyTriangle.positionIn3DSpace(
            new LXVector(LEN_SIDE * 0.8f, 0, -4),
            LEN_SIDE, 0,
            up, right,
            true, 0, 1, BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 1, 0
        ),

        // v1 is behind 2nd triangle and in line with 1st, but rotated forward a bit
        BlinkyTriangle.positionIn3DSpace(
            new LXVector(LEN_SIDE * 1.6f, 0, 0),
            LEN_SIDE, 0,
            up, new LXVector(1, 0, -1),
            true, 0, 1, 2 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 2, 0
        ),

        // Rotated 90 deg relative to first
        BlinkyTriangle.positionIn3DSpace(
            new LXVector(LEN_SIDE * 1.8f, 0, -LEN_SIDE*2/3),
            LEN_SIDE, 0,
            up, new LXVector(0, 0, -1),
            true, 0, 1, 3 * BlinkyTriangle.NUM_LEDS_PER_TRIANGLE, 3, 0
        )
    ));
  }

}
