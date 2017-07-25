package com.github.starcats.blinkydome.pattern.perlin;


import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Projects a list of points as vectors into a perlin noise space.
 *
 * On every {@link #step(double)}, moves the perlin noise space in the direction of travelVector.  When the projected
 * points sample the perlin noise space, it appears as if they've moved.
 */
public class PerlinNoiseExplorer {

  /** Points' vector multiplier to transform features down into noise space -- smaller makes larger perlin features */
  public final CompoundParameter noiseZoom;

  /** Speed we move through the noise space */
  public final CompoundParameter noiseSpeed;

  /**
   * When picking a random noise travel direction, biases can be tweaked to adjust dominant directions.
   * eg can be set to predominantly travel 'up', whichever direction model defines that
   *
   * If x+ and x- are set to 1 and -1 respectively, perlin noise will be equally likely to travel anywhere in the x dim.
   * If x+ and x- are set to 1 and 0 respectively, perlin noise will only travel in the positive x direction
   */
  public final CompoundParameter xPosBias = new CompoundParameter("x+ bias", 1, 0, 1);
  public final CompoundParameter yPosBias = new CompoundParameter("y+ bias", 1, 0, 1);
  public final CompoundParameter zPosBias = new CompoundParameter("z+ bias", 1, 0, 1);
  public final CompoundParameter xNegBias = new CompoundParameter("x- bias", -1, 0, -1);
  public final CompoundParameter yNegBias = new CompoundParameter("y- bias", -1, 0, -1);
  public final CompoundParameter zNegBias = new CompoundParameter("z- bias", -1, 0, -1);

  // Needed for noise() dependency
  private final PApplet p;

  private final List<LXVector> origFeatures;

  private final LXVector noiseOrigin = new LXVector(0, 0, 0);

  private List<LXVector> features;

  private LXVector noiseTravel;

  private double lastNoiseZoom;



  public PerlinNoiseExplorer(PApplet p, List<? extends LXPoint> features, String prefix, String desc) {
    this(p, features, prefix, desc, null, null);
  }

  public PerlinNoiseExplorer(PApplet p, List<? extends LXPoint> features, String prefix, String desc,
                             CompoundParameter noiseSpeed, CompoundParameter noiseZoom
  ) {
    this.p = p;

    if (noiseZoom == null) {
      this.noiseZoom = new CompoundParameter(prefix + "zoom", 0.01, 0.005, 0.03)
          .setDescription("Multiplier ('zoom') of the perlin noise pattern used for " + desc + " mapping");
    } else {
      this.noiseZoom = noiseZoom;
    }

    lastNoiseZoom = this.noiseZoom.getValue();


    if (noiseSpeed == null) {
      this.noiseSpeed = new CompoundParameter(prefix + "speed", .5)
          .setDescription("The speed of the perlin noise pattern used for " + desc + " mapping");
    } else {
      this.noiseSpeed = noiseSpeed;
    }


    this.origFeatures = features.stream().map(f -> new LXVector(f.x, f.y, f.z)).collect(Collectors.toList());


    // noiseSpeed controls magnitude of noiseTravel vector
    this.randomizeDirection();

    // initialize magnitudes
    lastNoiseZoom = -1;
    step(50);
  }

  public PerlinNoiseExplorer randomizeDirection() {
    this.noiseTravel = new LXVector(
        (float)(Math.random()*(xPosBias.getValue() - xNegBias.getValue()) + xNegBias.getValue()),
        (float)(Math.random()*(yPosBias.getValue() - yNegBias.getValue()) + yNegBias.getValue()),
        (float)(Math.random()*(zPosBias.getValue() - zNegBias.getValue()) + zNegBias.getValue())
    );
    return this;
  }

  /**
   * Sets the travel direction (note: magnitude is ignored, use {@link #noiseSpeed} for speed through noise field)
   * @param travelVector New direction of travel
   */
  public void setTravelVector(LXVector travelVector) {
    this.noiseTravel = travelVector;
  }

  /**
   * @return Get the direction of travel through noise field
   */
  public LXVector getTravelVector() {
    return this.noiseTravel;
  }

  /**
   * Step through the noise field according to travel vector
   */
  public PerlinNoiseExplorer step(double deltaMs) {
    // Re-Map points according to whatever modulation is on the noiseZoom (if it's changed)
    if (noiseZoom.getValue() != lastNoiseZoom) {
      lastNoiseZoom = noiseZoom.getValue();

      this.features = this.origFeatures.stream()
          .map(f -> f.copy().mult(noiseZoom.getValuef()))
          .collect(Collectors.toList());
    }


    // Set the travel vector of the noise field proportional to time elapsed and current zoom level
    float newTravelMag = noiseSpeed.getValuef() * (float) deltaMs * noiseZoom.getValuef() / 2.5f;
    if (newTravelMag != 0) {
      noiseTravel.setMag(newTravelMag);
      noiseOrigin.add(noiseTravel);
    }

    return this;
  }

  public float getNoise(int i) {
    LXVector ptProjection = features.get(i);

    return p.noise(
        noiseOrigin.x + ptProjection.x,
        noiseOrigin.y + ptProjection.y,
        noiseOrigin.z + ptProjection.z
    );
  }


}
