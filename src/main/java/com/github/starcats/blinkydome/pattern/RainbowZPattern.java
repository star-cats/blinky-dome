package com.github.starcats.blinkydome.pattern;

import heronarts.lx.LX;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.SinLFO;

/**
 * Simple pattern that uses pixels' z-values to derive a rainbow color
 */
public class RainbowZPattern extends AbstractSimplePattern {

  private SinLFO globalFade = new SinLFO(0, 360, 10000);

  public RainbowZPattern(LX lx) {
    super(lx);
    this.addModulator(globalFade).start();
  }

  public void run(double deltaMs) {
    int c;
    for (LXPoint led : points) {
      c = LX.hsb(led.z/196 * 360 + globalFade.getValuef(), 100, 80);
      setLEDColor(led, c);
    }
  }
}
