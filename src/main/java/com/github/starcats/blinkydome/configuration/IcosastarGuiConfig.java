package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.model.Icosastar;
import com.github.starcats.blinkydome.ui.UIGradientPicker;
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
  public void onUIReady(LXStudio lx, LXStudio.UI ui) {
    // Add custom gradient selector
    UI2dScrollContext container = ui.leftPane.global;
    UIGradientPicker uiGradientPicker = new UIGradientPicker(
        ui, colorSampler, 0, 0, container.getContentWidth());
    uiGradientPicker.addToContainer(container);


    // Enable audio support
    lx.engine.audio.enabled.setValue(true);

    lx.engine.output.brightness.setValue(0.75); // don't trip power supply breakers
  }
}
