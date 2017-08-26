package com.github.starcats.blinkydome.configuration;

import heronarts.lx.LX;
import heronarts.lx.model.LXModel;

import java.util.Optional;

/**
 * Defines a LX configuration to be used by our apps
 */
public interface StarcatsLxConfig<M extends LXModel> {
  /**
   * @return the model created for this configuration
   */
  M getModel();

  /**
   * Initializes against an LX instance, eg
   *   - Add patterns
   *   - Configure channels
   *   - Add modulators
   *   - Any other components
   *
   * @param lx LX instance
   */
  void init(LX lx);

  Optional<String> getLxProjectToLoad();
}
