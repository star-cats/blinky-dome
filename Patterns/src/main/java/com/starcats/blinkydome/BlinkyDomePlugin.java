package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXPlugin;
import heronarts.lx.studio.LXStudio;

/**
 * Studio-mode plugin that adds the Blinky Dome's custom preview overlays.
 *
 * Everything in this package that isn't a pattern or effect — the extra 3D
 * rendering we want layered on top of Chromatik's point cloud — hangs off
 * this one plugin.  Right now that's the triangle diffuser; more can be
 * added as more fixture types grow their own visualizations.
 *
 * To activate: Chromatik → Preferences → Plugins, enable "Blinky Dome
 * Diffuser", restart.  The toggle is per-install, not per-project.
 */
@LXPlugin.Name("Blinky Dome Diffuser")
public class BlinkyDomePlugin implements LXStudio.Plugin {

  private UITriangleDiffuser diffuser;

  @Override
  public void initialize(LX lx) {
    // Nothing engine-side yet.
  }

  @Override
  public void initializeUI(LXStudio lx, LXStudio.UI ui) {
    // UI shell is being built; the preview window isn't guaranteed to exist
    // until onUIReady.  Hook in there.
  }

  @Override
  public void onUIReady(LXStudio lx, LXStudio.UI ui) {
    this.diffuser = new UITriangleDiffuser(ui);
    ui.preview.addComponent(this.diffuser);
  }

  @Override
  public void dispose() {
    if (this.diffuser != null) {
      this.diffuser.dispose();
      this.diffuser = null;
    }
  }
}
