package com.github.starcats.blinkydome.modulator;

import com.github.starcats.blinkydome.util.SCTriggerable;
import com.github.starcats.blinkydome.util.StarCatFFT;
import ddf.minim.analysis.BeatDetect;
import heronarts.lx.LX;
import heronarts.lx.LXModulationEngine;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.LXListenableNormalizedParameter;
import heronarts.lx.parameter.LXNormalizedParameter;
import heronarts.lx.parameter.LXTriggerModulation;

/**
 * Triggers for various minim detection events
 */
public class MinimBeatTriggers extends LXModulator {
  public final BooleanParameter kickTrigger = new BooleanParameter("kick")
      .setMode(BooleanParameter.Mode.MOMENTARY)
      .setDescription("Triggers on minim-detected kicks");

  public final BooleanParameter snareTrigger = new BooleanParameter("snare")
      .setMode(BooleanParameter.Mode.MOMENTARY)
      .setDescription("Triggers on minim-detected snares");

  public final BooleanParameter hihatTrigger = new BooleanParameter("hihat")
      .setMode(BooleanParameter.Mode.MOMENTARY)
      .setDescription("Triggers on minim-detected hi-hats");

  private final MinimTriggeredDecay kickDecay = new MinimTriggeredDecay("kick decay");

  private final LXModulationEngine modulationEngine;

  /**
   * Headless constructor -- programmatic modulations are added to default LX modulation engine, don't show up in any gui
   * @param lx LX instance
   * @param minimProvider Provider of a Beat detector
   */
  public MinimBeatTriggers(LX lx, StarCatFFT minimProvider) {
    this(lx, lx.engine.modulation, minimProvider.beat);
  }

  /**
   * GUI constructor -- programmatic modulations can be added to the UI modulation engine
   * @param lx LX or P3LX instance
   * @param modulationEngine If P3LX, should be ui.modulationEngine to make programmatic modulations show up
   * @param minim Beat detector
   */
  public MinimBeatTriggers(LX lx, LXModulationEngine modulationEngine, BeatDetect minim) {
    super("Minim");
    this.modulationEngine = modulationEngine;

    this.addParameter(kickTrigger);
    this.addParameter(snareTrigger);
    this.addParameter(hihatTrigger);
    this.addParameter(kickDecay);

    lx.engine.addLoopTask(deltaMs -> {
      if (!this.isRunning()) return;

      kickTrigger.setValue(minim.isKick());
      snareTrigger.setValue(minim.isSnare());
      hihatTrigger.setValue(minim.isHat());

      if (minim.isKick()) {
        kickDecay.setValue(1.0);
      }
      kickDecay.tick(deltaMs);
    });

    this.running.setValue(true);
  }

  /** See {@link #triggerWithKick(BooleanParameter)} */
  public LXTriggerModulation triggerWithKick(SCTriggerable target) {
    return triggerWithKick(target.getTrigger());
  }

  /**
   * Wires up a new trigger modulation to make a target trigger when a kick-drum hits
   * @param target param to modulate
   * @return The modulation object.  Don't need to do anything with it if you're never going to .onDispose() it.
   */
  public LXTriggerModulation triggerWithKick(BooleanParameter target) {
    LXTriggerModulation trigger = new LXTriggerModulation(kickTrigger, target);
    modulationEngine.addTrigger(trigger);
    return trigger;
  }

  public LXNormalizedParameter getKickDecay() {
    return this.kickDecay;
  }

  @Override
  protected double computeValue(double deltaMs) {
    return kickTrigger.getValue();
  }

  private class MinimTriggeredDecay extends LXListenableNormalizedParameter {

    public MinimTriggeredDecay(String label) {
      super(label, 0);
    }

    @Override
    public LXNormalizedParameter setNormalized(double value) {
      throw new UnsupportedOperationException("TODO");
    }

    @Override
    public double getNormalized() {
      return this.getValue();
    }

    @Override
    public float getNormalizedf() {
      return (float) this.getValuef();
    }

    @Override
    protected double updateValue(double value) {
      return value;
    }

    void tick(double deltaMs) {
      if (getValue() < 0.01) {
        this.setValue(0);
        return;
      }

      setValue(this.getValue() * Math.pow(0.99, deltaMs));
    }
  }
}
