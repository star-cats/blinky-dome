package com.github.starcats.blinkydome.pattern;


import com.github.starcats.blinkydome.color.ColorMappingSourceClan;
import com.github.starcats.blinkydome.pattern.effects.Sparklers;
import com.github.starcats.blinkydome.pattern.effects.WhiteWipe;
import com.github.starcats.blinkydome.pattern.perlin.ColorMappingSourceColorizer;
import com.github.starcats.blinkydome.pattern.perlin.PerlinNoiseExplorer;
import com.github.starcats.blinkydome.util.AudioDetector;
import ddf.minim.analysis.BeatDetect;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import processing.core.PApplet;

public class PerlinNoisePattern extends LXPattern {

  private BeatDetect beat;

  private PerlinNoiseExplorer hueNoise;

  /** The speed of the perlin noise pattern used for hue mapping */
  public final CompoundParameter hueSpeed;

  /** Multiplier ('zoom') of the perlin noise pattern used for hue mapping */
  public final CompoundParameter hueXForm;

  private final ColorMappingSourceColorizer colorMappingColorizer;

  private double time = 0;


  // WhiteWipes overlay
  private BooleanParameter triggerWipe;
  private WhiteWipe wiper;
  private WhiteWipe[] allWipes;

  private final Sparklers sparklers;
  private BooleanParameter triggerSparklers = new BooleanParameter("sparkle");

  private double timeSinceLastRotate = 0;

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

    addParameter(
        new BooleanParameter("randir", false)
        .setMode(BooleanParameter.Mode.MOMENTARY)
        .setDescription("Set random direction for perlin travel")
        .addListener(param -> {
          if (param.getValue() == 0) return;

          hueNoise.randomizeDirection();
        })
    );


    // Make ColorMappingSource colorizer
    this.colorMappingColorizer = new ColorMappingSourceColorizer(hueNoise, colorSamplers);
    this.colorMappingColorizer.getModulators().forEach(this::addModulator);
    this.colorMappingColorizer.activate();


    // EFFECTS
    // ------------

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

    // HEYDAN Debugging audio
//    time += deltaMs;
//    if (time > 1000) {
//      time = 0;
//      System.out.println("Audio engine enabled? " + this.lx.engine.audio.enabled.getValueb());
//      System.out.println("Band 2 level: " + this.lx.engine.audio.meter.getDecibels(2));
//    }

    for (LXPoint p : this.model.points) {
      colors[p.index] = this.colorMappingColorizer.getColor(p);
    }

    hueNoise.step(deltaMs);
    //brightnessBoostNoise.step();


    // Change direction every few beats to keep things interesting
    boolean doRotate;
    if (AudioDetector.LINE_IN.isRunning()) {
      doRotate = beat.isSnare() && beat.isKick() && Math.random() < .1;
    } else {
      // No audio?  Rotate probabilistically
      doRotate = Math.random() * 1_000_000 < timeSinceLastRotate;
    }
    if (doRotate) {
      hueNoise.randomizeDirection();
      timeSinceLastRotate = 0;
    } else {
      timeSinceLastRotate += deltaMs;
    }


    // Apply overlays:
    for (WhiteWipe w : allWipes) {
      w.run(deltaMs);
    }
    sparklers.run(deltaMs);

  }


}
