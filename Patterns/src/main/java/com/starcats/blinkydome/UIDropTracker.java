package com.starcats.blinkydome;

import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UIKnob;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.modulation.UIModulator;
import heronarts.lx.studio.ui.modulation.UIModulatorControls;

/**
 * Panel for {@link DropTracker}: one knob and the ramp.
 *
 * This fires rarely by design, so the caption explains the silence: counting
 * down while the cooldown holds it off, "armed" when sitting in ambient with a
 * drop available the moment bass returns. A permanently empty meter with no
 * explanation is indistinguishable from a broken one.
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
    addColumn(knobs, newKnob(tracker.cooldown));
    knobs.addToContainer(uiModulator);

    new UIPulseMeter(ui, METER_WIDTH, ROW_HEIGHT,
      tracker::getValue,
      () -> {
        PrimaryController controller = MoodState.get();
        if (controller == null) {
          return "no ctrl";
        }
        double remaining = tracker.getCooldownRemaining();
        if (remaining > 0) {
          // Counting down is the difference between "held off" and "broken".
          return String.format("%.0fs", remaining);
        }
        return controller.getMood() == Mood.AMBIENT ? "armed" : "ready";
      }).addToContainer(uiModulator);
  }
}
