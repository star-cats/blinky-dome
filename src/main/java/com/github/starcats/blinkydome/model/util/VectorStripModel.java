package com.github.starcats.blinkydome.model.util;

import com.github.starcats.blinkydome.util.SCAbstractFixture;
import heronarts.lx.model.LXPoint;
import heronarts.lx.transform.LXVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Model for a straight strip of LED points between two vector positions
 *
 * LED positions are padded, meaning the LED's won't start AT start, but rather will have a half spacing between start
 * and first one.
 * ie if start is 0, end is 1, and numPoints is 4, will create 4 LEDs at 0.125, 0.375, 0.625, and 0.875
 *
 * @param <P> The type of LXPoint used for this fixture (
 */
public class VectorStripModel<P extends LXPoint> extends SCAbstractFixture {
  public final LXVector start;
  public final LXVector end;

  private final List<P> typedPoints;

  public interface PointFactory<P extends LXPoint> {
    P constructPoint(float x, float y, float z);
  }

  public static final PointFactory<LXPoint> GENERIC_POINT_FACTORY = LXPoint::new;

  public VectorStripModel(LXVector start, LXVector end, PointFactory<P> pointFactory, int numPoints) {
    this.start = start;
    this.end = end;
    this.typedPoints = new ArrayList<>(numPoints);

    float lerpInc = 1f/(float)numPoints;
    float lerpI = lerpInc / 2;

    for (int i=0; i<numPoints; i++) {
      LXVector newPoint = start.copy().lerp(end, lerpI);
      P pt = pointFactory.constructPoint(newPoint.x, newPoint.y, newPoint.z);

      // lose generic typing; fast to avoid incremental centroid calcs
      addPointFast(pt);

      // fighting non-generic'd LX interfaces: also save typed point
      typedPoints.add(pt);

      lerpI += lerpInc;
    }

    this.initCentroid();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    VectorStripModel that = (VectorStripModel) o;

    if (!start.equals(that.start)) return false;
    return end.equals(that.end);

  }

  @Override
  public int hashCode() {
    int result = start.hashCode();
    result = 31 * result + end.hashCode();
    return result;
  }

  /**
   * Like {@link #getPoints()} but without losing type information in LX interfaces
   */
  public List<P> getPointsTyped() {
    return Collections.unmodifiableList(this.typedPoints);
  }


}
