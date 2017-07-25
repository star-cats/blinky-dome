package com.github.starcats.blinkydome.pattern;


import com.github.starcats.blinkydome.color.ColorMappingSourceClan;
import com.github.starcats.blinkydome.pattern.effects.Sparklers;
import com.github.starcats.blinkydome.pattern.effects.WhiteWipe;
import com.github.starcats.blinkydome.pattern.perlin.ColorMappingSourceColorizer;
import com.github.starcats.blinkydome.pattern.perlin.PerlinNoiseColorizer;
import com.github.starcats.blinkydome.pattern.perlin.PerlinNoiseExplorer;
import com.github.starcats.blinkydome.pattern.perlin.RotatingHueColorizer;
import com.github.starcats.blinkydome.util.AudioDetector;
import ddf.minim.analysis.BeatDetect;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameter;
import processing.core.PApplet;

import java.util.LinkedHashMap;
import java.util.Map;

public class PerlinNoisePattern extends LXPattern {

  private BeatDetect beat;

  private PerlinNoiseExplorer hueNoise;

  /** The speed of the perlin noise pattern used for hue mapping */
  public final CompoundParameter hueSpeed;

  /** Multiplier ('zoom') of the perlin noise pattern used for hue mapping */
  public final CompoundParameter hueXForm;

  /** Selects a colorizer to use */
  public final DiscreteParameter colorizerSelect;
  public final BooleanParameter rotateColorizer;

  private Map<String, PerlinNoiseColorizer> allColorizers;
  private int[] colorizerWeights;
  private int totalWeight;

  private double timeSinceLastRotate = 0;


  // WhiteWipes overlay
  private BooleanParameter triggerWipe;
  private WhiteWipe wiper;
  private WhiteWipe[] allWipes;

  private final Sparklers sparklers;
  private BooleanParameter triggerSparklers = new BooleanParameter("sparkle");

  public PerlinNoisePattern(LX lx, PApplet p, BeatDetect beat, ColorMappingSourceClan colorSamplers) {
    super(lx);

    this.beat = beat;


    // Make Hue Noise
    // -----------------
    this.hueNoise = new PerlinNoiseExplorer(p, this.model.getPoints(), "h ", "hue");

    this.hueSpeed = hueNoise.noiseSpeed;
    addParameter(this.hueSpeed);

    this.hueXForm = hueNoise.noiseZoom;
    addParameter(this.hueXForm);



    // Make colorizers
    // -----------------
    allColorizers = new LinkedHashMap<>(); // LinkedHashMap to maintain same ordering as colorizerWeights
    colorizerWeights = new int[2];

    RotatingHueColorizer rotatingHueColorizer = new RotatingHueColorizer(hueNoise);
    allColorizers.put("rotatingHue", rotatingHueColorizer);
    colorizerWeights[0] = 1; // 1 real pattern, so one weight
    addParameter(
        rotatingHueColorizer.huePeriodMs
        .setDescription("When the rotating hue colorizer is used, the period (ms) of 1 full rotation through the " +
            "color spectrum")
        .setUnits(LXParameter.Units.MILLISECONDS)
    );

    // Colorizer: ColorMappingSource
    ColorMappingSourceColorizer colorMappingColorizer = new ColorMappingSourceColorizer(hueNoise, colorSamplers);
    allColorizers.put("mapping", colorMappingColorizer);
    colorizerWeights[1] = colorSamplers.getNumSources();


    // Register all colorizers
    allColorizers.values().forEach(
        colorizer -> colorizer.getModulators().forEach(this::addModulator)
    );

    int totalWeight = 0;
    for (int w : colorizerWeights) {
      totalWeight += w;
    }
    this.totalWeight = totalWeight;

    colorizerSelect = new DiscreteParameter(
        "clrzr",
        allColorizers.keySet().toArray(new String[allColorizers.keySet().size()])
    );
    colorizerSelect
    .setDescription("Set which colorizer to use")
    .addListener(param -> allColorizers.get(colorizerSelect.getOption()).activate());

    // And do first activation:
    allColorizers.get(colorizerSelect.getOption()).activate();

    addParameter(colorizerSelect);

    rotateColorizer = new BooleanParameter("do rotates", true);
    rotateColorizer.setDescription("Enable or disable rotating through different colorizers on rotate triggers.");
    addParameter(rotateColorizer);


    allWipes = new WhiteWipe[] {
        new WhiteWipe(lx, this, m -> m.yMin, m -> m.yMax, pt -> pt.y),
        new WhiteWipe(lx, this, m -> m.yMax, m -> m.yMin, pt -> pt.y),

        new WhiteWipe(lx, this, m -> m.xMin, m -> m.xMax, pt -> pt.x),
        new WhiteWipe(lx, this, m -> m.xMax, m -> m.xMin, pt -> pt.x),

        new WhiteWipe(lx, this, m -> m.zMin, m -> m.zMax, pt -> pt.z),
        new WhiteWipe(lx, this, m -> m.zMax, m -> m.zMin, pt -> pt.z)
    };

    triggerWipe = new BooleanParameter("doWipe", false);
    triggerWipe.setMode(BooleanParameter.Mode.MOMENTARY);
    addParameter(triggerWipe);
    triggerWipe.addListener(param -> {
      //if (param.getValue() > 0) {
      this.startRandomWipe();
      System.out.println("STARTING WAVE: " + param.getValue());
    });

    sparklers = new Sparklers(this);
    addParameter(triggerSparklers);
    triggerSparklers.addListener(param -> {
      triggerSparklers(param.getValue() > 0);
    });
  }

  public void startRandomWipe() {
    wiper = allWipes[ (int) (Math.random() * allWipes.length) ];
    wiper.start();
  }

  public void triggerSparklers(boolean on) {
    if (on) {
      sparklers.resetSparklers();
    } else {
      sparklers.stopSparklers();
    }
  }

  public void run(double deltaMs) {
//    if (gradientAutoselect != null) {
//      gradientColorSource.patternSelect.setValue(Math.floor(gradientAutoselect.getValue()));
//    }

    PerlinNoiseColorizer colorizer = allColorizers.get(colorizerSelect.getOption());
    //float maxBrightness = ((AbstractIcosaLXModel)model).getMaxBrightness();

    for (LXPoint p : this.model.points) {
      colors[p.index] = colorizer.getColor(p);
    }

    // Rotate colorizers
    boolean doRotate = false;
    if (AudioDetector.LINE_IN.isRunning()) {
      doRotate = beat.isSnare() && beat.isKick() && rotateColorizer.getValueb();
    } else if (rotateColorizer.getValueb()) {
      // No audio?  Rotate probabilistically
      doRotate = Math.random() * 1000000 < timeSinceLastRotate;
    }
    if (doRotate) {
      rotate();
    } else {
      timeSinceLastRotate += deltaMs;
    }

    hueNoise.step(deltaMs);
    //brightnessBoostNoise.step();


    // Apply overlays:
    for (WhiteWipe w : allWipes) {
      w.run(deltaMs);
    }
    sparklers.run(deltaMs);

  }

  public void rotate() {
    PerlinNoiseColorizer colorizer = randomColorizer();
    hueNoise.randomizeDirection();
    colorizer.rotate();
    timeSinceLastRotate = 0;
  }

  private PerlinNoiseColorizer randomColorizer() {
    int rand = (int) (this.totalWeight * Math.random());

    int i=0;
    for (; i<colorizerWeights.length - 1; i++) {
      if (rand < colorizerWeights[i]) {
        break;
      }
    }

    colorizerSelect.setValue(i);

    return allColorizers.get(colorizerSelect.getOption());
  }

}
