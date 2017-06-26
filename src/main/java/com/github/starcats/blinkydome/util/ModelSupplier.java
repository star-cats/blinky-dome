package com.github.starcats.blinkydome.util;

import com.github.starcats.blinkydome.model.BlinkyDome;
import com.github.starcats.blinkydome.model.StarcatsLxModel;
import processing.core.PApplet;

/**
 * Configuration-picker class to supply same model for both headless and GUI apps
 * in some build config
 */
public class ModelSupplier {

  public static StarcatsLxModel getModel(PApplet p, boolean hasGui, DeferredLxOutputProvider outputProvider) {
    return BlinkyDome.makeModel(p, hasGui, outputProvider);
  }
}