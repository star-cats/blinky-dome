package com.starcats.blinkydome;

import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UIKnob;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.modulation.UIModulator;
import heronarts.lx.studio.ui.modulation.UIModulatorControls;

/**
 * Panel for {@link AmbientTracker}.
 *
 * Same shape as the drive panel, but the caption reports intensity instead of a
 * mood gate -- this one is never gated, so intensity is the only thing that
 * explains the height of the bar.
 */
public class UIAmbientTracker implements UIModulatorControls<AmbientTracker> {

  private static final float METER_WIDTH = 54;

  /** See UIDriveTracker: a horizontal container needs its height set explicitly. */
  private static final float ROW_HEIGHT = UIKnob.HEIGHT + 16;

  @Override
  public void buildModulatorControls(LXStudio.UI ui, UIModulator uiModulator, AmbientTracker tracker) {
    uiModulator.setLayout(UI2dContainer.Layout.HORIZONTAL);
    uiModulator.setChildSpacing(4);
    uiModulator.setContentHeight(ROW_HEIGHT);

    UI2dContainer knobs = UI2dContainer.newHorizontalContainer(ROW_HEIGHT, 4);
    addColumn(knobs, newKnob(tracker.decay));
    addColumn(knobs, newKnob(tracker.depth));
    knobs.addToContainer(uiModulator);

    new UIPulseMeter(ui, METER_WIDTH, ROW_HEIGHT,
      tracker::getValue,
      () -> MoodState.get() == null
        ? "no ctrl"
        : String.format("int %.2f", tracker.getIntensity())).addToContainer(uiModulator);
  }
}
