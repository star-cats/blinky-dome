package com.github.starcats.blinkydome.pattern.perlin;

import com.github.starcats.blinkydome.color.ColorMappingSource;
import com.github.starcats.blinkydome.color.ColorMappingSourceGroup;
import heronarts.lx.model.LXPoint;

/**
 * Uses a {@link PerlinNoiseExplorer} as the mapping source into a {@link ColorMappingSource}
 */
public class ColorMappingSourceColorizer extends PerlinNoiseColorizer {
  private final ColorMappingSourceGroup colorMappingSourceGroup;

  public ColorMappingSourceColorizer(PerlinNoiseExplorer noiseSource, ColorMappingSourceGroup colorSource) {
    super(noiseSource);
    this.colorMappingSourceGroup = colorSource;
  }

  @Override
  public ColorMappingSourceColorizer rotate() {
    colorMappingSourceGroup.setRandomSource();

    return this;
  }

  protected ColorMappingSourceGroup getColorSource() {
    return colorMappingSourceGroup;
  }

  @Override
  public int getColor(LXPoint point) {
    return getColorSource()
    .getColor(noiseSource.getNoise(point.index));
  }
}
