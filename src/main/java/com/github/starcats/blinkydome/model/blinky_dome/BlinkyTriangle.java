package com.github.starcats.blinkydome.model.blinky_dome;

import com.github.starcats.blinkydome.model.util.VectorStripModel;
import com.github.starcats.blinkydome.util.SCAbstractFixture;
import heronarts.lx.transform.LXVector;
import processing.core.PVector;

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
  public final LXVector vA, vB, vC;

  /** Strip from vA to vB */
  public final VectorStripModel<BlinkyLED> sX;

  /** Strip from vB to vC */
  public final VectorStripModel<BlinkyLED> sY;

  /** Strip from vC to vA */
  public final VectorStripModel<BlinkyLED> sZ;


  private final List<BlinkyLED> pointsTyped;

  public final int ppGroup;
  public final int ppPort;


  /**
   * Helper method that creates a BlinkyTriangle positioned in 3D space given the position of the initial vertex
   * and two vectors that define the plane in which the triangle lies.
   *
   * @param v1Position Position of first vertex (initial LED)
   * @param lenSide The length of a triangle side, in the same units as the v1Position vector
   * @param rotation Rotation to apply to the triangle.  0 means the v3-->v1 side is at a right angle to trianglePlaneUp (ie ▲)
   * @param trianglePlaneUp The "up" direction of the triangle plane.
   * @param trianglePlaneRight The "right" direction of the triangle plane.
   * @param firstV Constructor pass-through
   * @param secondV Constructor pass-through
   * @param ppGroup Constructor pass-through
   * @param ppPort Constructor pass-through
   * @param firstPpIndex Constructor pass-through
   * @param domeGroup Constructor pass-through
   * @param domeGroupIndex Constructor pass-through
   * @return
   */
  public static BlinkyTriangle positionIn3DSpace(
      LXVector v1Position,
      float lenSide, float rotation,
      LXVector trianglePlaneUp, LXVector trianglePlaneRight,
      V firstV, V secondV,
      int ppGroup, int ppPort, int firstPpIndex,
      int domeGroup, int domeGroupIndex
  ) {
    LXVector vertexRotationAxis = trianglePlaneUp.copy().cross(trianglePlaneRight);
    float rL = vertexRotationAxis.x;
    float rM = vertexRotationAxis.y;
    float rN = vertexRotationAxis.z;

    LXVector v2 = trianglePlaneUp.copy().setMag(lenSide)
        .rotate((float) Math.PI / 6.f + rotation, rL, rM, rN) // 30 degrees, so inside angle relative to planeRight is 60
        .add(v1Position);

    LXVector v3 = trianglePlaneUp.copy().setMag(lenSide)
        .rotate((float) Math.PI / 2.f + rotation, rL, rM, rN) // 90 degrees
        .add(v1Position);

    return new BlinkyTriangle(
        v1Position, v2, v3, firstV, secondV,
        ppGroup, ppPort, firstPpIndex,
        domeGroup, domeGroupIndex
    );

  }

  /** Enum used to specify vertex ordering for the triangle strip */
  public enum V {
    V1,
    V2,
    V3
  }


  /** Convenience constructor -- does default V1 --> V2 --> V3 --> V1 vertex ordering */
  public BlinkyTriangle(
      LXVector v1, LXVector v2, LXVector v3,
      int ppGroup, int ppPort, int firstPpIndex,
      int domeGroup, int domeGroupIndex
  ) {
    this(v1, v2, v3, V.V1, V.V2, ppGroup, ppPort, firstPpIndex, domeGroup, domeGroupIndex);
  }

  /**
   * Constructor
   * @param v1 One vertex
   * @param v2 Another vertex
   * @param v3 And the third vertex to define the triangle
   * @param firstV The vertex at which the first LED is wired
   * @param secondV The next vertex according to LED strip dataline direction
   * @param ppGroup Which ppGroup this triangle is wired to
   * @param ppPort Which ppPort this triangle is wired to
   * @param firstPpIndex The index of the triangle's first LED on the ppPort harness; generally a multiple of NUM_LEDS_PER_TRIANGLE
   * @param domeGroup Dome geometry metadata: which triangle group this is part of
   * @param domeGroupIndex Dome geometry metadata: the index of the triangle in this triangle group
   */
  public BlinkyTriangle(
      LXVector v1, LXVector v2, LXVector v3,
      V firstV, V secondV,
      int ppGroup, int ppPort, int firstPpIndex,
      int domeGroup, int domeGroupIndex
  ) {
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

    // Note this factory keeps state on ppIndex -- increments it for every new LED created
    BlinkyPointFactory ledFactory = new BlinkyPointFactory(ppGroup, ppPort, firstPpIndex);

    this.sX = new VectorStripModel<>(vA, vB, ledFactory, NUM_LEDS_PER_SIDE);
    this.sY = new VectorStripModel<>(vB, vC, ledFactory, NUM_LEDS_PER_SIDE);
    this.sZ = new VectorStripModel<>(vC, vA, ledFactory, NUM_LEDS_PER_SIDE);

    // Now add all the LEDs in order into this fixture
    this.sX.getPointsTyped().forEach(this::addPointFast);
    this.sY.getPointsTyped().forEach(this::addPointFast);
    this.sZ.getPointsTyped().forEach(this::addPointFast);
    this.initCentroid();

    this.thetaRad = PVector.angleBetween(
        new PVector(1f, 0f, 1f),
        this.getCentroid()
    );
    this.phiRad = PVector.angleBetween(
        new PVector(1f, 1f, 0f),
        this.getCentroid()
    );

    pointsTyped = Stream.of(
        sX.getPointsTyped(),
        sY.getPointsTyped(),
        sZ.getPointsTyped()
    ).flatMap(Collection::stream).collect(Collectors.toList());
  }

  /**
   * Like {@link #getPoints()} but without losing type information in LX interfaces
   */
  public List<BlinkyLED> getPointsTyped() {
    return Collections.unmodifiableList(pointsTyped);
  }

  public String toString() {
    return "[BlinkyTriangle group:" + this.domeGroup + " groupIndex:" + this.domeGroupIndex + "]";
  }


  private static class BlinkyPointFactory implements VectorStripModel.PointFactory<BlinkyLED> {

    private final int ppGroup;
    private final int ppPort;
    private int ppIndex;
    
    BlinkyPointFactory(int ppGroup, int ppPort, int firstPpIndex) {
      this.ppGroup = ppGroup;
      this.ppPort = ppPort;
      this.ppIndex = firstPpIndex;
    }

    @Override
    public BlinkyLED constructPoint(float x, float y, float z) {
      // Increment ppIndex on every new factory constructor -- assume subsequent calls are down the strip.
      return new BlinkyLED(x, y, z, ppGroup, ppPort, ppIndex++);
    }
  }

}
