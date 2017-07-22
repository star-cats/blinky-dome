package com.github.starcats.blinkydome.util;

import heronarts.lx.model.LXAbstractFixture;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXPoint;
import processing.core.PVector;

import java.util.List;


public class SCAbstractFixture extends LXAbstractFixture implements SCFixture {
  // Centroid
  private PVector centroid;

  /**
   * Calculate the centroid of the fixture.  Call when points are ready or changed.
   * @return this, for chaining
   */
  public SCAbstractFixture initCentroid() {
    float cx = 0, cy = 0, cz = 0;
    List<LXPoint> points = this.getPoints();
    for (LXPoint pt : points) {
      cx += pt.x;
      cy += pt.y;
      cz += pt.z;
    }
    this.centroid = new PVector(
        cx / points.size(),
        cy / points.size(),
        cz / points.size()
    );

    return this;
  }

  /**
   * Add a point and recalculates the centroid.
   *
   * Call {@link #addPointFast} to skip centroid calcs (ie call {@link #initCentroid()} manually when done)
   *
   * @param point point to add
   * @return this, for chaining
   */
  @Override
  public SCAbstractFixture addPoint(LXPoint point) {
    super.addPoint(point);
    initCentroid();
    return this;
  }

  /** Add a point without recalculating centroid.  Call {@link #initCentroid()} yourself when done */
  public SCAbstractFixture addPointFast(LXPoint point) {
    super.addPoint(point);
    return this;
  }

  /** Adds all the points from another fixture and recalculates centroid */
  @Override
  public SCAbstractFixture addPoints(LXFixture fixture) {
    super.addPoints(fixture);
    initCentroid();
    return this;
  }

  @Override
  public float getCx() {
    return centroid.x;
  }

  @Override
  public float getCy() {
    return centroid.y;
  }

  @Override
  public float getCz() {
    return centroid.z;
  }

  @Override
  public PVector getCentroid() {
    return centroid.copy(); // make sure immutable
  }
}
