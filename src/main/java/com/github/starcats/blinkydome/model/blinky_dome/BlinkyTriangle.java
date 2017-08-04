package com.github.starcats.blinkydome.model.blinky_dome;

import com.github.starcats.blinkydome.model.util.VectorStripModel;
import com.github.starcats.blinkydome.util.SCAbstractFixture;
import heronarts.lx.transform.LXVector;

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


  public final int domeGroup;
  public final int domeGroupIndex;

  // Triangle vertices in 3D space
  public final LXVector vA, vB, vC;

  /** Strip from vA to vB */
  public final VectorStripModel<BlinkyLED> sX;

  /** Strip from vB to vC */
  public final VectorStripModel<BlinkyLED> sY;

  /** Strip from vC to vA */
  public final VectorStripModel<BlinkyLED> sZ;


  private final List<BlinkyLED> pointsTyped;


  /** Convenience constructor -- excludes the reversed parameter */
  public BlinkyTriangle(
      LXVector v1, LXVector v2, LXVector v3,
      int ppGroup, int ppPort, int firstPpIndex,
      int domeGroup, int domeGroupIndex
  ) {
    this(v1, v2, v3, false, ppGroup, ppPort, firstPpIndex, domeGroup, domeGroupIndex);
  }

  /**
   * Constructor
   * @param v1 Starting and ending vertex for the LED strip
   * @param v2 Second vertex
   * @param v3 Third vertex
   * @param reversed False for v1 -> v2 -> v3 -> v1 triangle; true for v1 -> v3 -> v2 -> v1
   * @param ppGroup Which ppGroup this triangle is wired to
   * @param ppPort Which ppPort this triangle is wired to
   * @param firstPpIndex The index of the triangle's first LED on the ppPort harness; generally a multiple of NUM_LEDS_PER_TRIANGLE
   * @param domeGroup Dome geometry metadata: which triangle group this is part of
   * @param domeGroupIndex Dome geometry metadata: the index of the triangle in this triangle group
   */
  public BlinkyTriangle(
      LXVector v1, LXVector v2, LXVector v3, boolean reversed,
      int ppGroup, int ppPort, int firstPpIndex,
      int domeGroup, int domeGroupIndex
  ) {
    this.domeGroup = domeGroup;
    this.domeGroupIndex = domeGroupIndex;

    this.vA = v1;
    this.vB = reversed ? v3 : v2;
    this.vC = reversed ? v2 : v3;

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
