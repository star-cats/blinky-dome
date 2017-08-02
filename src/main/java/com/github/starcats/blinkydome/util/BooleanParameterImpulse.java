package com.github.starcats.blinkydome.util;

import heronarts.lx.LXPattern;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.parameter.BooleanParameter;

/**
 * Utility that's like a {@link heronarts.lx.parameter.LXTriggerModulation} -- it sets a target
 * {@link heronarts.lx.parameter.BooleanParameter} on and off -- but implemented as a self-registering modulator
 * for patterns that they can call to do a 1-cycle toggle of a boolean param.
 *
 * Basically a programatic, no-UI {@link heronarts.p3lx.ui.component.UISwitch} for a momentary BooleanParameter.
 *
 * Call {@link #trigger()} to trigger an impulse
 */
public class BooleanParameterImpulse extends LXModulator {

  public static BooleanParameterImpulse makeImpulseFor(BooleanParameter target, LXPattern parent, String label) {
    BooleanParameterImpulse impulse = new BooleanParameterImpulse(label, target);

    parent.addModulator(impulse);

    return impulse;
  }

  private enum State {
    OFF,
    TRIGGERED,
    RELEASING
  }

  private final BooleanParameter target;
  private State state = State.OFF;


  /** constructor */
  private BooleanParameterImpulse(String label, BooleanParameter target) {
    super(label);

    this.target = target;
  }

  /** Called by {@link #trigger()}*/
  @Override
  protected void onReset() {
    this.state = State.TRIGGERED;
    target.setValue(true);
  }


  @Override
  protected double computeValue(double deltaMs) {
    if (state == State.TRIGGERED) {
      // wait for next cycle so any bound UI's can see it's on
      state = State.RELEASING;

    } else if (state == State.RELEASING) {
      state = State.OFF;
      target.setValue(false);
    }

    return target.getValue();
  }
}
