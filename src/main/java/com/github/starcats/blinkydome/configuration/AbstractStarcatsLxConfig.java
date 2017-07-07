package com.github.starcats.blinkydome.configuration;

import heronarts.lx.LX;
import heronarts.lx.LXChannel;
import heronarts.lx.model.LXModel;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.output.LXOutput;
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
 *
 * @param <M> The type of LXModel this configuration is valid for
 */
public abstract class AbstractStarcatsLxConfig<M extends LXModel> implements StarcatsLxConfig<M> {

  protected final PApplet p;
  protected final M model;
  protected LX lx;

  protected AbstractStarcatsLxConfig(PApplet p) {
    this.p = p;
    this.model = makeModel();
  }

  /**
   * IMPLEMENTATION HOOK: Provide an instance of the config's model
   * @return Model instance
   */
  protected abstract M makeModel();

  @Override
  public M getModel() {
    return model;
  }

  @Override
  public void init(LX lx) {
    this.lx = lx;

    constructOutputs(lx).forEach(lx::addOutput);

    initComponents(p, lx, model);

    constructModulators(p, lx, model).forEach(lx.engine.modulation::addModulator);

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

  /**
   * IMPLEMENTATION HOOK: Return any outputs to be registered with LX
   */
  abstract protected List<LXOutput> constructOutputs(LX lx);

  /**
   * IMPLEMENTATION HOOK: Configure and setup anything specific to this model config that doesn't fit into other impl
   * hooks (eg custom FFT, custom color sources, RaspiGPIO, etc)
   */
  abstract protected void initComponents(PApplet p, LX lx, M model);

  /**
   * IMPLEMENTATION HOOK: Return all modulators that should be added to the LX modulation engine.
   *
   * If using P3LX (GUI), P3LX will create a UI element for each of these.
   *
   * Note: Don't confuse modulators with modulations! Modulations should be added probably during pattern-instantiation,
   * potentially referencing one of the modulators added here.
   */
  protected abstract List<LXModulator> constructModulators(PApplet p, LX lx, M model);

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
}
