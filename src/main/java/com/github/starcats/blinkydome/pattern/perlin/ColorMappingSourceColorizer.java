package com.github.starcats.blinkydome.pattern.perlin;

import com.github.starcats.blinkydome.color.ColorMappingSource;
import com.github.starcats.blinkydome.color.ColorMappingSourceClan;
import com.github.starcats.blinkydome.color.ColorMappingSourceGroup;
import heronarts.lx.model.LXPoint;

/**
 * Uses a {@link LXPerlinNoiseExplorer} as the mapping source into a {@link ColorMappingSource}
 */
public class ColorMappingSourceColorizer extends PerlinNoiseColorizer {
  private final ColorMappingSourceGroup colorMappingSourceGroup;
  private final ColorMappingSourceClan colorMappingSourceClan;

  public ColorMappingSourceColorizer(LXPerlinNoiseExplorer noiseSource, ColorMappingSourceGroup colorSource) {
    super(noiseSource);
    this.colorMappingSourceGroup = colorSource;
    this.colorMappingSourceClan = null;
  }

  public ColorMappingSourceColorizer(LXPerlinNoiseExplorer noiseSource, ColorMappingSourceClan colorSource) {
    super(noiseSource);
    this.colorMappingSourceClan = colorSource;
    this.colorMappingSourceGroup = null;
  }

  @Override
  public ColorMappingSourceColorizer rotate() {
    if (colorMappingSourceClan != null) {
      colorMappingSourceClan.setRandomGroupAndSource();
    } else if (colorMappingSourceGroup != null) {
      colorMappingSourceGroup.setRandomSource();
    }

    return this;
  }

  protected ColorMappingSource getColorSource() {
    return colorMappingSourceClan != null ? colorMappingSourceClan : colorMappingSourceGroup;
  }

  @Override
  public int getColor(LXPoint point) {
    return getColorSource()
    .getColor(noiseSource.getNoise(point.index));
  }
}
