package com.github.starcats.blinkydome.color;

import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXNormalizedParameter;

/**
 * Represents some tight logical grouping of {@link ColorMappingSource}'s.
 *
 * For example, an {@link ImageColorSampler} is a {@link ColorMappingSource} that groups all the sources in a particular
 * sample-able image (eg gradients in gradients.png, patterns in patterns.png).
 */
public interface ColorMappingSourceFamily extends ColorMappingSource {

  /**
   * @return a DiscreteParameter where the Objects are {@link ColorMappingSource} entities
   */
  DiscreteParameter getSourceSelect();

  /**
   * Changes the sourceSelect to a random source
   */
  BooleanParameter getRandomSourceTrigger();

  /**
   * Returns how many sources this group contains
   */
  int getNumSources();

  /**
   * Shortcut to call {@link ColorMappingSource#getColor} against the currently-selected source in
   * the sourceSelect DiscreteParameter
   *
   * @param normalizedValue Value from 0-1
   * @return A color through some mapping
   */
  @Override
  int getColor(double normalizedValue);

  /**
   * Shortcut to call {@link ColorMappingSource#getColor} against the currently-selected source in
   * the sourceSelect DiscreteParameter
   *
   * @param normalizedParameter A parameter that can provide a normalized value
   * @return A color through some mapping
   */
  @Override
  int getColor(LXNormalizedParameter normalizedParameter);
}
