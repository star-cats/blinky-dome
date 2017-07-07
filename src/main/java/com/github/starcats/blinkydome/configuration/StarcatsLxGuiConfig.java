package com.github.starcats.blinkydome.configuration;

import heronarts.lx.model.LXModel;
import heronarts.p3lx.LXStudio;

/**
 * Defines a configuration that supports P3LX GUI (ie a non-headless configuration).
 */
public interface StarcatsLxGuiConfig<M extends LXModel> extends StarcatsLxConfig<M> {
  /**
   * IMPLEMENTATION HOOK: if using a P3LX / GUI config, add any special things to your GUI.
   */
  void onUIReady(LXStudio lx, LXStudio.UI ui);
}
