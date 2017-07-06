package com.github.starcats.blinkydome.model;

import com.github.starcats.blinkydome.model.util.ConnectedVectorStripModel;
import heronarts.lx.model.LXAbstractFixture;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.transform.LXVector;

import java.util.*;
import java.util.stream.Stream;


/**
 * DLo's backpack-mounted LED icosahedron
 */
public class Icosastar extends LXModel {
  public final List<ConnectedVectorStripModel> innerSpokeLeds;
  public final List<ConnectedVectorStripModel> outerSpokeLeds;
  public final List<ConnectedVectorStripModel> ring1Leds;

  public static final float a = 50;
  public static final float RING_R = a * (float)Math.cos(Math.toRadians(26.57));
  public static final float RING_H = a * (float)Math.sin(Math.toRadians(26.57));

  public static final int RING1_R = 150;
  public static final int RING2_R = (int)((float)RING1_R * 0.9);// 230;

  public static final int NUM_LEDS_PER_SPOKE = 6;

  // number of vertexes in one ring
  public static final int NUM_POINTS = 5;

  private static final float POINT_OFFSET_RAD = (float)(2 * Math.PI / NUM_POINTS);

  @FunctionalInterface
  private interface ThreeParamClosure <A, B, C> {
    void apply(A a, B b, C c);
  }

  private static class DummyFixture extends LXAbstractFixture { }

  public static Icosastar makeModel() {

    LXVector[] ring1Vs = new LXVector[NUM_POINTS];
    LXVector[] ring2Vs = new LXVector[NUM_POINTS];
    LXVector center = new LXVector(0, 0, 0);

    // LXFixtures:
    List<ConnectedVectorStripModel> innerSpokeLeds  = new ArrayList<>();
    List<ConnectedVectorStripModel> outerSpokeLeds = new ArrayList<>();
    List<ConnectedVectorStripModel> ring1Leds = new ArrayList<>();

    LXVector r1 = new LXVector(RING_R, 0, a - RING_H).rotate(POINT_OFFSET_RAD/2);
    LXVector r2 = new LXVector(RING_R, 0, a + RING_H);

    for (int i=0; i<NUM_POINTS; i++) {
      ring1Vs[i] = r1.copy();
      ring2Vs[i] = r2.copy();

      r1.rotate(POINT_OFFSET_RAD);
      r2.rotate(POINT_OFFSET_RAD);
    }

    // We want all spokes to know which other spokes they're connected to -- a graph of spokes
    // Do that by keeping track of connecting nodes, indexed by their vector's toString()
    final Map<String, List<ConnectedVectorStripModel>> connectingNodesByVector = new HashMap<>();


    // Here we build the VectorStripModels and the nodes that connect them together.
    ThreeParamClosure<List<ConnectedVectorStripModel>, LXVector, LXVector> addLedSegment = (toWhere, start, end) -> {
      if (!connectingNodesByVector.containsKey(start.toString())) {
        connectingNodesByVector.put(start.toString(), new ArrayList<>());
      }
      if (!connectingNodesByVector.containsKey(end.toString())) {
        connectingNodesByVector.put(end.toString(), new ArrayList<>());
      }

      toWhere.add(new ConnectedVectorStripModel(
          start, connectingNodesByVector.get(start.toString()),
          end, connectingNodesByVector.get(end.toString()),
          NUM_LEDS_PER_SPOKE
      ));
    };


    // Port 0: Segments lining top piece
    // --------------------

    addLedSegment.apply(innerSpokeLeds, ring1Vs[2], center);
    addLedSegment.apply(innerSpokeLeds, center, ring1Vs[3]);
    addLedSegment.apply(ring1Leds, ring1Vs[3], ring1Vs[2]);
    addLedSegment.apply(ring1Leds, ring1Vs[2], ring1Vs[1]);
    addLedSegment.apply(innerSpokeLeds, ring1Vs[1], center);
    addLedSegment.apply(innerSpokeLeds, center, ring1Vs[0]);
    // zero is furthest away from me

    addLedSegment.apply(ring1Leds, ring1Vs[0], ring1Vs[4]);
    addLedSegment.apply(innerSpokeLeds, ring1Vs[4], center);
    addLedSegment.apply(ring1Leds, ring1Vs[3], ring1Vs[4]);
    addLedSegment.apply(ring1Leds, ring1Vs[0], ring1Vs[1]);


    // That's 60 LED's.  Create 4 dummy ones to fill up the fadecandy port of 64 in the lx buffer.
    DummyFixture unusedLeds = new DummyFixture();
    unusedLeds.addPoint(new LXPoint(0, 0));
    unusedLeds.addPoint(new LXPoint(0, 0));
    unusedLeds.addPoint(new LXPoint(0, 0));
    unusedLeds.addPoint(new LXPoint(0, 0));


    // Fadecandy port 1: Segments lining equator triangles
    addLedSegment.apply(outerSpokeLeds, ring2Vs[0], ring1Vs[0]);
    addLedSegment.apply(outerSpokeLeds, ring1Vs[0], ring2Vs[1]);

    addLedSegment.apply(outerSpokeLeds, ring2Vs[1], ring1Vs[1]);
    addLedSegment.apply(outerSpokeLeds, ring1Vs[1], ring2Vs[2]);

    addLedSegment.apply(outerSpokeLeds, ring2Vs[2], ring1Vs[2]);
    addLedSegment.apply(outerSpokeLeds, ring1Vs[2], ring2Vs[3]);

    addLedSegment.apply(outerSpokeLeds, ring2Vs[3], ring1Vs[3]);
    addLedSegment.apply(outerSpokeLeds, ring1Vs[3], ring2Vs[4]);

    addLedSegment.apply(outerSpokeLeds, ring2Vs[4], ring1Vs[4]);
    addLedSegment.apply(outerSpokeLeds, ring1Vs[4], ring2Vs[0]);

    return new Icosastar(innerSpokeLeds, outerSpokeLeds, ring1Leds, unusedLeds);
  }

  protected Icosastar(
      List<ConnectedVectorStripModel> innerSpokeLeds,
      List<ConnectedVectorStripModel> outerSpokeLeds,
      List<ConnectedVectorStripModel> ring1Leds,
      LXFixture unusedLeds // LEDs in LX buffer we need to fill out fadecandy ports
  ) {
    super(
        Stream.concat(
            Stream.concat(innerSpokeLeds.stream(), outerSpokeLeds.stream()),
            Stream.concat(ring1Leds.stream(), Stream.of(unusedLeds))
        ).toArray(LXFixture[]::new)
    );
    System.out.println("Starting up with icosastar model");

    this.innerSpokeLeds = innerSpokeLeds;
    this.outerSpokeLeds = outerSpokeLeds;
    this.ring1Leds = ring1Leds;
  }
}