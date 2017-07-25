package com.github.starcats.blinkydome.pattern.perlin;

import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;

/**
 * Perlin colorizer used to transform perlin noise into black-and-white colors, suitable for a mask
 */
public class MaskingColorizer extends PerlinNoiseColorizer {

  public MaskingColorizer(PerlinNoiseExplorer noiseSource) {
    super(noiseSource);
  }

  @Override
  public PerlinNoiseColorizer rotate() {
    return this;
  }

  @Override
  public int getColor(LXPoint point) {
    double noiseValue = noiseSource.getNoise(point.index);

    // Add some clipping -- perlin noise seems to be gaussian around 0.5, make it a bit more extreme
    double CLIP_RANGE = 0.3;
    noiseValue = Math.max(0, Math.min(1, (noiseValue - CLIP_RANGE) / (1. - CLIP_RANGE)));

    int rgb = (int) (255 * noiseValue);
    return LXColor.rgb(rgb, rgb, rgb);
  }
}
