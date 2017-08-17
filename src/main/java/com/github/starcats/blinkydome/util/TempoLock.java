package com.github.starcats.blinkydome.util;

import heronarts.lx.LX;
import heronarts.lx.LXComponent;
import heronarts.lx.LXModulatorComponent;
import heronarts.lx.Tempo;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameterListener;

/**
 * Locks a modulator's period against a {@link heronarts.lx.Tempo} object:
 *   - when the tempo changes, the period changes with it
 *   - when the tempo indicates a beat, the modulator resets
 *
 *
 * TO USE:
 * ---------
 * Make sure to override your dispose() method to call {@link TempoLock#dispose()} -- subcomponents sadly aren't
 * removed automatically (TODO: LX bug? {@link LXComponent#addSubcomponent(LXComponent)} should add a child.dispose() hook?)
 *
 * The {@link #beatIndexTrigger} param can be used to lock the modulator against every beat, measures, mid-measures,
 * etc.
 *
 * Expose a boolean param {@link #enable} that can be used to enable or disable the tempo lock.
 */
public class TempoLock extends LXComponent {

  public final BooleanParameter enable;

  // TODO: beatIndexTrigger must match tempo time to hit measures.  Perhaps add a tempo.measure listener?
  public final DiscreteParameter beatIndexTrigger = new DiscreteParameter("beatI", 1, 1, 9)
          .setDescription("Trigger tempo lock on every ith beat (1 = every beat, 4 = every measure on 4/4 time)");

  private final Tempo tempo;
  private final LXParameterListener tempoPeriodListener;
  private final Tempo.AbstractListener tempoListener;

  private VariableLFO modulatorToLock;

  /**
   * Deferred constructor when no modulatorToLock is available.  Lock will do nothing until setModulatorToLock is called
   */
  public TempoLock(LX lx, String label, Tempo tempo) {
    this(lx, label, tempo, null);
  }

  public TempoLock(LX lx, String label, final Tempo tempo, VariableLFO modulatorToLock) {
    super(lx, label);
    this.tempo = tempo;
    this.modulatorToLock = modulatorToLock;

    enable = new BooleanParameter("tempoLck", false)
        .setDescription(
                "turn Tempo Lock on or off" + (modulatorToLock == null ? "" : " against " + modulatorToLock.getLabel())
        );
//    addParameter(enable);

    enable.addListener(parameter -> {
      if (!enable.getValueb()) return;

      // When the lock is first turned on, set the modulator's period
      syncModulatorPeriod();
    });

    // When the tempo period changes, propagate to the modulator's period
    this.tempoPeriodListener = parameter -> {
      if (!enable.getValueb()) return;

      syncModulatorPeriod();
    };
    tempo.period.addListener(this.tempoPeriodListener);

    // Reset the modulator everytime the tempo changes
    this.tempoListener = new Tempo.AbstractListener() {
      @Override
      public void onBeat(Tempo tempo, int beat) {
        if (!enable.getValueb()) return;

        if (beat % beatIndexTrigger.getValuei() != 0) return;

        TempoLock.this.modulatorToLock.setBasis(1); // will rollover fine, but set to 1 to trigger modulator's loop()
      }
    };
    tempo.addListener(this.tempoListener);
  }

  public void setModulatorToLock(VariableLFO modulatorToLock) {
    this.modulatorToLock = modulatorToLock;
    this.enable.setDescription("turn Tempo Lock on or off against " + modulatorToLock.getLabel());
  }

  private void syncModulatorPeriod() {
    // Set the period to tempo.period (ms / beat) * how many beats we wait to trigger (eg *4 if triggering every measure)
    this.modulatorToLock.period.setValue( this.tempo.period.getValue() * beatIndexTrigger.getValuei() );
    this.modulatorToLock.setBasis( this.tempo.ramp() * beatIndexTrigger.getValuei() );
  }

  @Override
  public void dispose() {
    tempo.period.removeListener(this.tempoPeriodListener);
    tempo.removeListener(this.tempoListener);
    super.dispose();
  }
}
