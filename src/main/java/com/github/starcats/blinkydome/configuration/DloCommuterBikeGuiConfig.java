package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.model.Icosastar;
import com.github.starcats.blinkydome.model.dlo.DloRoadBikeModel;
import com.github.starcats.blinkydome.pattern.mask.Mask_RollingBouncingDisc;
import com.github.starcats.blinkydome.ui.RollingBouncingDiscAxisViz;
import com.github.starcats.blinkydome.ui.UIColorMappingSource;
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
public class DloCommuterBikeGuiConfig extends DloCommuterBikeConfig implements StarcatsLxGuiConfig<DloRoadBikeModel> {

  private LXStudio.UI ui;

  public DloCommuterBikeGuiConfig(PApplet p) {
    super(p);
  }

  @Override
  public void initUI(LXStudio lx, LXStudio.UI ui) {
    this.ui = ui;
  }

  @Override
  protected LX.LXPatternFactory<Mask_RollingBouncingDisc> getRollingBouncingDiscFactory() {
    // RollingBouncingDisc has an accompanying viz.  In GUI, override the factory to add in the viz, if the UI is ready.
    return (lx2, ch, l) -> {
      Mask_RollingBouncingDisc mask = new Mask_RollingBouncingDisc(
              lx2,
              new LXVector(model.cx, model.yMax, model.cz),
              new LXVector(0, model.yMin - model.yMax, 0),
              new LXVector(1, 0, 0)
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

    lx.engine.output.brightness.setValue(0.75); // don't trip power supply breakers
  }
}
