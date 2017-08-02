package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.model.BlinkyDome;
import com.github.starcats.blinkydome.pattern.mask.Mask_RollingBouncingDisc;
import com.github.starcats.blinkydome.ui.RollingBouncingDiscAxisViz;
import com.github.starcats.blinkydome.ui.UIColorMappingSource;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
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
    UIColorMappingSource uiColorMappingSource = new UIColorMappingSource(
        ui, colorSampler, 0, 0, container.getContentWidth());
    uiColorMappingSource.addToContainer(container);


    // Enable audio support
    lx.engine.audio.enabled.setValue(true);


    // Add pattern aids
    for (LXChannel ch : lx.engine.getChannels()) {
      for (LXPattern pattern : ch.getPatterns()) {
        if (!(pattern instanceof Mask_RollingBouncingDisc)) {
          continue;
        }

        RollingBouncingDiscAxisViz viz = new RollingBouncingDiscAxisViz();
        ((Mask_RollingBouncingDisc) pattern).setMonitor(viz);
        ui.preview.addComponent(viz);
      }
    }
  }
}
