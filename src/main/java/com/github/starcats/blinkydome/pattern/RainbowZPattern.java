package com.github.starcats.blinkydome.pattern;

import com.github.starcats.blinkydome.model.LED;
import heronarts.lx.LX;
import heronarts.lx.modulator.SinLFO;

/**
 * Created by akesich on 6/23/17.
 */
public class RainbowZPattern extends Pattern {

  SinLFO globalFade = new SinLFO(0, 360, 10000);

  public RainbowZPattern(LX lx) {
    super(lx);
    this.addModulator(globalFade).start();
  }

  public void run(double deltaMs) {
    int c;
    for (LED led : leds) {
      c = lx.hsb(led.z/196 * 360 + globalFade.getValuef(), 100, 80);
      setLEDColor(led, c);
    }
  }
}
