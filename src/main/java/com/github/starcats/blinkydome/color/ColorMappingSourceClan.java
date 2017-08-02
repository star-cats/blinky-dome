package com.github.starcats.blinkydome.color;

import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXNormalizedParameter;

/**
 * Represents some a higher-level grouping of {@link ColorMappingSourceFamily}'s.
 *
 * For example, several {@link ImageColorSampler}'s are SourceFamilies that could be grouped into a SourceClan.
 */
public interface ColorMappingSourceClan extends ColorMappingSourceFamily {

  /**
   * @return a DiscreteParameter where the Objects are {@link ColorMappingSourceFamily} entities
   */
  DiscreteParameter getFamilySelect();

  /**
   * @return List of all the families in the clan
   */
  ColorMappingSourceFamily[] getFamilies();

  /**
   * @return Trigger that sets a random source across ALL families
   *   (ie picks a random family AND a random source in that family.
   */
  @Override
  BooleanParameter getRandomSourceTrigger();

  /** @return Trigger that sets a random source in the current family */
  BooleanParameter getRandomSourceInFamilyTrigger();

  /**
   * Returns total number of sources across all families
   */
  @Override
  int getNumSources();

  /**
   * Shortcut to call {@link ColorMappingSource#getColor} against the currently-selected Source in
   * the currently selected family
   *
   * @param normalizedValue Value from 0-1
   * @return A color through some mapping
   */
  @Override
  int getColor(double normalizedValue);

  /**
   * Shortcut to call {@link ColorMappingSource#getColor} against the currently-selected Source in
   * the currently selected family
   *
   * @param normalizedParameter A parameter that can provide a normalized value
   * @return A color through some mapping
   */
  @Override
  int getColor(LXNormalizedParameter normalizedParameter);
}
