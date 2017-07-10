package com.github.starcats.blinkydome.pattern;

import com.github.starcats.blinkydome.color.ColorMappingSourceClan;
import com.github.starcats.blinkydome.pattern.perlin.ColorMappingSourceColorizer;
import com.github.starcats.blinkydome.pattern.perlin.LXPerlinNoiseExplorer;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.FunctionalModulator;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXCompoundModulation;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;

import java.util.List;

/**
 * Breathing pattern colorized with perlin noise
 */
public class PerlinBreathing extends LXPattern {

  public final CompoundParameter breathingPeriod;
  public final LXPerlinNoiseExplorer perlinNoiseField;

  private final VariableLFO position;

  private final FunctionalModulator speed;
  private final List<? extends LXPoint> points;
  private final ColorMappingSourceColorizer colorizer;

  private final LXVector up = new LXVector(0, 1, 0);
  private final LXVector down = new LXVector(0, -1, 0);



  public PerlinBreathing(LX lx, PApplet p, List<? extends LXPoint> points, ColorMappingSourceClan colorSource) {
    super(lx);

    this.points = points;

    breathingPeriod = new CompoundParameter("period", 8000, 500, 16000);
    breathingPeriod
        .setDescription("Period (ms) of one breath")
        .setUnits(LXParameter.Units.MILLISECONDS);
    addParameter(breathingPeriod);

    position = new VariableLFO("pos");
    position.waveshape.setValue(VariableLFO.Waveshape.SIN);
    position.setPeriod(breathingPeriod);
    addModulator(position);
    position.start();

//    speed = new VariableLFO("speed");
//    speed.waveshape.setValue(VariableLFO.Waveshape.SIN);
//    speed.setPeriod(breathingPeriod);
//    speed.phase.setValue(0.25); // This shift makes it a cosine, aka derivative of sin.  Speed is derivative of pos
//    addModulator(speed);
//    speed.start();

    FunctionalModulator absSpeed = new FunctionalModulator("abs speed", 0., 1., breathingPeriod) {
      @Override
      public double compute(double basis) {
        double cos = Math.cos(2 * Math.PI * basis);
        return Math.abs(cos);
      }
    };
    addModulator(absSpeed);
    absSpeed.start();
    speed = absSpeed;


    perlinNoiseField = new LXPerlinNoiseExplorer(p, points, "br", "breathing");
    perlinNoiseField.setTravelVector(up);
    addParameter(perlinNoiseField.noiseSpeed);

    this.colorizer = new ColorMappingSourceColorizer(perlinNoiseField, colorSource);

    LXCompoundModulation speedModulation = new LXCompoundModulation(absSpeed, perlinNoiseField.noiseSpeed);
    speedModulation.range.setValue(0.2);

  }

  @Override
  protected void run(double deltaMs) {
    //if (speed.getValue() > 0.5) {
    if ( Math.cos(2 * Math.PI * speed.getBasis()) > 0 ) {
      perlinNoiseField.setTravelVector(up);
    } else {
      perlinNoiseField.setTravelVector(down);
    }


    perlinNoiseField.step(deltaMs);

    for (LXPoint point : points) {
      setColor(point.index, colorizer.getColor(point));
    }
  }
}
