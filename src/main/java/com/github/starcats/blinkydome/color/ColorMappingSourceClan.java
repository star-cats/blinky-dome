package com.github.starcats.blinkydome.color;

import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXNormalizedParameter;

/**
 * Represents some a higher-level grouping of {@link ColorMappingSourceGroup}'s.
 *
 * For example, several {@link ImageColorSampler}'s are SourceGroups that could be grouped into a SourceClan.
 */
public interface ColorMappingSourceClan extends ColorMappingSourceGroup {

  /**
   * @return a DiscreteParameter where the Objects are {@link ColorMappingSourceGroup} entities
   */
  DiscreteParameter getGroupSelect();

  /**
   * @return List of all the groups in the clan
   */
  ColorMappingSourceGroup[] getGroups();

  /**
   * Picks a random source group and a random source in that group.
   */
  void setRandomGroupAndSource();

  /** Picks a random source in the current group */
  void setRandomSourceInGroup();

  /**
   * Returns total number of sources across all groups
   */
  @Override
  int getNumSources();

  /**
   * Shortcut to call {@link ColorMappingSource#getColor} against the currently-selected Source in
   * the currently selected SourceGroup
   *
   * @param normalizedValue Value from 0-1
   * @return A color through some mapping
   */
  @Override
  int getColor(double normalizedValue);

  /**
   * Shortcut to call {@link ColorMappingSource#getColor} against the currently-selected Source in
   * the currently selected SourceGroup
   *
   * @param normalizedParameter A parameter that can provide a normalized value
   * @return A color through some mapping
   */
  @Override
  int getColor(LXNormalizedParameter normalizedParameter);
}
