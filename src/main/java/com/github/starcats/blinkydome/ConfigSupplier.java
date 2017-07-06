package com.github.starcats.blinkydome;

import com.github.starcats.blinkydome.configuration.IcosastarConfig;
import com.github.starcats.blinkydome.configuration.StarcatsLxConfig;
import processing.core.PApplet;

/**
 * Configuration-picker class to supply same model for both headless and GUI apps
 * in some build config
 */
public class ConfigSupplier {

  public static StarcatsLxConfig getConfig(PApplet p) {
    // Headless:
    //return new BlinkyDomeConfig(p);
    return new IcosastarConfig(p);

    // GUI:
    //return new BlinkyDomeGuiConfig(p);
    //return new IcosastarGuiConfig(p);
  }
}