package com.github.starcats.blinkydome.ui;

import com.github.starcats.blinkydome.modulator.MinimBeatTriggers;
import heronarts.lx.LX;
import heronarts.p3lx.ui.UI;
import heronarts.p3lx.ui.UIModulationSource;
import heronarts.p3lx.ui.component.UISwitch;
import heronarts.p3lx.ui.studio.modulation.UIModulator;
import heronarts.p3lx.ui.studio.modulation.UITriggerModulationButton;

/**
 * Minim triggers exposed as modulatable components
 */
public class UIMinimModulator extends UIModulator {

  private static final int HEIGHT = 15 + UISwitch.SWITCH_SIZE + 13;

  public UIMinimModulator(UI ui, LX lx, MinimBeatTriggers minimTriggers, float x, float y, float w) {
    super(ui, lx, minimTriggers, true, x, y, w, HEIGHT);

    new UITriggerModulationButton(ui, lx, minimTriggers.kickTrigger,
        UISwitch.WIDTH - UISwitch.SWITCH_MARGIN - TRIGGER_WIDTH, 0, TRIGGER_WIDTH, 12
    )
        .addToContainer(this);
    new UITriggerModulationButton(ui, lx, minimTriggers.snareTrigger,
        UISwitch.WIDTH * 2 - UISwitch.SWITCH_MARGIN - TRIGGER_WIDTH, 0, TRIGGER_WIDTH, 12
    )
        .addToContainer(this);
    new UITriggerModulationButton(ui, lx, minimTriggers.hihatTrigger,
        UISwitch.WIDTH * 3 - UISwitch.SWITCH_MARGIN - TRIGGER_WIDTH, 0, TRIGGER_WIDTH, 12
    )
        .addToContainer(this);

    new UISwitch(0, 15)
        .setParameter(minimTriggers.kickTrigger)
        .addToContainer(this);

    new UISwitch(UISwitch.WIDTH, 15)
        .setParameter(minimTriggers.snareTrigger)
        .addToContainer(this);

    new UISwitch(UISwitch.WIDTH * 2, 15)
        .setParameter(minimTriggers.hihatTrigger)
        .addToContainer(this);
  }

  @Override
  protected UIModulationSource getModulationSourceUI() {
    // Not really a modulator per-say, just a bunch of triggers that can be trigger-modulated
    // Leaving this one null
    return null;
  }
}
