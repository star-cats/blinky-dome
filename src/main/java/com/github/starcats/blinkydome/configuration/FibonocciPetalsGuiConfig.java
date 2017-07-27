package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.model.FibonocciPetalsModel;
import com.github.starcats.blinkydome.pattern.FadecandyLedSelectorPattern;
import com.github.starcats.blinkydome.pattern.fibonocci_petals.FibonocciPetalsLayoutTesterPattern;
import com.github.starcats.blinkydome.pattern.mask.Mask_RollingBouncingDisc;
import com.github.starcats.blinkydome.ui.RollingBouncingDiscAxisViz;
import com.github.starcats.blinkydome.ui.UIGradientPicker;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
import heronarts.p3lx.LXStudio;
import heronarts.p3lx.ui.UI2dScrollContext;
import processing.core.PApplet;

import java.util.Arrays;
import java.util.List;

/**
 * Adds additional gui-only testing patterns to {@link FibonocciPetalsConfig}
 */
public class FibonocciPetalsGuiConfig extends FibonocciPetalsConfig implements StarcatsLxGuiConfig<FibonocciPetalsModel> {

  public FibonocciPetalsGuiConfig(PApplet p) {
    super(p);
  }

  @Override
  protected List<LXPattern> getGuiPatterns() {
    return Arrays.asList(
        new FadecandyLedSelectorPattern(lx),
        new FibonocciPetalsLayoutTesterPattern(lx)
    );
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
