package com.github.starcats.blinkydome.pattern.perlin;

import com.github.starcats.blinkydome.color.ColorMappingSource;
import com.github.starcats.blinkydome.color.ColorMappingSourceClan;
import com.github.starcats.blinkydome.color.ColorMappingSourceGroup;
import heronarts.lx.model.LXPoint;

import java.util.Optional;

/**
 * Uses a {@link LXPerlinNoiseExplorer} as the mapping source into a {@link ColorMappingSource}
 */
public class ColorMappingSourceColorizer extends PerlinNoiseColorizer {
  private final Optional<ColorMappingSourceGroup> colorMappingSourceGroup;
  private final Optional<ColorMappingSourceClan> colorMappingSourceClan;

  public ColorMappingSourceColorizer(LXPerlinNoiseExplorer noiseSource, ColorMappingSourceGroup colorSource) {
    super(noiseSource);
    this.colorMappingSourceGroup = Optional.of(colorSource);
    this.colorMappingSourceClan = Optional.empty();
  }

  public ColorMappingSourceColorizer(LXPerlinNoiseExplorer noiseSource, ColorMappingSourceClan colorSource) {
    super(noiseSource);
    this.colorMappingSourceClan = Optional.of(colorSource);
    this.colorMappingSourceGroup = Optional.empty();
  }

  @Override
  public ColorMappingSourceColorizer rotate() {
    colorMappingSourceClan.ifPresent(ColorMappingSourceClan::setRandomGroupAndSource);
    colorMappingSourceGroup.ifPresent(ColorMappingSourceGroup::setRandomSource);

    return this;
  }

  @Override
  public int getColor(LXPoint point) {
    return colorMappingSourceGroup.orElseGet(colorMappingSourceClan::get)
    .getColor(noiseSource.getNoise(point.index));
  }
}
