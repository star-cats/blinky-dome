package com.github.starcats.blinkydome.pattern;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.SawLFO;
import heronarts.lx.parameter.DiscreteParameter;

/**
 * Pattern that selects just a single LED from a fadecandy.  Useful for geometry checking
 */
public class FadecandyLedSelectorPattern extends LXPattern {
  public final int LEDS_PER_PORT = 64; // fadecandy

  public final DiscreteParameter ledSelect;
  public final DiscreteParameter portSelect; // Which port to select

  private SawLFO hueModulator = new SawLFO(0, 360, 1000);

  public FadecandyLedSelectorPattern(LX lx) {
    super(lx);

    addModulator(hueModulator).start();

    ledSelect = new DiscreteParameter("LED", 0, lx.model.points.length);

    portSelect = new DiscreteParameter("Port", -1, (int)Math.floor(lx.model.points.length / 64));
    portSelect.addListener(portSelect -> {
      if (portSelect.getValue() < 0) {
        ledSelect.setRange(0, lx.model.points.length);
      } else {
        ledSelect.setRange(0, LEDS_PER_PORT);
      }
      ledSelect.setValue(0);
    });

    addParameter(portSelect);
    addParameter(ledSelect);
  }

  public void run(double deltaMs) {
    int selectedLed;
    if (portSelect.getValue() < 1) {
      selectedLed = ledSelect.getValuei();
    } else {
      selectedLed = 64 * portSelect.getValuei() + ledSelect.getValuei();
    }

    for (LXPoint point : model.getPoints()) {
      if (point.index == selectedLed) {
        colors[point.index] = LXColor.hsb(hueModulator.getValue(), 100, 100);
      } else {
        colors[point.index] = 0;
      }
    }
  }
}
