package com.github.starcats.blinkydome.model.blinky_dome;

import com.github.starcats.blinkydome.model.util.VectorStripModel;
import com.github.starcats.blinkydome.util.SCAbstractFixture;
import heronarts.lx.model.LXPoint;
import heronarts.lx.transform.LXVector;
import processing.core.PVector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Blinky-dome acrylic triangle fixtures
 */
public class BlinkyTriangle extends SCAbstractFixture {
  public static final int NUM_LEDS_PER_SIDE = 35;
  public static final int NUM_LEDS_PER_TRIANGLE = 3 * NUM_LEDS_PER_SIDE;

  // Geometry metadata:
  public final int domeGroup;
  public final int domeGroupIndex;

  /** Angular position of triangle in XZ (floor) plane */
  public final float thetaRad;

  /** Angular position of triangle in XY (up/down) plane */
  public final float phiRad;

  // Triangle vertices in 3D space
  private LXVector vA, vB, vC;

  /** Strip from vA to vB */
  private VectorStripModel<BlinkyLED> sX;

  /** Strip from vB to vC */
  private VectorStripModel<BlinkyLED> sY;

  /** Strip from vC to vA */
  private VectorStripModel<BlinkyLED> sZ;

  private List<BlinkyLED> pointsTyped;

  public final int ppGroup;
  public final int ppPort;
  public final int firstPpIndex;

  /**
   * Helper method that creates a BlinkyTriangle positioned in 3D space given the
   * position of the initial vertex and two vectors that define the plane in which
   * the triangle lies.
   *
   * @param v1Position         Position of first vertex (initial LED)
   * @param lenSide            The length of a triangle side, in the same units as
   *                           the v1Position vector
   * @param rotation           Rotation to apply to the triangle. 0 means the
   *                           v3-->v1 side is at a right angle to trianglePlaneUp
   *                           (ie ▲)
   * @param trianglePlaneUp    The "up" direction of the triangle plane.
   * @param trianglePlaneRight The "right" direction of the triangle plane.
   * @param firstV             Constructor pass-through
   * @param secondV            Constructor pass-through
   * @param ppGroup            Constructor pass-through
   * @param ppPort             Constructor pass-through
   * @param firstPpIndex       Constructor pass-through
   * @param domeGroup          Constructor pass-through
   * @param domeGroupIndex     Constructor pass-through
   * @return
   */
  public static BlinkyTriangle positionIn3DSpace(LXVector v1Position, float lenSide, float rotation,
      LXVector trianglePlaneUp, LXVector trianglePlaneRight, V firstV, V secondV, int ppGroup, int ppPort,
      int firstPpIndex, int domeGroup, int domeGroupIndex) {
    LXVector vertexRotationAxis = trianglePlaneUp.copy().cross(trianglePlaneRight);
    float rL = vertexRotationAxis.x;
    float rM = vertexRotationAxis.y;
    float rN = vertexRotationAxis.z;

    LXVector v2 = trianglePlaneUp.copy().setMag(lenSide).rotate((float) Math.PI / 6.f + rotation, rL, rM, rN)
        // 30 degrees, so inside angle relative to planeRight is 60
        .add(v1Position);

    LXVector v3 = trianglePlaneUp.copy().setMag(lenSide).rotate((float) Math.PI / 2.f + rotation, rL, rM, rN)
            // 90 degrees
        .add(v1Position);

    return new BlinkyTriangle(v1Position, v2, v3, firstV, secondV, ppGroup, ppPort, firstPpIndex, domeGroup,
        domeGroupIndex);

  }

  /** Enum used to specify vertex ordering for the triangle strip */
  public enum V {
    V1, V2, V3
  }

  /**
   * Convenience constructor -- does default V1 --> V2 --> V3 --> V1 vertex
   * ordering
   */
  public BlinkyTriangle(LXVector v1, LXVector v2, LXVector v3, int ppGroup, int ppPort, int firstPpIndex, int domeGroup,
      int domeGroupIndex) {
    this(v1, v2, v3, V.V1, V.V2, ppGroup, ppPort, firstPpIndex, domeGroup, domeGroupIndex);
  }

  /**
   * Constructor
   * 
   * @param v1             One vertex
   * @param v2             Another vertex
   * @param v3             And the third vertex to define the triangle
   * @param firstV         The vertex at which the first LED is wired
   * @param secondV        The next vertex according to LED strip dataline
   *                       direction
   * @param ppGroup        Which ppGroup this triangle is wired to
   * @param ppPort         Which ppPort this triangle is wired to
   * @param firstPpIndex   The index of the triangle's first LED on the ppPort
   *                       harness; generally a multiple of NUM_LEDS_PER_TRIANGLE
   * @param domeGroup      Dome geometry metadata: which triangle group this is
   *                       part of
   * @param domeGroupIndex Dome geometry metadata: the index of the triangle in
   *                       this triangle group
   */
  public BlinkyTriangle(LXVector v1, LXVector v2, LXVector v3, V firstV, V secondV, int ppGroup, int ppPort,
      int firstPpIndex, int domeGroup, int domeGroupIndex) {
    System.out.println(ppGroup + "\t" + ppPort + "\t" + firstPpIndex + "\t" + domeGroup + "\t" + domeGroupIndex);
    this.domeGroup = domeGroup;
    this.domeGroupIndex = domeGroupIndex;

    this.ppGroup = ppGroup;
    this.ppPort = ppPort;
    this.firstPpIndex = firstPpIndex;

    if (firstV == V.V1) {
      this.vA = v1;
      this.vB = secondV == V.V2 ? v2 : v3;
      this.vC = secondV == V.V2 ? v3 : v2;

    } else if (firstV == V.V2) {
      this.vA = v2;
      this.vB = secondV == V.V3 ? v3 : v1;
      this.vC = secondV == V.V3 ? v1 : v3;

    } else {
      this.vA = v3;
      this.vB = secondV == V.V1 ? v1 : v2;
      this.vC = secondV == V.V1 ? v2 : v1;
    }

    // Note this factory keeps state on ppIndex -- increments it for every new LED
    // created
    BlinkyPointProducer ledFactory = new BlinkyPointProducer(ppGroup, ppPort, firstPpIndex);

    this.sX = new VectorStripModel<>(vA, vB, ledFactory, NUM_LEDS_PER_SIDE);
    this.sY = new VectorStripModel<>(vB, vC, ledFactory, NUM_LEDS_PER_SIDE);
    this.sZ = new VectorStripModel<>(vC, vA, ledFactory, NUM_LEDS_PER_SIDE);

    // Now add all the LEDs in order into this fixture
    this.sX.getPointsTyped().forEach(this::addPointFast);
    this.sY.getPointsTyped().forEach(this::addPointFast);
    this.sZ.getPointsTyped().forEach(this::addPointFast);
    this.initCentroid();

    this.thetaRad = PVector.angleBetween(new PVector(1f, 0f, 1f), this.getCentroid());
    this.phiRad = PVector.angleBetween(new PVector(1f, 1f, 0f), this.getCentroid());

    this.pointsTyped = Stream.of(sX.getPointsTyped(), sY.getPointsTyped(), sZ.getPointsTyped()).flatMap(Collection::stream)
        .collect(Collectors.toList());
  }

  /**
   * Like {@link #getPoints()}, but returns the points in order of the triangle pixel chain (ie how they're connected
   * on the pixelpusher). Will be reordered as a result of .rotate() or .flip().
   */
  public List<BlinkyLED> getPixelChain() {
    return Collections.unmodifiableList(pointsTyped);
  }

  public String toString() {
    return "[BlinkyTriangle group:" + this.domeGroup + " groupIndex:" + this.domeGroupIndex + "]";
  }

  /**
   * Rotates the LED's in the triangle.
   *
   * Leaves the underlying pixels in place, but changes the fixture abstractions and re-numbers how they're mapped on
   * the pixelpusher output chain. In other words, of the triangle pixels, we change which corner has the 'first'
   * pixel on the chain.
   */
  public void rotate() {
    // A-->B, B-->C, C-->A
    LXVector prevA = this.vA;
    this.vA = this.vC;
    this.vC = this.vB;
    this.vB = prevA;

    // start the pp ordering at y: y, z, x
    List<BlinkyLED> newOrdering = Stream.of(sY.getPointsTyped(), sZ.getPointsTyped(), sX.getPointsTyped())
            .flatMap(Collection::stream)
            .collect(Collectors.toList());

    PPReorderingPointProducer newPPOrdering = new PPReorderingPointProducer(newOrdering, this.firstPpIndex);

    // Now we redefine our fixture abstractions while producing the existing pixels in the new reordering
    this.sX = new VectorStripModel<>(vA, vB, newPPOrdering, NUM_LEDS_PER_SIDE);
    this.sY = new VectorStripModel<>(vB, vC, newPPOrdering, NUM_LEDS_PER_SIDE);
    this.sZ = new VectorStripModel<>(vC, vA, newPPOrdering, NUM_LEDS_PER_SIDE);

    this.pointsTyped = newOrdering;

    // Leave the centroid, thetaRad, and phiRad unchanged -- we rearranged points, but set of positions remains same
    // so those params should be unchanged (and it lets us avoid LX point registration complications of recomputing
    // those)
  }

  /**
   * Flips the LED's in the triangle -- if the pixel chain was going CW, makes the chain go CCW.
   *
   * Leaves the underlying pixels in place, but changes the fixture abstractions and re-numbers how they're mapped on
   * the pixelpusher output chain. In other words, of the triangle pixels, we change which corner has the 'first'
   * pixel on the chain.
   */
  public void flip() {
    // We're going to flip on the A-vertex axis, so
    // B-->C, C-->B
    LXVector prevC = this.vC;
    this.vC = this.vB;
    this.vB = prevC;

    // to flip: same x, y, z ordering, but reverse it. That way x --> z', y --> y', z --> x' (where '==reversed)
    List<BlinkyLED> newOrdering = Stream.of(sX.getPointsTyped(), sY.getPointsTyped(), sZ.getPointsTyped())
            .flatMap(Collection::stream)
            .collect(Collectors.toList());
    Collections.reverse(newOrdering);

    PPReorderingPointProducer newPPOrdering = new PPReorderingPointProducer(newOrdering, this.firstPpIndex);

    // Now we redefine our fixture abstractions while producing the existing pixels in the new reordering
    this.sX = new VectorStripModel<>(vA, vB, newPPOrdering, NUM_LEDS_PER_SIDE);
    this.sY = new VectorStripModel<>(vB, vC, newPPOrdering, NUM_LEDS_PER_SIDE);
    this.sZ = new VectorStripModel<>(vC, vA, newPPOrdering, NUM_LEDS_PER_SIDE);

    this.pointsTyped = newOrdering;

    // Leave the centroid, thetaRad, and phiRad unchanged -- we rearranged points, but set of positions remains same
    // so those params should be unchanged (and it lets us avoid LX point registration complications of recomputing
    // those)
  }

  public VectorStripModel<BlinkyLED> getSX() {
    return sX;
  }

  public VectorStripModel<BlinkyLED> getSY() {
    return sY;
  }

  public VectorStripModel<BlinkyLED> getSZ() {
    return sZ;
  }

  //
  // -----------------------
  // VectorStripModel.PointProducer implementations for BlinkyLED / BlinkyTriangle use-cases
  // -----------------------

  /**
   * BlinkyLED's used in BlinkyTriangles are pixel-pushable LXPoint's, meaning each needs to have pixelpusher
   * params attached to it.
   * This is a stateful producer of BlinkyLEDs (LXPoints) that increments the LED's pixel pusher position for each
   * new LED produced.
   */
  private static class BlinkyPointProducer implements VectorStripModel.PointProducer<BlinkyLED> {

    private final int ppGroup;
    private final int ppPort;
    private int ppIndex;

    BlinkyPointProducer(int ppGroup, int ppPort, int firstPpIndex) {
      this.ppGroup = ppGroup;
      this.ppPort = ppPort;
      this.ppIndex = firstPpIndex;
    }

    @Override
    public BlinkyLED constructPoint(float x, float y, float z) {
      // Increment ppIndex on every new factory constructor -- assume subsequent calls
      // are down the strip.
      return new BlinkyLED(x, y, z, ppGroup, ppPort, ppIndex++);
    }
  }

  /**
   * Bit of a hack to shimmy reordering (rotation and flipping) into existing structures.
   * Here we have a producer that doesn't produce NEW pixels instances, only produces a list of existing instances.
   * But, on their way out, their pixelpusher output changes, so effectively we're changing the mapping between
   * virtual space and physical space -- we're mapping different virtual pixels to the same physical pixels.
   */
  private static class PPReorderingPointProducer implements VectorStripModel.PointProducer<BlinkyLED> {
    private final List<BlinkyLED> ledsToProduce;
    private int i = 0;
    private int ppOffset;

    PPReorderingPointProducer(List<BlinkyLED> ledsToReorder, int initialPPOfset) {
      this.ledsToProduce = ledsToReorder;
      ppOffset = initialPPOfset;
    }

    @Override
    public BlinkyLED constructPoint(float x, float y, float z) {
      // We're not producing any new instances, so we do nothing with x,y,z.  This is a bit of a hack to fit into
      // existing patterns.
      
      BlinkyLED reorder = ledsToProduce.get(this.i);
      reorder.setPpIndex(ppOffset);

      ppOffset += 1;
      i += 1;

      return reorder;
    }
  }

}
