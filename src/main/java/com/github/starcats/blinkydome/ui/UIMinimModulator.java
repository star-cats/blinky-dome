package com.github.starcats.blinkydome.ui;

import com.github.starcats.blinkydome.configuration.CommonScLxConfigUtils;
import heronarts.lx.LX;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.p3lx.ui.UI;
import heronarts.p3lx.ui.UIModulationSource;
import heronarts.p3lx.ui.component.UIToggleSet;
import heronarts.p3lx.ui.studio.modulation.UIModulator;

/**
 * Minim triggers exposed as modulatable components
 */
public class UIMinimModulator extends UIModulator {

  private static final int HEIGHT = 110;

  public UIMinimModulator(UI ui, LX lx, CommonScLxConfigUtils.MinimBeatTriggers minimTriggers, float x, float y, float w) {
    super(ui, lx, minimTriggers.kickTrigger, false, x, y, w, HEIGHT);

    new UIToggleSet(0, 44, getContentWidth(), 16)
        .setParameter(
            new DiscreteParameter("minim", new String[] {"Kick", "Snare", "Hihat"})
        )
        .addToContainer(this);
  }

  @Override
  protected UIModulationSource getModulationSourceUI() {
    return null;
  }
}
