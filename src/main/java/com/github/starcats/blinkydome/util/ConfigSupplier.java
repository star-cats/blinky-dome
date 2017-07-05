package com.github.starcats.blinkydome.util;

import com.github.starcats.blinkydome.model.configuration.BlinkyDomeStudioConfig;
import com.github.starcats.blinkydome.model.configuration.StarcatsLxModelConfig;
import processing.core.PApplet;

/**
 * Configuration-picker class to supply same model for both headless and GUI apps
 * in some build config
 */
public class ConfigSupplier {

  public static StarcatsLxModelConfig getConfig(PApplet p) {
    return new BlinkyDomeStudioConfig(p);
  }
}