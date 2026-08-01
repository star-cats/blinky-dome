package com.starcats.blinkydome;

import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UIKnob;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.modulation.UIModulator;
import heronarts.lx.studio.ui.modulation.UIModulatorControls;

/**
 * Panel for {@link IntensityTracker}: the window, the smoothing, and a meter
 * showing all three stages of what they do.
 *
 * The bar is the output, the line across it is the windowed target the bar is
 * chasing, and the caption is the raw controller intensity going in. Between
 * them every knob on the panel is visible: whether Min and Max are pinning the
 * target at an end -- the number moving while the line sits still -- and how far
 * Smooth is holding the bar behind it.
 */
public class UIIntensityTracker implements UIModulatorControls<IntensityTracker> {

  private static final float METER_WIDTH = 54;

  /** See UIDriveTracker: a horizontal container needs its height set explicitly. */
  private static final float ROW_HEIGHT = UIKnob.HEIGHT + 16;

  @Override
  public void buildModulatorControls(LXStudio.UI ui, UIModulator uiModulator, IntensityTracker tracker) {
    uiModulator.setLayout(UI2dContainer.Layout.HORIZONTAL);
    uiModulator.setChildSpacing(4);
    uiModulator.setContentHeight(ROW_HEIGHT);

    UI2dContainer knobs = UI2dContainer.newHorizontalContainer(ROW_HEIGHT, 4);
    addColumn(knobs, newKnob(tracker.min));
    addColumn(knobs, newKnob(tracker.max));
    addColumn(knobs, newKnob(tracker.smooth));
    knobs.addToContainer(uiModulator);

    new UIPulseMeter(ui, METER_WIDTH, ROW_HEIGHT,
      tracker::getValue,
      tracker::getTarget,
      () -> MoodState.get() == null
        ? "no ctrl"
        : String.format("in %.2f", tracker.getInput())).addToContainer(uiModulator);
  }
}
