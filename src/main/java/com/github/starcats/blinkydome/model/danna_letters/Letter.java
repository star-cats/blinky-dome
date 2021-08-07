package com.github.starcats.blinkydome.model.danna_letters;

import com.github.starcats.blinkydome.model.blinky_dome.BlinkyLED;
import com.github.starcats.blinkydome.model.util.PixelPushablePointProducer;
import com.github.starcats.blinkydome.model.util.VectorStripModel;
import com.github.starcats.blinkydome.util.SCAbstractFixture;
import heronarts.lx.transform.LXVector;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixture representing a letter. Defined by a series of VectorStripModels, as defined by shortcut
 * classes SegmentSpecification and SegmentBuilder
 */
public class Letter extends SCAbstractFixture {
  private List<VectorStripModel<BlinkyLED>> segments;

  /**
   * @param ledFactory PointProducer to generate LED points for the segments
   * @param origin Global offset for the origin point of this letter -- segment specs get added to this
   * @param segmentSpecs Definition of internal LED strip segments
   */
  public Letter(VectorStripModel.PointProducer<BlinkyLED> ledFactory,
                LXVector origin, List<SegmentSpecification> segmentSpecs) {

    this.segments = new ArrayList<>();
    for (SegmentSpecification segmentSpec : segmentSpecs) {
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
