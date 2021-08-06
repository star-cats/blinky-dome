package com.github.starcats.blinkydome.model.danna_letters;

import com.github.starcats.blinkydome.model.blinky_dome.BlinkyLED;
import com.github.starcats.blinkydome.model.util.PixelPushablePointProducer;
import com.github.starcats.blinkydome.model.util.VectorStripModel;
import com.github.starcats.blinkydome.util.SCAbstractFixture;
import heronarts.lx.transform.LXVector;

import java.util.ArrayList;
import java.util.List;

/**
 * DANNA letters: D
 */
public abstract class AbstractLetter extends SCAbstractFixture {
  private LXVector origin;

  private List<VectorStripModel<BlinkyLED>> segments;

  public final int ppGroup;
  public final int ppPort;
  public final int firstPpIndex;


  public AbstractLetter(LXVector origin, int ppGroup, int ppPort, int firstPpIndex) {
    this.ppGroup = ppGroup;
    this.ppPort = ppPort;
    this.firstPpIndex = firstPpIndex;

    // Note this factory keeps state on ppIndex -- increments it for every new LED
    // created
    VectorStripModel.PointProducer<BlinkyLED> ledFactory = new PixelPushablePointProducer(ppGroup, ppPort, firstPpIndex);

    this.segments = new ArrayList<>();
    for (SegmentSpecification segmentSpec : getSegmentSpecs()) {
      segments.add(new VectorStripModel<>(
          origin.copy().add(segmentSpec.startX, segmentSpec.startY),
          origin.copy().add(
              segmentSpec.startX + segmentSpec.deltaX, segmentSpec.startY + segmentSpec.deltaY
          ),
          ledFactory, segmentSpec.numPoints
      ));
    }


    // Now add all the LEDs in order into this fixture
    this.segments.forEach(strip -> strip.getPoints().forEach(this::addPointFast));
    this.initCentroid();
  }

  public List<VectorStripModel<BlinkyLED>> getSegments() {
    return this.segments;
  }

  /** Implementation hook for letter to define VectorStripModel specs */
  abstract List<SegmentSpecification> getSegmentSpecs();



  //
  // --- HELPER CLASSES ------
  //

  protected static class SegmentSpecification {
    final float startX, startY, deltaX, deltaY;
    final int numPoints;

    public SegmentSpecification(float startX, float startY, float deltaX, float deltaY, int numPoints) {
      this.startX = startX;
      this.startY = startY;
      this.deltaX = deltaX;
      this.deltaY = deltaY;
      this.numPoints = numPoints;
    }
  }

  protected static class SegmentBuilder {
    private float startX, startY;
    public final List<SegmentSpecification> segmentSpecifications = new ArrayList<>();

    public SegmentBuilder(float startX, float startY) {
      this.startX = startX;
      this.startY = startY;
    }

    public SegmentBuilder() {
      this(0, 0);
    }

    public SegmentBuilder addSegment(float deltaX, float deltaY, int numPoints) {
      this.segmentSpecifications.add(new SegmentSpecification(
          this.startX, this.startY, deltaX, deltaY, numPoints
      ));
      this.startX += deltaX;
      this.startY += deltaY;
      return this;
    }
  }

}
