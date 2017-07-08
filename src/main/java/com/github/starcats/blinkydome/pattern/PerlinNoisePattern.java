package com.github.starcats.blinkydome.pattern;


import com.github.starcats.blinkydome.color.ColorMappingSourceClan;
import com.github.starcats.blinkydome.pattern.effects.Sparklers;
import com.github.starcats.blinkydome.pattern.effects.WhiteWipe;
import com.github.starcats.blinkydome.pattern.perlin.ColorMappingSourceColorizer;
import com.github.starcats.blinkydome.pattern.perlin.LXPerlinNoiseExplorer;
import com.github.starcats.blinkydome.pattern.perlin.PerlinNoiseColorizer;
import com.github.starcats.blinkydome.pattern.perlin.RotatingHueColorizer;
import com.github.starcats.blinkydome.util.AudioDetector;
import ddf.minim.analysis.BeatDetect;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.parameter.*;
import processing.core.PApplet;
import processing.core.PVector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PerlinNoisePattern extends LXPattern {

  private BeatDetect beat;

  private LXPerlinNoiseExplorer hueNoise;

  /** The speed of the perlin noise pattern used for hue mapping */
  public final CompoundParameter hueSpeed;

  /** Multiplier ('zoom') of the perlin noise pattern used for hue mapping */
  public final CompoundParameter hueXForm;

  public final LXPerlinNoiseExplorer brightnessBoostNoise;
  private float brightnessBoostT = 0;

  /**
   * Exponential dropoff of the brightness boost, per ms.
   * eg in 500ms, the brightness boost will be at Math.pow({bright decay}, 500) %
   */
  private BoundedParameter brightnessBoostDecayPerMs = new BoundedParameter("bb decay", 0.998, 0.990, 0.999)
      .setDescription("Exponential dropoff of the brightness boost, per ms (eg after 123ms, the brightness boost " +
      "will be at Math.pow({bb decay}, 123))");

  /** Selects a colorizer to use */
  public final DiscreteParameter colorizerSelect;
  public final BooleanParameter rotateColorizer;

  private Map<String, PerlinNoiseColorizer> allColorizers;
  private int[] colorizerWeights;
  private int totalWeight;

  private double timeSinceLastRotate = 0;

  public final BoundedParameter maxBrightness = new BoundedParameter("maxBrightness", 100, 0, 100);
  public final BoundedParameter baseBrightnessPct = new BoundedParameter("baseBrightnessPct", 0.30, 0.10, 1.00);


  // WhiteWipes overlay
  private BooleanParameter triggerWipe;
  private WhiteWipe wiper;
  private WhiteWipe[] allWipes;

  private final Sparklers sparklers;
  private BooleanParameter triggerSparklers = new BooleanParameter("sparkle");

  public PerlinNoisePattern(LX lx, PApplet p, BeatDetect beat, ColorMappingSourceClan colorSamplers) {
    super(lx);

    this.beat = beat;

    addParameter(maxBrightness);
    addParameter(baseBrightnessPct);


    // Make Hue Noise
    // -----------------
    List<PVector> leds = this.model.getPoints().stream()
        .map(pt -> new PVector(pt.x, pt.y, pt.z))
        .collect(Collectors.toList());

    this.hueNoise = new LXPerlinNoiseExplorer(p, this.model.getPoints(), "h ", "hue");

    this.hueSpeed = hueNoise.noiseSpeed;
    addParameter(this.hueSpeed);

    this.hueXForm = hueNoise.noiseZoom;
    addParameter(this.hueXForm);


    // Make Noise field
    // -----------------
    this.brightnessBoostNoise = new LXPerlinNoiseExplorer(p, this.model.getPoints(), "bb ",
        "brightness bumps"
    );
    addParameter(brightnessBoostNoise.noiseSpeed);
    addParameter(brightnessBoostNoise.noiseZoom);

    addParameter(brightnessBoostDecayPerMs);


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
    for (PerlinNoiseColorizer colorizer : allColorizers.values()) {
      for (LXModulator modulator : colorizer.getModulators()) {
        addModulator(modulator);
      }
    }

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


    boolean isBrightnessBoost = beat.isKick();
    if (isBrightnessBoost) {
      brightnessBoostT = 1.0f;
    } else if (brightnessBoostT > 0.05) {
      brightnessBoostT *= Math.pow(brightnessBoostDecayPerMs.getValuef(), deltaMs);
    }

    PerlinNoiseColorizer colorizer = allColorizers.get(colorizerSelect.getOption());
    //float maxBrightness = ((AbstractIcosaLXModel)model).getMaxBrightness();
    float maxBrightness = this.maxBrightness.getValuef();
    float baseBrightness = maxBrightness * baseBrightnessPct.getValuef();
    float boostBrightness = maxBrightness * (1.0f - baseBrightnessPct.getValuef());

    for (LXPoint p : this.model.points) {
      int color = colorizer.getColor(p);

      float b = LXColor.b(color);

      if (AudioDetector.LINE_IN.isRunning()) {
        b = baseBrightness * b/100f +
            (brightnessBoostT > 0.05 ?
                brightnessBoostT * boostBrightness * brightnessBoostNoise.getNoise(p.index) :
                0
            );
      } else {
        // If audio not working, just do random brightness mapping
        b = baseBrightness * b/100f +
            boostBrightness * brightnessBoostNoise.getNoise(p.index);
      }

      color = LX.hsb(
          LXColor.h(color),
          LXColor.s(color),
          b
      );

      colors[p.index] = color;

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
