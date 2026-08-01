package com.starcats.blinkydome;

import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UIKnob;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.modulation.UIModulator;
import heronarts.lx.studio.ui.modulation.UIModulatorControls;

/**
 * Panel for {@link DropTracker}: one knob and the ramp.
 *
 * This fires rarely by design, so the caption says whether it is armed -- a
 * build in progress means a drop could land at any moment -- rather than leaving
 * a permanently empty meter with nothing to explain it.
 */
public class UIDropTracker implements UIModulatorControls<DropTracker> {

  private static final float METER_WIDTH = 54;

  /** See UIDriveTracker: a horizontal container needs its height set explicitly. */
  private static final float ROW_HEIGHT = UIKnob.HEIGHT + 16;

  @Override
  public void buildModulatorControls(LXStudio.UI ui, UIModulator uiModulator, DropTracker tracker) {
    uiModulator.setLayout(UI2dContainer.Layout.HORIZONTAL);
    uiModulator.setChildSpacing(4);
    uiModulator.setContentHeight(ROW_HEIGHT);

    UI2dContainer knobs = UI2dContainer.newHorizontalContainer(ROW_HEIGHT, 4);
    addColumn(knobs, newKnob(tracker.duration));
    knobs.addToContainer(uiModulator);

    new UIPulseMeter(ui, METER_WIDTH, ROW_HEIGHT,
      tracker::getValue,
      () -> {
        PrimaryController controller = MoodState.get();
        if (controller == null) {
          return "no ctrl";
        }
        return controller.getMood() == Mood.BUILDING ? "armed" : "waiting";
      }).addToContainer(uiModulator);
  }
}
