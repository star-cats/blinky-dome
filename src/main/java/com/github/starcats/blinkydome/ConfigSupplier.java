package com.github.starcats.blinkydome;

import com.github.starcats.blinkydome.configuration.StarcatsLxConfig;
import com.github.starcats.blinkydome.configuration.dlo.DLoPlayaBikeGuiConfig;
import processing.core.PApplet;

/**
 * Configuration-picker class to supply some model and LX configuration
 *
 * TODO: Should ideally be replaced by a classloader or something specified at build time
 */
public class ConfigSupplier {

  public static StarcatsLxConfig getConfig(PApplet p) {
    // Headless:
//    return new BlinkyDomeConfig(p);
//    return new IcosastarConfig(p);
//    return new FibonocciPetalsConfig(p);
//    return new TotemConfig(p);

    // GUI:
//    return new BlinkyDomeGuiConfig(p);
//    return new IcosastarGuiConfig(p);
//    return new FibonocciPetalsGuiConfig(p);
//    return new TotemGuiConfig(p);
    return new DLoPlayaBikeGuiConfig(p);
  }
}