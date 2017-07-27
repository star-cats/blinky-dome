package com.github.starcats.blinkydome.util;

import heronarts.lx.Tempo;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.parameter.BooleanParameter;

/**
 * Locks a modulator's period against a {@link heronarts.lx.Tempo} object:
 *   - when the tempo changes, the period changes with it
 *   - when the tempo indicates a beat, the modulator resets
 *
 * Expose a boolean param {@link #enableLock} that can be used to enable or disable the tempo lock.
 */
public class TempoLock {

  public final BooleanParameter enableLock;

  public TempoLock(final Tempo tempo, final VariableLFO modulator) {
    enableLock = new BooleanParameter("tempoLck", false)
        .setDescription("turn Tempo Lock on or off against " + modulator.getLabel());

    // When the lock is first turned on, set the modulator's period
    enableLock.addListener(parameter -> {
      if (!enableLock.getValueb()) return;

      modulator.period.setValue(tempo.period.getValue());
    });

    // When the tempo period changes, propagate to the modulator's period
    tempo.period.addListener(parameter -> {
      if (!enableLock.getValueb()) return;

      modulator.period.setValue(parameter.getValue());
    });

    // Reset the modulator everytime the tempo changes
    tempo.addListener(new Tempo.AbstractListener() {
      @Override
      public void onBeat(Tempo tempo, int beat) {
        if (!enableLock.getValueb()) return;

        modulator.setBasis(1); // will rollover fine, but set to 1 to trigger modulator loop()
      }
    });
  }
}
