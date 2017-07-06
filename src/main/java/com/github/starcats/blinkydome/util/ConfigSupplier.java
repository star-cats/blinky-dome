package com.github.starcats.blinkydome.util;

import com.github.starcats.blinkydome.model.configuration.AbstractStarcatsLxModelConfig;
import com.github.starcats.blinkydome.model.configuration.IcosastarStudioConfig;
import processing.core.PApplet;

/**
 * Configuration-picker class to supply same model for both headless and GUI apps
 * in some build config
 */
public class ConfigSupplier {

  public static AbstractStarcatsLxModelConfig getConfig(PApplet p) {
    //return new BlinkyDomeStudioConfig(p);
    return new IcosastarStudioConfig(p);
  }
}