package com.github.starcats.blinkydome.pattern.perlin;

import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.LXModulator;

import java.util.Collections;
import java.util.List;

/**
 * Given a perlin noise source, derives colors onto LXPoints
 */
public abstract class PerlinNoiseColorizer {

  LXPerlinNoiseExplorer noiseSource;

  public PerlinNoiseColorizer(LXPerlinNoiseExplorer noiseSource) {
    this.noiseSource = noiseSource;
  }

  /**
   * @return All modulators used by this colorizer (so pattern can add them)
   */
  public List<LXModulator> getModulators() {
    // Override if applicable
    return Collections.EMPTY_LIST;
  }

  /**
   * Initialize against this NoiseColorizer (apply perlin noise speeds, etc.)
   * @return this instance for chaining
   */
  public PerlinNoiseColorizer activate() {
    // default is no-op, normally adjust noise source params.
    return this;
  }

  /**
   * Advance to the next color scheme
   * @return this instance for chaining
   */
  public abstract PerlinNoiseColorizer rotate();

  public abstract int getColor(LXPoint point);
}
