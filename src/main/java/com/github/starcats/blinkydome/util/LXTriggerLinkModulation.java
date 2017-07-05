package com.github.starcats.blinkydome.util;

import heronarts.lx.modulator.LXTriggerSource;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.LXTriggerModulation;

/**
 * Whereas {@link LXTriggerModulation} sets a target {@link heronarts.lx.parameter.BooleanParameter} true when the
 * source is true, this modulation also sets the target false when the source is false.
 *
 * Thus, it provides a link between triggers.
 */
public class LXTriggerLinkModulation extends LXTriggerModulation {

  public LXTriggerLinkModulation(LXTriggerSource source, BooleanParameter target) {
    super(source.getTriggerSource(), target);
  }

  @Override
  public void onParameterChanged(LXParameter p) {
    if (p != this.source) {
      return;
    }

    if (this.source.isOn()) {
      this.target.setValue(true);

    } else if (!this.source.isOn()) {
      this.target.setValue(false);
    }
  }
}
