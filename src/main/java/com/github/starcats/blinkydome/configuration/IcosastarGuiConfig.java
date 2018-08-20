package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.model.Icosastar;
import com.github.starcats.blinkydome.modulator.MinimBeatTriggers;
import com.github.starcats.blinkydome.pattern.mask.Mask_RollingBouncingDisc;
import com.github.starcats.blinkydome.ui.RollingBouncingDiscAxisViz;
import com.github.starcats.blinkydome.ui.UIColorMappingSource;
import com.github.starcats.blinkydome.ui.UIMinimModulator;
import heronarts.lx.LX;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
import heronarts.lx.transform.LXVector;
import heronarts.p3lx.LXStudio;
import heronarts.p3lx.ui.UI2dScrollContext;
import processing.core.PApplet;

/**
 * LXStudio-based (GUI, not headless) configuration for the {@link Icosastar} model
 */
public class IcosastarGuiConfig extends IcosastarConfig implements StarcatsLxGuiConfig<Icosastar> {

  private LXStudio.UI ui;

  public IcosastarGuiConfig(PApplet p) {
    super(p);
  }

  @Override
  public void initUI(LXStudio lx, LXStudio.UI ui) {
    this.ui = ui;

    // Add modulator UI's
    ui.registry.registerModulatorUI(
            MinimBeatTriggers.class,
            UIMinimModulator::new
    );
  }

  @Override
  protected LX.LXPatternFactory<Mask_RollingBouncingDisc> getRollingBouncingDiscFactory() {
    // RollingBouncingDisc has an accompanying viz.  In GUI, override the factory to add in the viz, if the UI is ready.
    return (lx2, ch, l) -> {
      Mask_RollingBouncingDisc mask = new Mask_RollingBouncingDisc(
              lx2,
              new LXVector(model.cx, model.cy, model.zMax),
              new LXVector(0, 0, model.zMin - model.zMax),
              new LXVector(0, 1, 0)
      );

      // Skip the disc on the initial creation (ui won't be ready, see onUIReady()).  But when deserializing from
      // JSON, this will hit
      if (ui != null && ui.preview != null) {
        RollingBouncingDiscAxisViz viz = new RollingBouncingDiscAxisViz() {
          @Override
          protected void onDispose() {
            ui.preview.removeComponent(this);
          }
        };
        mask.setMonitor(viz);
        ui.preview.addComponent(viz);
      }

      return mask;
    };
  }

  @Override
  public void onUIReady(LXStudio lx, LXStudio.UI ui) {
    // Add custom gradient selector
    UI2dScrollContext container = ui.leftPane.global;
    UIColorMappingSource uiColorMappingSource = new UIColorMappingSource(
        ui, colorSampler, 0, 0, container.getContentWidth());
    uiColorMappingSource.addToContainer(container);


    // Add pattern aids
    for (LXChannel ch : lx.engine.getChannels()) {
      for (LXPattern pattern : ch.getPatterns()) {
        if (!(pattern instanceof Mask_RollingBouncingDisc)) {
          continue;
        }

        RollingBouncingDiscAxisViz viz = new RollingBouncingDiscAxisViz() {
          @Override
          protected void onDispose() {
            ui.preview.removeComponent(this);
          }
        };
        ((Mask_RollingBouncingDisc) pattern).setMonitor(viz);
        ui.preview.addComponent(viz);
      }
    }


    // Enable audio support
    lx.engine.audio.enabled.setValue(true);
  }
}
