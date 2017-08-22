package com.github.starcats.blinkydome.model.dlo;


import com.github.starcats.blinkydome.model.util.VectorStripModel;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.transform.LXVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DLoPlayaBikeModel extends LXModel {

  private static final int NUM_LEDS_PER_STRIP = 64; // fadecandy port

  public final List<LXFixture> framePieces;
  public final LXFixture brakeBlob;

  // Coordinate system scale factor to get approximately in sync with other models
  private static final float ALPHA = 10;

  // Bike geometry
  private static final LXVector seatpostStripA = new LXVector(  6,    9, 0).mult(ALPHA);
  private static final LXVector seatpostStripB = new LXVector(  7,    9, 0).mult(ALPHA);
  private static final LXVector brakeBlobC     = new LXVector(  8,    8, 0).mult(ALPHA);
  private static final LXVector handlebars     = new LXVector(-17,    9, 0).mult(ALPHA);
  private static final LXVector stripsJoin     = new LXVector( -7, 1.5f, 0).mult(ALPHA);
  private static final LXVector pedals         = new LXVector(  2,   -6, 0).mult(ALPHA);

  private static final float BRAKE_BLOB_R = 1 * ALPHA;

  // strip 0
  private static final int BRAKE_BLOB_NUM_LEDS = 19;
  private static final int LEN_SEATPOST_PEDALS = 46 - BRAKE_BLOB_NUM_LEDS;
  private static final int LEN_PEDALS_HANDLEBARS_HALF = NUM_LEDS_PER_STRIP - LEN_SEATPOST_PEDALS - BRAKE_BLOB_NUM_LEDS;

  // strip 1
  private static final int LEN_SEATPOST_HANDLEBARS = 40;
  private static final int LEN_HANDLEBARS_PEDALS_HALF = NUM_LEDS_PER_STRIP - LEN_SEATPOST_HANDLEBARS;

  public static DLoPlayaBikeModel makeModel(boolean hasGui) {
    // If not gui, then Bike is running on raspi.  Get GPIO inputs.
//    if (!hasGui) {
//      RaspiGpio.init(outputProvider);
//    }

    List<LXFixture> allFixtures = new ArrayList<>();
    List<LXFixture> framePieces = new ArrayList<>();

    // Create the brake blob.  Model as a circle in the Z-axis
    List<LXPoint> brakeBlobPoints = new ArrayList<>(BRAKE_BLOB_NUM_LEDS);
    float thetaInc = (float)Math.PI * 2 / BRAKE_BLOB_NUM_LEDS;
    LXVector brakeBlobRadial = new LXVector(0, BRAKE_BLOB_R, 0); // will rotate in z axis
    for (int i=0; i<BRAKE_BLOB_NUM_LEDS; i++) {
      LXVector brakeBlobLed = brakeBlobC.copy().add(brakeBlobRadial);
      brakeBlobPoints.add(new LXPoint(brakeBlobLed.x, brakeBlobLed.y, brakeBlobLed.z));
      brakeBlobRadial.rotate(thetaInc, 1, 0, 0);
    }
    LXFixture brakeBlob = () -> brakeBlobPoints; // new fixtures just define a getPoints() method.  Lambda that fucker.
    allFixtures.add(brakeBlob);


    LXFixture fixture = new VectorStripModel<>(seatpostStripB, pedals, VectorStripModel.GENERIC_POINT_FACTORY, LEN_SEATPOST_PEDALS);
    allFixtures.add(fixture);
    framePieces.add(fixture);

    fixture = new VectorStripModel<>(pedals, stripsJoin,VectorStripModel.GENERIC_POINT_FACTORY, LEN_PEDALS_HANDLEBARS_HALF);
    allFixtures.add(fixture);
    List<LXPoint> pedalsToHandlebarsHalf = new ArrayList<>(fixture.getPoints());
    // Don't add to framePieces because we'll join this into one piece below


    fixture = new VectorStripModel<>(seatpostStripA, handlebars, VectorStripModel.GENERIC_POINT_FACTORY, LEN_SEATPOST_HANDLEBARS);
    allFixtures.add(fixture);
    framePieces.add(fixture);

    fixture = new VectorStripModel<>(handlebars, stripsJoin, VectorStripModel.GENERIC_POINT_FACTORY, LEN_HANDLEBARS_PEDALS_HALF);
    allFixtures.add(fixture);
    List<LXPoint> handlebarsToPedalHalf = new ArrayList<>(fixture.getPoints());
    // Don't add to framePieces because we'll join this into one piece below


    // Join the two pedals<-->handlebars halves into one fixture
    List<LXPoint> handlesbarsToPedalsSecondHalf = new ArrayList<>(pedalsToHandlebarsHalf);
    Collections.reverse(handlesbarsToPedalsSecondHalf);
    List<LXPoint> handlebarsToPedals = Stream.concat(
        handlebarsToPedalHalf.stream(),
        handlesbarsToPedalsSecondHalf.stream()
    ).collect(Collectors.toList());
    framePieces.add(() -> handlebarsToPedals); // lambda-ify new LXFixture()

    return new DLoPlayaBikeModel(allFixtures, framePieces, brakeBlob);
  }

  private DLoPlayaBikeModel(List<LXFixture> allFixtures, List<LXFixture> framePieces, LXFixture brakeBlob) {
    super(allFixtures.toArray(new LXFixture[allFixtures.size()]));

    System.out.println("Starting up with bike model");

    this.framePieces = framePieces;
    this.brakeBlob = brakeBlob;
  }
}
