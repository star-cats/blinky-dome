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
public class LXPerlinNoiseExplorer {
  // Needed for noise() dependency
  private PApplet p;

  private List<LXVector> origFeatures;

  private final LXVector noiseOrigin = new LXVector(0, 0, 0);

  private List<LXVector> features;

  private LXVector noiseTravel;


  // Accessible Parameters
  // --------------

  /** Points' vector multiplier to transform features down into noise space -- smaller makes larger perlin features */
  public final CompoundParameter noiseZoom;

  private double lastNoiseZoom;

  /** Speed we move through the noise space */
  public final CompoundParameter noiseSpeed;


  public LXPerlinNoiseExplorer(PApplet p, List<LXPoint> features, String prefix, String desc) {
    this.p = p;


    noiseZoom = new CompoundParameter(prefix + "zoom", 0.01, 0.005, 0.03)
        .setDescription("Multiplier ('zoom') of the perlin noise pattern used for " + desc + " mapping");

    lastNoiseZoom = noiseZoom.getValue();


    noiseSpeed = new CompoundParameter(prefix + "speed", .5)
        .setDescription("The speed of the perlin noise pattern used for " + desc + " mapping");


    this.origFeatures = features.stream().map(f -> new LXVector(f.x, f.y, f.z)).collect(Collectors.toList());


    // noiseSpeed controls magnitude of noiseTravel vector
    this.randomizeDirection();

    // initialize magnitudes
    lastNoiseZoom = -1;
    step(50);
  }

  public LXPerlinNoiseExplorer randomizeDirection() {
    this.noiseTravel = new LXVector(
        // Mostly travel in Z-axis so shapes are coming 'through' the cloud rather than 'across'
        (float)(Math.random()*0.5 - 0.25f),
        (float)(Math.random()*0.5 - 0.25f),
        (float)(Math.random() - 0.5f)
    );
    return this;
  }

  /**
   * Step through the noise field according to travel vector
   */
  public LXPerlinNoiseExplorer step(double deltaMs) {
    // Re-Map points according to whatever modulation is on the noiseZoom (if it's changed)
    if (noiseZoom.getValue() != lastNoiseZoom) {
      lastNoiseZoom = noiseZoom.getValue();

      this.features = this.origFeatures.stream()
          .map(f -> f.copy().mult(noiseZoom.getValuef()))
          .collect(Collectors.toList());
    }


    // Set the travel vector of the noise field proportional to time elapsed and current zoom level
    float newTravelMag = noiseSpeed.getValuef() * (float) deltaMs * noiseZoom.getValuef() / 2.5f;
    if (newTravelMag > 0) {
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
