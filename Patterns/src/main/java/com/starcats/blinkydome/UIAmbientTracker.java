package com.starcats.blinkydome;

import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UIKnob;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.modulation.UIModulator;
import heronarts.lx.studio.ui.modulation.UIModulatorControls;

/**
 * Panel for the always-on forecast pulse from {@link AmbientTracker}.
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
    addColumn(knobs, newKnob(tracker.output));
    addColumn(knobs, newKnob(tracker.decay));
    knobs.addToContainer(uiModulator);

    new UIPulseMeter(ui, METER_WIDTH, ROW_HEIGHT,
      tracker::getValue,
      () -> MoodState.get() == null
        ? "no ctrl"
        : String.format("%.2f", tracker.getValue())).addToContainer(uiModulator);
  }
}
