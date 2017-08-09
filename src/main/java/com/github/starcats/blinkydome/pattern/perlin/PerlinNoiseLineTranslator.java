package com.github.starcats.blinkydome.pattern.perlin;

import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.transform.LXProjection;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Takes in a bunch of fixtures assuming each has evenly-spaced LED's that loop back onto themselves.
 *
 * For each fixture, we map the points to a circle in a perlin noise field.  The loop can either rotate, sending the
 * perlin noise pattern "down the line", or it can translate through the noise field, making the pattern morph.
 *
 * No feature locality between different fixtures
 */
public class PerlinNoiseLineTranslator {

  private static final LXVector TRAVEL_DIR = new LXVector(0, 0, 1);
  private static final LXVector TRAVEL_DIR_REV = new LXVector(0, 0, -1);

  // Rotates around TRAVEL_DIR to place loop projections
  private static final LXVector INITIAL_LOOP_POSITION = new LXVector(1, 0, 0);


  /** Points' vector multiplier to transform features down into noise space -- smaller makes larger perlin features */
  public final CompoundParameter noiseZoom;

  /** Speed we move through the noise space */
  public final CompoundParameter noiseSpeed;

  /** How rotated the fixture is */
  public final CompoundParameter fixtureRotation;


  private List<FixtureLoopProjection> lines;
  private final List< Map<Integer, Float> > pt2PerlinSpaceProjections;


  // Travel vector through perlin noise field (magnitude is parameterized)
  private final LXVector travel = TRAVEL_DIR.copy();

  private final PApplet p;


  public PerlinNoiseLineTranslator(PApplet p, List<? extends LXFixture> fixtures, String prefix, String desc) {
    this(p, fixtures, prefix, desc, null, null);
  }

  public PerlinNoiseLineTranslator(PApplet p, List<? extends LXFixture> fixtures, String prefix, String desc,
                                   CompoundParameter noiseSpeed, CompoundParameter noiseZoom
  ) {
    this.p = p;

    this.noiseZoom = noiseZoom != null ?
        noiseZoom :
        (CompoundParameter) new CompoundParameter(prefix + "zoom", 1, 0.1, 10)
            .setDescription("Multiplier ('zoom') of the perlin noise pattern used for " + desc + " mapping")
            .setExponent(2);


    this.noiseSpeed = noiseSpeed != null ?
        noiseSpeed :
        new CompoundParameter(prefix + "speed", 0, -1., 1.)
            .setDescription("The speed of the perlin noise pattern used for " + desc + " mapping");

    this.fixtureRotation = new CompoundParameter(prefix + " angle")
        .setDescription("Rotation of fixtures around the noise field");

    this.lines = fixtures.stream().map(FixtureLoopProjection::new).collect(Collectors.toList());
    this.pt2PerlinSpaceProjections = this.lines.stream()
        .map(FixtureLoopProjection::calculateProjection)
        .collect(Collectors.toList());

    // Adjust zoom on all FixtureLoopProjections when param changes
    this.noiseZoom.addListener(param ->
      this.lines.forEach(flp -> flp.setZoom(param.getValuef()))
    );
    this.lines.forEach(flp -> flp.setZoom(this.noiseZoom.getValuef()));
  }


  /**
   * Advance the perlin noise projections.  Patterns should call this in their run().
   * @param deltaMs
   */
  public void step(double deltaMs) {
    float newTravelMag = (float) (noiseSpeed.getValue() * deltaMs * noiseZoom.getValuef() / 400);
    if (newTravelMag != 0) {
      travel.set(newTravelMag > 0 ? TRAVEL_DIR : TRAVEL_DIR_REV).setMag(newTravelMag);
      this.lines.forEach(flp -> flp.travel(travel));
    }

    // Set rotation every frame rather than listener to account for modulation
    this.lines.forEach(flp -> flp.setRotation(fixtureRotation.getValue()));

    this.lines.forEach(FixtureLoopProjection::calculateProjection);
  }

  /**
   * Get the perlin noise value mapping for a tracked point
   * @param pt
   * @return Value of perlin noise projection
   */
  public float getPtValue(LXPoint pt) {
    for (Map<Integer, Float> pt2Value : this.pt2PerlinSpaceProjections) {
      if (!pt2Value.containsKey(pt.index)) continue;

      return levelTransform(pt2Value.get(pt.index));
    }

    throw new RuntimeException("Unknown point! " + pt.toString());
  }

  /** Implementation hook that transforms the levels curve of perlin noise (default: makes more extreme) */
  protected float levelTransform(float in) {
    return (float) MaskingColorizer.LevelCurve.EXP_IN_OUT.transform(in);
  }



  private class FixtureLoopProjection {

    private final LXProjection loopProjection;

    private final LXVector noiseFieldPos;


    // memoized vars to avoid new objects every loop
    private final Map<Integer, Float> ptToPerlinValues = new HashMap<>();
    private final Map<Integer, Float> immutablePtToPerlinValues = Collections.unmodifiableMap(ptToPerlinValues);
    private final LXVector curProj = new LXVector(0, 0, 0);
    private double curRotationN = 0; // normalized, 0-1

    public FixtureLoopProjection(LXFixture origFixture) {

      LXModel fakeModel = new LXModel(origFixture);
      this.loopProjection = new LXProjection( fakeModel );

      // In order to save space and not create a second layer of LXPoints that we would have to map, just open up the
      // projection's vectors and reorient them into desired loop projection
      LXVector pt = INITIAL_LOOP_POSITION.copy();
      Iterator<LXVector> projectionIter = this.loopProjection.iterator();
      float stepTheta = (float) (2. * Math.PI / origFixture.getPoints().size());
      while (projectionIter.hasNext()) {
        LXVector projection = projectionIter.next();

        projection.set(pt);
        pt.rotate(stepTheta, TRAVEL_DIR.x, TRAVEL_DIR.y, TRAVEL_DIR.z);
      }

      // Initialize each loop projection to a different place in the noise field
      this.noiseFieldPos = new LXVector(fakeModel.cx, fakeModel.cy, fakeModel.cz);
    }

    public void setRotation(double normalizedTheta) {
      double deltaTheta = (normalizedTheta - curRotationN) * Math.PI * 2.;
      this.curRotationN = normalizedTheta;
      this.spin((float) deltaTheta);
    }

    /** Rotate the loop around the travel direction */
    public void spin(float theta) {
      this.loopProjection.rotate(-theta, TRAVEL_DIR.x, TRAVEL_DIR.y, TRAVEL_DIR.z);
    }

    /** Move through Perlin space */
    public void travel(LXVector travelVector) {
      noiseFieldPos.add(travelVector);
    }

    public void setZoom(float zoom) {
      this.loopProjection.iterator().forEachRemaining(vector -> vector.setMag(zoom) );
    }

    /**
     * Calculate projection
     * @return Mapping of point indexes to a perlin value projection
     */
    public Map<Integer, Float> calculateProjection() {
      this.loopProjection.iterator().forEachRemaining(ptProj -> {
        curProj.set(noiseFieldPos).add(ptProj);

        ptToPerlinValues.put(
            ptProj.point.index,
            p.noise(curProj.x, curProj.y, curProj.z)
        );

      });
      return immutablePtToPerlinValues;
    }
  }

}
