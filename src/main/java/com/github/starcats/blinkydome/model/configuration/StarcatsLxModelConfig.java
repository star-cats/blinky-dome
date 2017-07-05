package com.github.starcats.blinkydome.model.configuration;

import heronarts.lx.LX;
import heronarts.lx.LXChannel;
import heronarts.lx.modulator.LXModulator;
import heronarts.p3lx.LXStudio;
import processing.core.PApplet;

import java.util.List;

/**
 * Template for how to configure LX against a particular model.
 *
 * Configurations allows, for example,
 *   - different patterns to be used with different models
 *   - patterns to have different defaults/instantiations with different models (eg custom fixtures)
 *   - different wirings to be used in headless vs GUI (P3LX) configurations
 *
 * Implementors should implement/override various implementation hooks with a configuration specific to the model.
 */
public abstract class StarcatsLxModelConfig {

  protected final LX lx;
  protected final PApplet p;

  protected StarcatsLxModelConfig(PApplet p, LX lx) {
    this.p = p;
    this.lx = lx;
  }

  public void init() {
    registerModulators();

    for (int i=0; i<getNumChannels(); i++) {
      LXChannel channel;

      if (i == 0) {
        channel = lx.engine.getChannels().get(0);
      } else {
        channel = lx.engine.addChannel();
      }

      configChannel(i + 1, channel); // arg is 1-indexed like in P3LX, not 0-indexed
    }
  }

  private void registerModulators() {
    constructModulators().forEach(lx.engine.modulation::addModulator);
  }

  /**
   * IMPLEMENTATION HOOK: Return all modulators that should be added to the LX modulation engine.
   *
   * If using P3LX (GUI), P3LX will create a UI element for each of these.
   *
   * Note: Don't confuse modulators with modulations! Modulations should be added probably during pattern-instantiation,
   * potentially referencing one of the modulators added here.
   */
  protected abstract List<LXModulator> constructModulators();

  /**
   * IMPLEMENTATION HOOK: Return the number of channels LX should be configured with (default 1)
   */
  protected int getNumChannels() {
    return 1;
  }

  /**
   * IMPLEMENTATION HOOK: Configures an LX channel, eg
   *   channel.fader.setValue(1): turn it on
   *   channel.setPatterns(...): register channel patterns
   *
   * @param channelNum Which channel number (1-indexed like P3LX, ie "1" is getChannels().get(0)).
   *                 Override getNumChannels() to control how many are created
   * @param channel The Channel instance
   */
  protected abstract void configChannel(int channelNum, LXChannel channel);

  /**
   * IMPLEMENTATION HOOK: if using a P3LX / GUI config, add any special things to your GUI.
   */
  public void onUIReady(LXStudio lx, LXStudio.UI ui) {
    // default no-op / headless
    // TODO: Can we move this to different config so no dependencies on P3LX in headless --> smaller jar packaging?
  }
}
