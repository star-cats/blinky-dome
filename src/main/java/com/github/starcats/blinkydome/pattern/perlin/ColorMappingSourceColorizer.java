package com.github.starcats.blinkydome.pattern.perlin;

import com.github.starcats.blinkydome.color.ColorMappingSource;
import com.github.starcats.blinkydome.color.ColorMappingSourceFamily;
import heronarts.lx.model.LXPoint;

/**
 * Uses a {@link PerlinNoiseExplorer} as the mapping source into a {@link ColorMappingSource}
 */
public class ColorMappingSourceColorizer extends PerlinNoiseColorizer {
  private final ColorMappingSourceFamily colorMappingSourceFamily;

  public ColorMappingSourceColorizer(PerlinNoiseExplorer noiseSource, ColorMappingSourceFamily colorSource) {
    super(noiseSource);
    this.colorMappingSourceFamily = colorSource;
  }

  @Override
  public ColorMappingSourceColorizer rotate() {
    colorMappingSourceFamily.getRandomSourceTrigger();

    return this;
  }

  protected ColorMappingSourceFamily getColorSource() {
    return colorMappingSourceFamily;
  }

  @Override
  public int getColor(LXPoint point) {
    return getColorSource()
    .getColor(noiseSource.getNoise(point.index));
  }
}
