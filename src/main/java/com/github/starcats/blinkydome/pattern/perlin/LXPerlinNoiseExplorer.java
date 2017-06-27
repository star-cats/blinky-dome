package com.github.starcats.blinkydome.pattern.perlin;


import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.LXParameterListener;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;

import java.util.List;
import java.util.stream.Collectors;

public class LXPerlinNoiseExplorer {
  // Needed for noise() dependency
  private PApplet p;

  private List<LXVector> origFeatures;

  private final LXVector noiseOrigin = new LXVector(0, 0, 0);

  private List<LXVector> features;

  private LXVector noiseTravel;


  // Accessible Parameters
  // --------------

  /** Vector multiplier to transform features down into noise space -- smaller makes larger perlin features */
  public final BoundedParameter noiseXForm;

  /** Speed we move through the noise space */
  public final BoundedParameter noiseSpeed;


  public LXPerlinNoiseExplorer(PApplet p, List<LXPoint> features, String prefix) {
    this.p = p;

    noiseXForm = new BoundedParameter(prefix + "xform", 0.01, 0.005, 0.03);
    noiseSpeed = new BoundedParameter(prefix + "speed", 0.02, 0.005, 0.1);

    this.origFeatures = features.stream().map(f -> new LXVector(f.x, f.y, f.z)).collect(Collectors.toList());

    LXPerlinNoiseExplorer me = this;


    // noiseXForm controls 'zoom' into perlin noise field
    LXParameterListener onNoiseXformChange = noiseXForm -> me.features = this.origFeatures.stream()
        .map(f -> f.copy().mult(noiseXForm.getValuef()))
        .collect(Collectors.toList());

    noiseXForm.addListener(onNoiseXformChange);
    onNoiseXformChange.onParameterChanged(noiseXForm); // initialize feature


    // noiseSpeed controls magnitude of noiseTravel vector
    this.randomizeDirection();
    noiseSpeed.addListener(noiseSpeed -> me.noiseTravel.setMag(noiseSpeed.getValuef()));
  }

  public LXPerlinNoiseExplorer randomizeDirection() {
    this.noiseTravel = new LXVector(
        // Mostly travel in Z-axis so shapes are coming 'through' the cloud rather than 'across'
        (float)(Math.random()*0.5 - 0.25f),
        (float)(Math.random()*0.5 - 0.25f),
        (float)(Math.random() - 0.5f)
    )
    .setMag(noiseSpeed.getValuef());
    return this;
  }

  /**
   * Step through the noise field according to travel vector
   * @return
   */
  public LXPerlinNoiseExplorer step() {
    noiseOrigin.add(noiseTravel);

    // TODO: decay zoom

    return this;
  }

  public float getNoise(int i) {
    LXVector noiseVect = noiseOrigin.copy().add(features.get(i));

    return p.noise(noiseVect.x, noiseVect.y, noiseVect.z);
  }


}
