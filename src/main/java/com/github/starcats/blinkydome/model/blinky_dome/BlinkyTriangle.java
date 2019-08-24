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

  private final List<BlinkyLED> pointsTyped;

  public final int ppGroup;
  public final int ppPort;

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
   * Like {@link #getPoints()} but without losing type information in LX
   * interfaces
   */
  public List<BlinkyLED> getPointsTyped() {
    return Collections.unmodifiableList(pointsTyped);
  }

  public String toString() {
    return "[BlinkyTriangle group:" + this.domeGroup + " groupIndex:" + this.domeGroupIndex + "]";
  }

  /**
   * Changes the coordinates of the points in this triangle to rotate their sides.
   *
   * WARNING: NOT TO BE USED DURING MODEL CONSTRUCTION! ie use the V-enum param to set initial rotation!
   * THIS IS FOR CALIBRATION PATTERNS, NOT FOR INITIAL POSITIONING!
   *
   * Why? This method creates dummy LXPoints which are thrown away, but LX doesn't handle "throwing away" well for
   * initial model construction (color indicies stuff). This should be fine to use after model is constructed and
   * initialized, but don't use it as a shortcut to construct the model in the right way.
   */
  public void rotate() {
    // A-->B, B-->C, C-->A
    LXVector prevA = this.vA;
    this.vA = this.vC;
    this.vC = this.vB;
    this.vB = prevA;

    // Now to reposition the points.
    // Note: we can't create new point instances, since the way LX works is points are registered at model creation
    // time. So we need to be careful to use the existing point instances and just feed them new coordinates.

    // We're gonna move sZ to sX's positions, but by then the sX instances will be repositioned.
    // Need to save their positions for the final repositioning
    List<LXPoint> origSXPositions = sX.getPointsTyped().stream()
            .map(blinkyLED -> new LXPoint(blinkyLED.x, blinkyLED.y, blinkyLED.z))
            .collect(Collectors.toList());

    VectorStripModel.PointProducer<BlinkyLED> x2y = new RepositioningBlinkyPointProducer(
            sX.getPointsTyped(), sY.getPointsTyped());
    VectorStripModel.PointProducer<BlinkyLED> y2z = new RepositioningBlinkyPointProducer(
            sY.getPointsTyped(), sZ.getPointsTyped());
    VectorStripModel.PointProducer<BlinkyLED> z2x = new RepositioningBlinkyPointProducer(
            sZ.getPointsTyped(), origSXPositions);

    this.sY = new VectorStripModel<>(vB, vC, x2y, NUM_LEDS_PER_SIDE);
    this.sZ = new VectorStripModel<>(vC, vA, y2z, NUM_LEDS_PER_SIDE);
    this.sX = new VectorStripModel<>(vA, vB, z2x, NUM_LEDS_PER_SIDE);

    // Leave the centroid, thetaRad, and phiRad unchanged -- we rearranged points, but set of positions remains same
    // so those params should be unchanged (and it lets us avoid LX point registration complications of recomputing
    // those)
  }

  /**
   * Changes the coordinates of the points in this triangle as if the triangle was flipped -- makes CW leds go CCW.
   *
   * WARNING: NOT TO BE USED DURING MODEL CONSTRUCTION! ie use the V-enum param to set initial rotation!
   * THIS IS FOR CALIBRATION PATTERNS, NOT FOR INITIAL POSITIONING!
   *
   * Why? This method creates dummy LXPoints which are thrown away, but LX doesn't handle "throwing away" well for
   * initial model construction (color indicies stuff). This should be fine to use after model is constructed and
   * initialized, but don't use it as a shortcut to construct the model in the right way.
   */
  public void flip() {
    // We're going to flip on the A-vertex axis, so
    // B-->C, C-->B
    LXVector prevC = this.vC;
    this.vC = this.vB;
    this.vB = prevC;

    // Now to reposition the points.
    // Note: we can't create new point instances, since the way LX works is points are registered at model creation
    // time. So we need to be careful to use the existing point instances and just feed them new coordinates.

    SimpleRepositioningBlinkyPointProducer origX = new SimpleRepositioningBlinkyPointProducer(sX.getPointsTyped());
    SimpleRepositioningBlinkyPointProducer origY = new SimpleRepositioningBlinkyPointProducer(sY.getPointsTyped());
    SimpleRepositioningBlinkyPointProducer origZ = new SimpleRepositioningBlinkyPointProducer(sZ.getPointsTyped());


    this.sX = new VectorStripModel<>(vA, vB, origX, NUM_LEDS_PER_SIDE);
    this.sY = new VectorStripModel<>(vB, vC, origY, NUM_LEDS_PER_SIDE);
    this.sZ = new VectorStripModel<>(vC, vA, origZ, NUM_LEDS_PER_SIDE);

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
   * VectorStripModel.PointProducer that produces points from a list of existing points, but on its way out,
   * each existing point is repositioned according to a mapping list of new positions.
   */
  private static class RepositioningBlinkyPointProducer implements VectorStripModel.PointProducer<BlinkyLED> {
    private final List<BlinkyLED> ledsToProduce;
    private final List<? extends LXPoint> newPositions;
    private int ledI = 0;

    RepositioningBlinkyPointProducer(List<BlinkyLED> ledsToProduce, List<? extends LXPoint> newPositions) {
      if (ledsToProduce.size() != newPositions.size()) {
        throw new RuntimeException("newPositions map list must be same size as ledsToProduce!");
      }
      this.ledsToProduce = ledsToProduce;
      this.newPositions = newPositions;
    }

    @Override
    public BlinkyLED constructPoint(float x, float y, float z) {
      BlinkyLED repositionedPoint = ledsToProduce.get(ledI);
      LXPoint newPosition = newPositions.get(ledI);

      repositionedPoint.reposition(newPosition);

      ledI += 1;

      return repositionedPoint;
    }
  }


  private static class SimpleRepositioningBlinkyPointProducer implements VectorStripModel.PointProducer<BlinkyLED> {
    private final List<BlinkyLED> ledsToProduce;
    private int ledI = 0;

    SimpleRepositioningBlinkyPointProducer(List<BlinkyLED> ledsToProduce) {
      this.ledsToProduce = ledsToProduce;
    }

    @Override
    public BlinkyLED constructPoint(float x, float y, float z) {
      BlinkyLED repositionedPoint = ledsToProduce.get(ledI);
      ledI += 1;

      repositionedPoint.reposition(x, y, z);

      return repositionedPoint;
    }
  }

}
