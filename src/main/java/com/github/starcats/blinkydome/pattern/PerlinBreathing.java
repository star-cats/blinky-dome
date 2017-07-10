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
  public final CompoundParameter speedModulationAmount;

  private final VariableLFO positionLFO;

  private final SpeedModulator speedLFO;
  private final List<? extends LXPoint> points;
  private final ColorMappingSourceColorizer colorizer;

  private final LXVector up = new LXVector(0, 1, 0);
  private final LXVector down = new LXVector(0, -1, 0);


  private abstract class SpeedModulator extends FunctionalModulator {
    public SpeedModulator(String label, double start, double end, LXParameter periodMs) {
      super(label, start, end, periodMs);
    }

    /**
     * compute() must always return a normalized value 0-1, but this should return true when the current basis mapping
     * is to be interpreted as a negative speedLFO.
     */
    abstract public boolean isNegative();
  }


  public PerlinBreathing(LX lx, PApplet p, List<? extends LXPoint> points, ColorMappingSourceClan colorSource) {
    super(lx);

    this.points = points;

    breathingPeriod = new CompoundParameter("period", 8000, 500, 16000);
    breathingPeriod
        .setDescription("Period (ms) of one breath")
        .setUnits(LXParameter.Units.MILLISECONDS);
    addParameter(breathingPeriod);

    positionLFO = new VariableLFO("pos");
    positionLFO.waveshape.setValue(VariableLFO.Waveshape.SIN);
    positionLFO.setPeriod(breathingPeriod);
    addModulator(positionLFO);
    positionLFO.start();

    speedLFO = new SpeedModulator("abs speedLFO", 0., 1., breathingPeriod) {
      @Override
      public double compute(double basis) {
        // Speed must always be positive, so we get a normalized compute() response by just abs-valueing cosine
        double cos = Math.cos(2 * Math.PI * basis);
        return Math.abs(cos);
      }

      @Override
      public boolean isNegative() {
        // Return true when cosine is negative
        return getBasis() > 0.25 && getBasis() < 0.75;
      }
    };
    addModulator(speedLFO);
    speedLFO.start();


    perlinNoiseField = new LXPerlinNoiseExplorer(p, points, "br", "breathing");
    perlinNoiseField.setTravelVector(up);

    this.colorizer = new ColorMappingSourceColorizer(perlinNoiseField, colorSource);


    LXCompoundModulation speedModulation = new LXCompoundModulation(speedLFO, perlinNoiseField.noiseSpeed);


    // TODO: This is only needed b/c P3LX doesn't seem to show the built-in modulation device UI.  Remove if that bug
    // fixed ??
    speedModulationAmount = new CompoundParameter("speedLFO mod");
    speedModulationAmount.setDescription("Amount of modulation the speedLFO modulator applies to perlin noise speed");
    addParameter(speedModulationAmount);
    speedModulationAmount.addListener(parameter -> {
      speedModulation.range.setValue(parameter.getValue());
    });
    speedModulationAmount.setValue(0.2);


    // The perlin field's speedLFO should always stay at 0.  Idea is the speedLFO modulates the speed parameter,
    // moving it up and down with the up and down motion. The base value should stay at 0.
    perlinNoiseField.noiseSpeed.setValue(0);
    addParameter(perlinNoiseField.noiseSpeed); // adding it to see value of modulation, but not needed.

  }

  @Override
  protected void run(double deltaMs) {
    //if (speedLFO.getValue() > 0.5) {
    if ( speedLFO.isNegative() ) {
      perlinNoiseField.setTravelVector(down);
    } else {
      perlinNoiseField.setTravelVector(up);
    }


    perlinNoiseField.step(deltaMs);

    for (LXPoint point : points) {
      setColor(point.index, colorizer.getColor(point));
    }
  }
}
