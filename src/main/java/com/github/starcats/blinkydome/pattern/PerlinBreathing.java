package com.github.starcats.blinkydome.pattern;

import com.github.starcats.blinkydome.color.ColorMappingSourceClan;
import com.github.starcats.blinkydome.pattern.perlin.ColorMappingSourceColorizer;
import com.github.starcats.blinkydome.pattern.perlin.LXPerlinNoiseExplorer;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.FunctionalModulator;
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

  public final CompoundParameter breathingPeriodMs;
  public final LXPerlinNoiseExplorer perlinNoiseField;
  public final CompoundParameter speedModulationAmount;

  //private final VariableLFO positionLFO;
  private final FunctionalModulator positionLFO;

  private final SpeedModulator speedLFO;
  private final List<? extends LXPoint> points;
  private final ColorMappingSourceColorizer colorizer;
  private final double[] positionLfoValueProvider;

  private final LXVector up;
  private final LXVector down;


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


  public PerlinBreathing(
      LX lx,
      PApplet p,
      List<? extends LXPoint> points,
      ColorMappingSourceClan colorSource,
      LXVector upVector,
      LXVector downVector
  ) {
    super(lx);

    this.points = points;
    this.up = upVector;
    this.down = downVector;

    breathingPeriodMs = new CompoundParameter("period", 8000, 500, 16000);
    breathingPeriodMs
        .setDescription("Period (ms) of one breath")
        .setUnits(LXParameter.Units.MILLISECONDS);
    addParameter(breathingPeriodMs);

//    positionLFO = new VariableLFO("pos");
//    positionLFO.waveshape.setValue(VariableLFO.Waveshape.SIN);
//    positionLFO.setPeriod(breathingPeriodMs);
    positionLFO = new FunctionalModulator("pos", 0, 1, breathingPeriodMs) {
      @Override
      public double compute(double basis) {
        return Math.sin(2 * Math.PI * basis) / 2 + 0.5;
      }
    };
    addModulator(positionLFO);
    positionLFO.start();

    speedLFO = new SpeedModulator("abs speedLFO", 0., 1., breathingPeriodMs) {
      @Override
      public double compute(double basis) {
        // Speed must always be positive (a magnitude), so we get a normalized compute() response by just abs()'ing cosine
        return Math.abs(
            Math.cos(2 * Math.PI * basis)
        );
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

    // provide a memoized reference to each tick's positionLFO.getValue() so can reference it inside colorizer without
    // recalculating it for every pixel
    positionLfoValueProvider = new double[] {0.};

    this.colorizer = new ColorMappingSourceColorizer(perlinNoiseField, colorSource) {
      @Override
      public int getColor(LXPoint point) {
        float noiseValue = noiseSource.getNoise(point.index);

        // Modulate which pixels get colored according to position -- everything gets a color mapping only at full pos
        if (noiseValue > positionLfoValueProvider[0]) {
          return LXColor.BLACK;
        }

        return getColorSource().getColor(noiseValue);
      }
    };


    LXCompoundModulation speedModulation = new LXCompoundModulation(speedLFO, perlinNoiseField.noiseSpeed);


    // TODO: This is only needed b/c P3LX doesn't seem to show the built-in modulation device UI.  Remove if that bug
    // fixed ??
    speedModulationAmount = new CompoundParameter("speed mod");
    speedModulationAmount.setDescription("How quickly perlin noise fields moves on breathing (amount of modulation the " +
        "speedLFO modulator applies to perlin noise speed"
    );
    addParameter(speedModulationAmount);
    speedModulationAmount.addListener(parameter -> {
      speedModulation.range.setValue(parameter.getValue());
    });
    speedModulationAmount.setValue(0.2);


    // The perlin field's speedLFO should always stay at 0.  Idea is the speedLFO modulates the speed parameter,
    // moving it up and down with the up and down motion. The base value should stay at 0.
    perlinNoiseField.noiseSpeed.setValue(0);
    //addParameter(perlinNoiseField.noiseSpeed); // adding it to see value of modulation, but not needed.

  }

  @Override
  protected void run(double deltaMs) {
    positionLfoValueProvider[0] = positionLFO.getValue();

    if ( speedLFO.isNegative() ) {
      perlinNoiseField.setTravelVector(down);
    } else {
      perlinNoiseField.setTravelVector(up);
    }


    perlinNoiseField.step(deltaMs);

    for (LXPoint point : points) {
      setColor(point.index, colorizer.getColor(point));
    }

//    if (positionLFO.getValue() > 0.9999) {
//      System.out.println("SIN MAX");
//    } else if (positionLFO.getValue() < 0.0001) {
//      System.out.println("sin min");
//    }
//
//    if (speedLFO.getValue() > 0.9999) {
//      System.out.println("\t\tCOS MAX");
//    } else if (speedLFO.getValue() < 0.0005) {
//      System.out.println("\t\tcos min");
//    }
//
//    if (positionLFO.getBasis() < 0.01) {
//      System.out.println("\t\t\t\tsin RESET");
//    }
//
//    if (speedLFO.getBasis() < 0.01) {
//      System.out.println("\t\t\t\t\t\tcos RESET");
//    }

  }
}
