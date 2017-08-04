package com.github.starcats.blinkydome.model;

import com.github.starcats.blinkydome.model.util.VectorStripModel;
import com.github.starcats.blinkydome.util.SCAbstractFixture;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.transform.LXVector;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dan's Fibonocci Petals LED poster
 */
public class FibonocciPetalsModel extends LXModel {
  private static final int NUM_LEDS_PER_STRIP = 64; // fadecandy port

  // Coordinate system scale factor to get approximately in sync with other models
  private static final float ALPHA = 10;

  public static class Petal extends SCAbstractFixture {
    public final LXVector point;

    private LXFixture cwSide;
    private LXFixture ccwSide;
    private List<LXPoint> allPoints;

    private Petal(double thetaRad, int numRotations) {

      // Set magnitude of vector based on numRotations
      point = new LXVector(ALPHA * (numRotations + 1), 0, (numRotations - 7) * 10);

      // and add rotation
      point.rotate((float)thetaRad);
    }

    public LXFixture getCwSide() {
      return cwSide;
    }

    public LXFixture getCcwSide() {
      return ccwSide;
    }

    public List<LXPoint> getPoints() {
      return allPoints;
    }
  }

  public static class PetalSpiral implements LXFixture {
    private final List<Petal> petals;
    private final Set<LXPoint> allPoints;
    private final Set<LXPoint> cwPoints;
    private final Set<LXPoint> ccwPoints;

    /** Maximum magnitude of a point in this spiral*/
    public final float maxR;

    private PetalSpiral(Petal[] petals) {
      this.petals = Arrays.asList(petals);

      List<LXPoint> cwPointsList = this.petals.stream()
          .flatMap(petal -> petal.cwSide.getPoints().stream())
          .collect(Collectors.toList());

      List<LXPoint> ccwPointsList = this.petals.stream()
          .flatMap(petal -> petal.ccwSide.getPoints().stream())
          .collect(Collectors.toList());

      cwPoints = new HashSet<>(cwPointsList);
      ccwPoints = new HashSet<>(ccwPointsList);

      allPoints = new HashSet<>(cwPoints);
      allPoints.addAll(ccwPoints);

      float maxR = 0;
      for (LXPoint p : allPoints) {
        if (p.r > maxR) {
          maxR = p.r;
        }
      }
      this.maxR = maxR;
    }

    @Override
    public List<LXPoint> getPoints() {
      return new ArrayList<>(allPoints);
    }

    public Set<LXPoint> getCwPoints() {
      return cwPoints;
    }

    public Set<LXPoint> getCcwPoints() {
      return ccwPoints;
    }

    public List<Petal> getPetals() {
      return petals;
    }
  }

  // Processing's bezierPoint() function
  private static float bezierPoint(float a, float b, float c, float d, float t) {
    float t1 = 1.0F - t;
    return a * t1 * t1 * t1 + 3.0F * b * t * t1 * t1 + 3.0F * c * t * t * t1 + d * t * t * t;
  }

  private static Petal[] petals = {
      // Fibonocci petals are derived by thetaInc = 2*PI * 1/PHI and continuing to spin that around
      // (ie first petal is 0*thetaInc, then 1*thetaInc, then 2*thetaInc, etc., all mod 2PI)
      // Here we've done that but thrown away everything that's >PI (ie using only "top half" of circle)
      new Petal(0.000000000, 0) , // 0
      new Petal(2.399892449, 0) , // 1
      new Petal(0.916677346, 1) , // 2
      new Petal(1.833354692, 2) , // 3
      new Petal(0.350139589, 3) , // 4
      new Petal(2.750032038, 3) , // 5
      new Petal(1.266816936, 4) , // 6
      new Petal(2.183494282, 5) , // 7
      new Petal(0.700279179, 6) , // 8
      new Petal(3.100171628, 6) , // 9
      new Petal(1.616956525, 7) , // 10
      new Petal(0.133741422, 8) , // 11
      new Petal(2.533633871, 8) , // 12
      new Petal(1.050418768, 9) , // 13
      new Petal(1.967096114, 10), // 14
      new Petal(0.483881012, 11), // 15
      new Petal(2.883773461, 11), // 16
      new Petal(1.400558358, 12), // 17
      new Petal(2.317235704, 13), // 18
      new Petal(0.834020601, 14), // 19
//      new Petal(1.750697947, 15), // 20
//      new Petal(0.267482845, 16), // 21
//      new Petal(2.667375293, 16), // 22
//      new Petal(1.184160191, 17), // 23
//      new Petal(2.100837537, 18)  // 24
  };

  private static LXVector origin = new LXVector(0,0,0);

  private static void addPetal(List<LXFixture> allFixtures, Petal petal, int numLedsThere, int numLedsBack) {
    addPetal(allFixtures, petal, numLedsThere, numLedsBack, true);
  }
  private static void addPetal(List<LXFixture> allFixtures, Petal petal, int numLedsThere, int numLedsBack,
                               boolean stripGoesCw) {
    LXVector tip = petal.point;
    LXVector originControl = origin.copy().lerp(tip, 0.4f);
    LXVector tipControlThere = tip.copy()
        .rotate((stripGoesCw ? -1 : 1) * (float)Math.PI * 1.2f/2f)
        .setMag(tip.mag() * 0.3f);
    LXVector tipControlBack = tip.copy()
        .rotate((stripGoesCw ? 1 : -1) * (float)Math.PI * 1.2f/2f)
        .setMag(tip.mag() * 0.3f);

    List<LXPoint> therePts = new ArrayList<>(numLedsThere);
    for (int i=0; i<numLedsThere; i++) {
      therePts.add(new LXPoint(
          bezierPoint(origin.x, originControl.x, tip.x + tipControlThere.x, tip.x, (float)i / ((float)numLedsThere)),
          bezierPoint(origin.y, originControl.y, tip.y + tipControlThere.y, tip.y, (float)i / ((float)numLedsThere)),
          petal.point.z
      ));
    }

    List<LXPoint> backPts = new ArrayList<>(numLedsThere);
    for (int i=0; i<numLedsBack; i++) {
      backPts.add(new LXPoint(
          bezierPoint(tip.x, tip.x + tipControlBack.x, originControl.x, origin.x, (float)i / ((float)numLedsThere)),
          bezierPoint(tip.y, tip.y + tipControlBack.y, originControl.y, origin.y, (float)i / ((float)numLedsThere)),
          petal.point.z
      ));
    }

    LXFixture there = () -> therePts;
    LXFixture back = () -> backPts;

    petal.cwSide  = stripGoesCw ? there : back;
    petal.ccwSide = stripGoesCw ? back  : there;

    List<LXPoint> allPetalPoints = new ArrayList<>(therePts);
    allPetalPoints.addAll(backPts);
    petal.allPoints = allPetalPoints;
    petal.initCentroid();

    allFixtures.add(there);
    allFixtures.add(back);
  }

  private static void addTail(List<LXFixture> allFixtures, List<LXFixture> tails, int numLeds) {
    LXFixture tail = new VectorStripModel<>(
        origin,
        new LXVector(0, (float)numLeds * -ALPHA/3f, 0),
        VectorStripModel.GENERIC_POINT_FACTORY,
        numLeds
    );

    allFixtures.add(tail);
    tails.add(tail);
  }


  public static FibonocciPetalsModel makeModel() {
    List<LXFixture> allFixtures = new ArrayList<>();

    List<LXFixture> tails = new ArrayList<>();

    // Now we add petals in order of LED wiring

    // FC port 0 (petals 0-5)
    addPetal(allFixtures, petals[0], 4, 4);
    addPetal(allFixtures, petals[2], 5, 4);
    addPetal(allFixtures, petals[3], 6, 6);
    addPetal(allFixtures, petals[1], 4, 3);
    addPetal(allFixtures, petals[5], 7, 7);
    addPetal(allFixtures, petals[4], 8, 6);

    // FC port 1
    addPetal(allFixtures, petals[6], 9, 7);
    addPetal(allFixtures, petals[7], 9, 9);
    addPetal(allFixtures, petals[9], 10, 12);
    addTail(allFixtures, tails, 8);

    // FC port 2
    addPetal(allFixtures, petals[12], 13, 13, false);
    addPetal(allFixtures, petals[10], 11, 13, false);
    addTail(allFixtures, tails, 14);

    // FC port 3
    addPetal(allFixtures, petals[11], 13, 13);
    addPetal(allFixtures, petals[8], 10, 12 );
    addTail(allFixtures, tails, 16);

    // FC port 4
    addPetal(allFixtures, petals[13], 14, 14);
    addPetal(allFixtures, petals[17], 18, 18); // TODO: last four on strip broken

    // FC port 5
    addPetal(allFixtures, petals[15], 17, 14);
    addPetal(allFixtures, petals[19], 17, 16);

    // FC port 6
    addPetal(allFixtures, petals[14], 16, 14);
    addPetal(allFixtures, petals[18], 17, 17);

    // FC port 7
    addPetal(allFixtures, petals[16], 16, 16);
    addTail(allFixtures, tails, 32);


    // Okay.  LED's are mapped.
    // Now lets define higher-order model attributes.


    List<PetalSpiral> cwSpirals = new ArrayList();
    cwSpirals.add(new PetalSpiral(new Petal[]{
        petals[11],
        petals[15],
        petals[19],
    }));

    cwSpirals.add(new PetalSpiral(new Petal[]{
        petals[0],
        petals[4],
        petals[8],
        petals[13],
        petals[17],
    }));

    cwSpirals.add(new PetalSpiral(new Petal[]{
        petals[2],
        petals[6],
        petals[10],
        petals[14],
        petals[18],
    }));

    cwSpirals.add(new PetalSpiral(new Petal[]{
        petals[3],
        petals[7],
        petals[12],
        petals[16],
    }));

    cwSpirals.add(new PetalSpiral(new Petal[]{
        petals[1],
        petals[5],
        petals[9],
    }));

    return new FibonocciPetalsModel(allFixtures, Arrays.asList(petals), cwSpirals);
  }

  // END STATIC BUILDER
  // -----------------------------

  public final List<Petal> allPetals;
  public final List<PetalSpiral> cwSpirals;

  private FibonocciPetalsModel(List<LXFixture> allFixtures,
                               List<Petal> allPetals, List<PetalSpiral> cwSpirals) {
    super(allFixtures.toArray(new LXFixture[allFixtures.size()]));

    System.out.println("Starting up with fibonocci petals model");

    this.allPetals = allPetals;
    this.cwSpirals = cwSpirals;
  }

  /**
   * Gets a spiral's worth of petals
   * @param spiralSelect Which cwSpiral to grab
   * @return Petals in that spiral
   */
  public List<Petal> getPetalsBySpiral(int spiralSelect) {
    return getPetals(spiralSelect, -1);
  }

  /**
   * Gets a cross section of petals from this layout
   * @param spiralSelect Which cwSpiral to grab, or -1 for all
   * @param petalSelect Which petal from the spiral to use, or -1 for all
   * @return One or more petals as an immutable list
   */
  public List<Petal> getPetals(int spiralSelect, int petalSelect) {

    if (spiralSelect >= 0) {
      if (petalSelect >= 0) {
        return Collections.singletonList( cwSpirals.get(spiralSelect).getPetals().get(petalSelect) );
      } else {
        return Collections.unmodifiableList( cwSpirals.get(spiralSelect).getPetals() );
      }

    } else {
      // all spirals
      List<Petal> petals = new ArrayList<>();
      for (PetalSpiral spiral : cwSpirals) {
        if (petalSelect >= 0) {
          if (petalSelect >= spiral.getPetals().size()) {
            continue;
          }
          petals.add(spiral.getPetals().get(petalSelect));

        } else {
          petals.addAll(spiral.getPetals());
        }
      }
      return Collections.unmodifiableList(petals);
    }
  }

}
