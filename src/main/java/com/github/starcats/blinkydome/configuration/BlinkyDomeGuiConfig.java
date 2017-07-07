package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.model.BlinkyDome;
import com.github.starcats.blinkydome.ui.UIGradientPicker;
import heronarts.p3lx.LXStudio;
import heronarts.p3lx.ui.UI2dScrollContext;
import processing.core.PApplet;

/**
 * LXStudio-based (GUI, not headless) configuration for the {@link BlinkyDome} model
 */
public class BlinkyDomeGuiConfig extends BlinkyDomeConfig implements StarcatsLxGuiConfig<BlinkyDome> {

  public BlinkyDomeGuiConfig(PApplet p) {
    super(p);
  }

  @Override
  public void onUIReady(LXStudio lx, LXStudio.UI ui) {
    // Add custom gradient selector
    UI2dScrollContext container = ui.leftPane.global;
    UIGradientPicker uiGradientPicker = new UIGradientPicker(
        ui, colorSampler, 0, 0, container.getContentWidth());
    uiGradientPicker.addToContainer(container);


    // Enable audio support
    lx.engine.audio.enabled.setValue(true);
  }
}
