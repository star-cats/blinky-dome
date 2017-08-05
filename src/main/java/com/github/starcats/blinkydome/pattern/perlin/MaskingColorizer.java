package com.github.starcats.blinkydome.pattern.perlin;

import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.EnumParameter;

/**
 * Perlin colorizer used to transform perlin noise into black-and-white colors, suitable for a mask
 */
public class MaskingColorizer extends PerlinNoiseColorizer {

  public enum LevelCurve {
    LINEAR {
      @Override
      public double transform(double in) {
        return in;
      }
    },

    SIN_IN_OUT {
      @Override
      public double transform(double t) {
        return (1. - Math.cos(Math.PI * t)) / 2.;
      }
    },

    CIRCLE_IN {
      @Override
      public double transform(double in) {
        return 1. - Math.sqrt(1. - in * in);
      }
    },

    // This just washes out, not worth it over linear
//    CIRCLE_OUT {
//      @Override
//      public double transform(double in) {
//        return Math.sqrt(1. - --in * in);
//      }
//    },
    EXP_IN_OUT {
      @Override
      public double transform(double t) {
        return ((t *= 2.) <= 1 ? Math.pow(2, 10. * t - 10.) : 2. - Math.pow(2, 10. - 10. * t)) / 2.;
      }
    },

    CLIPPED {
      public double CLIP_RANGE = 0.3;

      @Override
      public double transform(double in) {
        // perlin noise seems to be gaussian around 0.5, make it a bit more extreme
        return Math.max(0, Math.min(1, (in - CLIP_RANGE) / (1. - CLIP_RANGE)));
      }
    };

    public abstract double transform(double in);
  }

  public final EnumParameter<LevelCurve> levelCurveParam;

  public MaskingColorizer(PerlinNoiseExplorer noiseSource) {
    super(noiseSource);

    levelCurveParam = new EnumParameter<>("levels", LevelCurve.EXP_IN_OUT);
  }

  @Override
  public PerlinNoiseColorizer rotate() {
    return this;
  }

  @Override
  public int getColor(LXPoint point) {
    double noiseValue = noiseSource.getNoise(point.index);

    noiseValue = levelCurveParam.getEnum().transform(noiseValue);

    int rgb = (int) (255 * noiseValue);
    return LXColor.rgb(rgb, rgb, rgb);
  }
}
