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

  private static final float SEATPOST_LOOP_R = 0.5f * ALPHA;
  private static final float BRAKE_BLOB_R = 1.5f * ALPHA;
  private static final float HEADLIGHT_SPIRAL_R = 0.5f * ALPHA;

  // Bike geometry: Define anchor points / vertices
  // Strip 0:
  private static final LXVector seatpostLoopC    = new LXVector(    0,      1, 0).mult(ALPHA);
  private static final LXVector brakeBlobC       = new LXVector(-0.5f,     -1, 0).mult(ALPHA);
  private static final LXVector seatpostStripV   = new LXVector(    0,     -1, 0).mult(ALPHA);
  private static final LXVector pedals           = new LXVector(    3, -15.5f, 0).mult(ALPHA);
  private static final LXVector strip0End        = new LXVector(   13,    -10, 0).mult(ALPHA);

  // Strip 1:
  private static final LXVector seatpostStripH   = new LXVector(    1, -0.5f, 0).mult(ALPHA);
  private static final LXVector handlebarsTop    = new LXVector(20.5f,     1, 0).mult(ALPHA);
  private static final LXVector headlightSpiralC = handlebarsTop.add(HEADLIGHT_SPIRAL_R, 0, 0);
  private static final LXVector handlebarsBottom = new LXVector(20.5f, -1.5f, 0).mult(ALPHA);
  private static final LXVector strip1End        = new LXVector(12.5f,   -10, 0).mult(ALPHA);


  // Geometry LED counts
  // strip 0
  private static final int SEATPOST_LOOP_NUM_LEDS = 10;
  private static final int BRAKE_BLOB_NUM_LEDS = 12;
  private static final int LEN_SEATPOST_PEDALS = 44 - BRAKE_BLOB_NUM_LEDS - SEATPOST_LOOP_NUM_LEDS;
  private static final int LEN_PEDALS_HANDLEBARS_HALF = NUM_LEDS_PER_STRIP -
      LEN_SEATPOST_PEDALS - BRAKE_BLOB_NUM_LEDS - SEATPOST_LOOP_NUM_LEDS;

  // strip 1
  private static final int LEN_SEATPOST_HANDLEBARS = 30;
  private static final int HEADLIGHT_NUM_LEDS = 46 - LEN_SEATPOST_HANDLEBARS;
  private static final int LEN_HANDLEBARS_PEDALS_HALF = NUM_LEDS_PER_STRIP - HEADLIGHT_NUM_LEDS - LEN_SEATPOST_HANDLEBARS;


  // Class fixtures
  // Fixtures
  public final List<LXFixture> framePieces;

  public final LXFixture seatpostLoop;
  public final LXFixture brakeBlob;
  public final LXFixture headlightSpiral;


//  private PatternRotator patternRotator;


  public static DloRoadBikeModel makeModel(boolean hasGui) {
    // If not gui, then Bike is running on raspi.  Get GPIO inputs.
//    if (!hasGui) {
//      RaspiGpio.init(outputProvider);
//    }

    List<LXFixture> allFixtures = new ArrayList<>();
    List<LXFixture> framePieces = new ArrayList<>();


    // STRIP 0
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
    List<LXPoint> handlebarsToPedalHalf = fixture.getPoints().stream().collect(Collectors.toList());
    // Don't add to framePieces because we'll join this into one piece below


    // STRIP 1
    // ---------
    // Topmost seatpost loop: circle in Y-axis
    List<LXPoint> seatpostLoopPoints = new ArrayList<>(SEATPOST_LOOP_NUM_LEDS);
    thetaInc = (float)Math.PI * 2 / SEATPOST_LOOP_NUM_LEDS;
    LXVector seatpostLoopRadial = new LXVector(SEATPOST_LOOP_R, 0, SEATPOST_LOOP_R); // will rotate in y axis
    for (int i=0; i<SEATPOST_LOOP_NUM_LEDS; i++) {
      LXVector led = seatpostLoopC.copy().add(seatpostLoopRadial);
      seatpostLoopPoints.add(new LXPoint(led.x, led.y, led.z));
      seatpostLoopRadial.rotate(thetaInc, 0, 1, 0);
    }
    LXFixture seatpostLoop = () -> seatpostLoopPoints; // getPoints() method implementation lambda.
    allFixtures.add(seatpostLoop);


    // Brake blob.  Model as another circle in the y-axis
    List<LXPoint> brakeBlobPoints = new ArrayList<>(BRAKE_BLOB_NUM_LEDS);
    thetaInc = (float)Math.PI * 2 / BRAKE_BLOB_NUM_LEDS;
    LXVector brakeBlobRadial = new LXVector(BRAKE_BLOB_R, 0, 0); // will rotate in y axis
    for (int i=0; i<BRAKE_BLOB_NUM_LEDS; i++) {
      LXVector led = brakeBlobC.copy().add(brakeBlobRadial);
      brakeBlobPoints.add(new LXPoint(led.x, led.y, led.z));
      brakeBlobRadial.rotate(thetaInc, 0, 1, 0);
    }
    LXFixture brakeBlob = () -> brakeBlobPoints; // new fixtures just define a getPoints() method.  Lambda that fucker.
    allFixtures.add(brakeBlob);


    fixture = new VectorStripModel<>(seatpostStripV, pedals, VectorStripModel.GENERIC_POINT_FACTORY, LEN_SEATPOST_PEDALS);
    allFixtures.add(fixture);
    framePieces.add(fixture);

    fixture = new VectorStripModel<>(pedals, strip0End, VectorStripModel.GENERIC_POINT_FACTORY, LEN_PEDALS_HANDLEBARS_HALF);
    allFixtures.add(fixture);
    List<LXPoint> pedalsToHandlebarsHalf = fixture.getPoints().stream().collect(Collectors.toList());
    // Don't add to framePieces because we'll join this into one piece below


    // MISC
    // ---------
    // Join the two pedals<-->handlebars halves into one fixture
    List<LXPoint> handlesbarsToPedalsSecondHalf = new ArrayList<>(pedalsToHandlebarsHalf);
    Collections.reverse(handlesbarsToPedalsSecondHalf);
    List<LXPoint> handlebarsToPedals = Stream.concat(
        handlebarsToPedalHalf.stream(),
        handlesbarsToPedalsSecondHalf.stream()
    ).collect(Collectors.toList());
    framePieces.add(() -> handlebarsToPedals); // lambda-ify new LXFixture()

    return new DloRoadBikeModel(allFixtures, framePieces, seatpostLoop, brakeBlob, headlightSpiral, hasGui);
  }

  private DloRoadBikeModel(List<LXFixture> allFixtures, List<LXFixture> framePieces,
                        LXFixture seatpostLoop, LXFixture brakeBlob, LXFixture headlightSpiral, boolean hasGui) {
    super(allFixtures.toArray(new LXFixture[allFixtures.size()]));

    System.out.println("Starting up with bike model");

    this.framePieces = framePieces;
    this.seatpostLoop = seatpostLoop;
    this.brakeBlob = brakeBlob;
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
