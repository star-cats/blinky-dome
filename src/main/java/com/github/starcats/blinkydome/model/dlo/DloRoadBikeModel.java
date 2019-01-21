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

public class DloRoadBikeModel extends LXModel {

  private static final int NUM_LEDS_PER_STRIP = 64; // fadecandy port

  // Coordinate system scale factor to get approximately in sync with other models
  private static final float ALPHA = 10;

  private static final float HEADLIGHT_SPIRAL_R = 0.5f * ALPHA;

  // Bike geometry: Define anchor points / vertices
  // x: Front to back (seat is about 0, front is +)
  // y: top to bottom (seat is about 0, pedals are -)
  // z: left to right (0 is plane of bike)

  // Rear bike geometry
  private static final LXVector leftSupportTop   = new LXVector(-1.5f,     -2, 1).mult(ALPHA);
  private static final LXVector leftSupportBase  = new LXVector(-12.5f,   -10, 3).mult(ALPHA);
  private static final LXVector rightSupportBase = new LXVector(-12.5f,   -10,-3).mult(ALPHA);
  private static final LXVector rightSupportTop  = new LXVector(0f,    -1.25f,-0.5f).mult(ALPHA);
  private static final LXVector rightSupportExt  = new LXVector(2.5f,  -0.5f,  0).mult(ALPHA);
  private static final LXVector seatPoleMiniStripT = new LXVector(0, -2, 0).mult(ALPHA);
  private static final LXVector seatPoleMiniStripB = new LXVector(0, -9, 0).mult(ALPHA);


  // Front bike geometry
  private static final LXVector seatpostStripH   = new LXVector(    1, -0.5f, 0).mult(ALPHA);
  private static final LXVector handlebarsTop    = new LXVector(20.5f,     1, 0).mult(ALPHA);
    // TODO: Should add intermediate points b/w seatpost and handlebars to account for 2 LED's cut out.
  private static final LXVector headlightSpiralC = handlebarsTop.add(HEADLIGHT_SPIRAL_R, 0, 0);
  private static final LXVector handlebarsBottom = new LXVector(20.5f, -1.5f, 0).mult(ALPHA);
  private static final LXVector strip1End        = new LXVector(12.5f,   -10, 0).mult(ALPHA);


  // Geometry LED counts
  // Rear bike LEDs
  private static final int BACK_LEFT_SUPPORT_NUM_LEDS = 20;
  private static final int BACK_RIGHT_SUPPORT_NUM_LEDS = 23;
  private static final int BACK_RIGHT_EXTENDER_NUM_LEDS = 4;
  private static final int SEAT_POLE_MINI_STRIP_NUM_LEDS = 11;

  // Front bike LEDs
  private static final int LEN_SEATPOST_HANDLEBARS = 28; // TODO: was 30, had to cut out 2. Should adjust geometry.
  private static final int HEADLIGHT_NUM_LEDS = 46 - LEN_SEATPOST_HANDLEBARS - 2;
  private static final int LEN_HANDLEBARS_PEDALS_HALF = NUM_LEDS_PER_STRIP - HEADLIGHT_NUM_LEDS - LEN_SEATPOST_HANDLEBARS - 2;


  // Class fixtures
  // Fixtures
  public final List<LXFixture> framePieces;

  public final LXFixture headlightSpiral;
  public final LXFixture leftRearSupport;
  public final LXFixture rightRearSupport;


//  private PatternRotator patternRotator;


  public static DloRoadBikeModel makeModel(boolean hasGui) {
    // If not gui, then Bike is running on raspi.  Get GPIO inputs.
//    if (!hasGui) {
//      RaspiGpio.init(outputProvider);
//    }

    List<LXFixture> allFixtures = new ArrayList<>();
    List<LXFixture> framePieces = new ArrayList<>();


    // STRIP 0 -- Front of bike
    // -----------
    LXFixture fixture = new VectorStripModel<>(seatpostStripH, handlebarsTop, VectorStripModel.GENERIC_POINT_FACTORY, LEN_SEATPOST_HANDLEBARS);
    allFixtures.add(fixture);
    framePieces.add(fixture);


    // "Headlight" spiral: make two loops of LEDs spiraling from handlebarsTop to handlebarsBottom
    List<LXPoint> headlightSpiralPoints = new ArrayList<>(HEADLIGHT_NUM_LEDS);
    float thetaInc = (float)Math.PI*4 / HEADLIGHT_NUM_LEDS; // Spiral is 2 loops, so 4*PI
    LXVector headlightSpiralRadial = new LXVector(-HEADLIGHT_SPIRAL_R, 0, 0); // will rotate in y axis
    float deltaY = (handlebarsBottom.y - handlebarsTop.y) / HEADLIGHT_NUM_LEDS;
    float y = headlightSpiralC.y;
    for (int i=0; i<HEADLIGHT_NUM_LEDS; i++) {
      LXVector led = headlightSpiralC.copy().add(headlightSpiralRadial);
      headlightSpiralPoints.add(new LXPoint(led.x, y, led.z));
      y += deltaY;
      headlightSpiralRadial.rotate(thetaInc, 0, 1, 0);
    }
    LXFixture headlightSpiral = () -> headlightSpiralPoints;
    allFixtures.add(headlightSpiral);


    fixture = new VectorStripModel<>(handlebarsBottom, strip1End, VectorStripModel.GENERIC_POINT_FACTORY, LEN_HANDLEBARS_PEDALS_HALF);
    allFixtures.add(fixture);
    framePieces.add(fixture);


    // STRIP 1 -- back of bike
    // ---------
    // Topmost seatpost loop: circle in Y-axis
//    List<LXPoint> seatpostLoopPoints = new ArrayList<>(SEATPOST_LOOP_NUM_LEDS);
//    thetaInc = (float)Math.PI * 2 / SEATPOST_LOOP_NUM_LEDS;
//    LXVector seatpostLoopRadial = new LXVector(SEATPOST_LOOP_R, 0, SEATPOST_LOOP_R); // will rotate in y axis
//    for (int i=0; i<SEATPOST_LOOP_NUM_LEDS; i++) {
//      LXVector led = seatpostLoopC.copy().add(seatpostLoopRadial);
//      seatpostLoopPoints.add(new LXPoint(led.x, led.y, led.z));
//      seatpostLoopRadial.rotate(thetaInc, 0, 1, 0);
//    }
//    LXFixture seatpostLoop = () -> seatpostLoopPoints; // getPoints() method implementation lambda.
//    allFixtures.add(seatpostLoop);
//
//
//    // Brake blob.  Model as another circle in the y-axis
//    List<LXPoint> brakeBlobPoints = new ArrayList<>(BRAKE_BLOB_NUM_LEDS);
//    thetaInc = (float)Math.PI * 2 / BRAKE_BLOB_NUM_LEDS;
//    LXVector brakeBlobRadial = new LXVector(BRAKE_BLOB_R, 0, 0); // will rotate in y axis
//    for (int i=0; i<BRAKE_BLOB_NUM_LEDS; i++) {
//      LXVector led = brakeBlobC.copy().add(brakeBlobRadial);
//      brakeBlobPoints.add(new LXPoint(led.x, led.y, led.z));
//      brakeBlobRadial.rotate(thetaInc, 0, 1, 0);
//    }
//    LXFixture brakeBlob = () -> brakeBlobPoints; // new fixtures just define a getPoints() method.  Lambda that fucker.
//    allFixtures.add(brakeBlob);
//
//
//    fixture = new VectorStripModel<>(seatpostStripV, pedals, VectorStripModel.GENERIC_POINT_FACTORY, LEN_SEATPOST_PEDALS);
//    allFixtures.add(fixture);
//    framePieces.add(fixture);
//
//    fixture = new VectorStripModel<>(pedals, strip0End, VectorStripModel.GENERIC_POINT_FACTORY, LEN_PEDALS_HANDLEBARS_HALF);
//    allFixtures.add(fixture);
//    List<LXPoint> pedalsToHandlebarsHalf = fixture.getPoints().stream().collect(Collectors.toList());
    // Don't add to framePieces because we'll join this into one piece below


    fixture = new VectorStripModel<>(leftSupportTop, leftSupportBase, VectorStripModel.GENERIC_POINT_FACTORY, BACK_LEFT_SUPPORT_NUM_LEDS);
    allFixtures.add(fixture);
    LXFixture leftRearSupport = fixture;

    fixture = new VectorStripModel<>(rightSupportBase, rightSupportTop, VectorStripModel.GENERIC_POINT_FACTORY, BACK_RIGHT_SUPPORT_NUM_LEDS);
    allFixtures.add(fixture);
    LXFixture rightRearSupport = fixture;

    fixture = new VectorStripModel<>(rightSupportTop, rightSupportExt, VectorStripModel.GENERIC_POINT_FACTORY, BACK_RIGHT_EXTENDER_NUM_LEDS);
    allFixtures.add(fixture);
    framePieces.add(fixture);

    fixture = new VectorStripModel<>(seatPoleMiniStripT, seatPoleMiniStripB, VectorStripModel.GENERIC_POINT_FACTORY, SEAT_POLE_MINI_STRIP_NUM_LEDS);
    allFixtures.add(fixture);
    framePieces.add(fixture);


    return new DloRoadBikeModel(allFixtures, framePieces, leftRearSupport, rightRearSupport, headlightSpiral, hasGui);
  }

  private DloRoadBikeModel(List<LXFixture> allFixtures, List<LXFixture> framePieces,
                           LXFixture leftRearSupport, LXFixture rightRearSupport, LXFixture headlightSpiral, boolean hasGui) {
    super(allFixtures.toArray(new LXFixture[allFixtures.size()]));

    System.out.println("Starting up with bike model");

    this.framePieces = framePieces;
    this.leftRearSupport = leftRearSupport;
    this.rightRearSupport = rightRearSupport;
    this.headlightSpiral = headlightSpiral;
  }

//  @Override
//  public DloRoadBikeModel initLx(LX lx) {
//    lx.engine.framesPerSecond.setValue(40); // higher on raspi and FFT seems to miss things.
//
//    this.patternRotator = new PatternRotator(lx);
//
//    return this;
//  }
//
//  @Override
//  public void addPatternsAndGo(LX lx, PApplet p, IcosaFFT icosaFft) {
//    LXPattern defaultPattern = new HazardStripesPattern(lx); //new PerlinNoisePattern(lx, p, icosaFft);
//
//    List<LXPattern> patterns = new ArrayList<>(Arrays.asList(
//        defaultPattern,
//        new PerlinNoisePattern(lx, p, icosaFft),
//        new RainbowPattern(lx),
//        new RainbowSpreadPattern(lx)
//    ));
//
//    if (hasGui) {
//      patterns.add(new LedSelectorPattern(lx));
//    }
//
//    lx.setPatterns(patterns.toArray(new LXPattern[patterns.size()]));
//    lx.goPattern(defaultPattern);
//  }


//  /**
//   * Adds RaspiGPIO listeners to rotate through patterns when the black moment is held down and the toggle gets flipped
//   */
//  private class PatternRotator {
//    private boolean isListeningForPatternToggle = false;
//    private LX lx;
//
//    public PatternRotator(LX lx) {
//      if (!RaspiGpio.isActive()) {
//        System.out.println("patternRotater NOT init'd -- no GPIO detected");
//        return;
//      }
//
//      this.lx = lx;
//
//      RaspiGpio.blackMoment.addListener(new GpioPinListenerDigital() {
//        @Override
//        public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
//          isListeningForPatternToggle = event.getState().isHigh();
//          System.out.println("isListeningForPatternToggle: " + isListeningForPatternToggle);
//        }
//      });
//
//      RaspiGpio.yellowMoment.addListener(new GpioPinListenerDigital() {
//        @Override
//        public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
//          if (!isListeningForPatternToggle || !event.getState().isHigh()) {
//            System.out.println("not changing pattern. isListening: " + isListeningForPatternToggle + "  or isHigh: " + event.getState().isHigh());
//            return;
//          }
//
//          lx.goNext();
//          System.out.println("changed pattern");
//        }
//      });
//
//      System.out.println("patternRotater init'd");
//    }
//  }
}
