package com.github.starcats.blinkydome.color;

import heronarts.lx.parameter.LXNormalizedParameter;

/**
 * Represents an object that given a normalized value from 0.0 - 1.0, returns some color through some mapping process
 */
public interface ColorMappingSource {

  /**
   * @param normalizedValue Value from 0-1
   * @return A color through some mapping
   */
  int getColor(double normalizedValue);

  /**
   * @param normalizedParameter A parameter that can provide a normalized value
   * @return A color through some mapping
   */
  int getColor(LXNormalizedParameter normalizedParameter);
}
