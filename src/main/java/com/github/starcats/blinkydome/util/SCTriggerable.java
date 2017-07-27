package com.github.starcats.blinkydome.util;

import heronarts.lx.parameter.BooleanParameter;

/**
 * Companion to {@link heronarts.lx.modulator.LXTriggerSource} -- semantic indication of something that can be
 * triggered by a LXTriggerSource.
 */
public interface SCTriggerable {

  /**
   * @return The implementation's trigger.
   *
   * Usually should have {@link BooleanParameter#setMode(BooleanParameter.Mode)} set to MOMENTARY.
   */
  BooleanParameter getTrigger();
}
