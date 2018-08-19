package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.model.Icosastar;
import com.github.starcats.blinkydome.modulator.MinimBeatTriggers;
import com.github.starcats.blinkydome.ui.UIColorMappingSource;
import com.github.starcats.blinkydome.ui.UIMinimModulator;
import heronarts.p3lx.LXStudio;
import heronarts.p3lx.ui.UI2dScrollContext;
import processing.core.PApplet;

/**
 * LXStudio-based (GUI, not headless) configuration for the {@link Icosastar} model
 */
public class IcosastarGuiConfig extends IcosastarConfig implements StarcatsLxGuiConfig<Icosastar> {

  public IcosastarGuiConfig(PApplet p) {
    super(p);
  }

  @Override
  public void initUI(LXStudio lx, LXStudio.UI ui) {
    // Add modulator UI's
    ui.registry.registerModulatorUI(
            MinimBeatTriggers.class,
            UIMinimModulator::new
    );
  }

  @Override
  public void onUIReady(LXStudio lx, LXStudio.UI ui) {
    // Add custom gradient selector
    UI2dScrollContext container = ui.leftPane.global;
    UIColorMappingSource uiColorMappingSource = new UIColorMappingSource(
        ui, colorSampler, 0, 0, container.getContentWidth());
    uiColorMappingSource.addToContainer(container);


    // Enable audio support
    lx.engine.audio.enabled.setValue(true);
  }
}
