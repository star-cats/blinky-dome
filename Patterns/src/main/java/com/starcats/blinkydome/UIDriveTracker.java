package com.starcats.blinkydome;

import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UIKnob;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.modulation.UIModulator;
import heronarts.lx.studio.ui.modulation.UIModulatorControls;

/**
 * Panel for {@link DriveTracker}: the two knobs and a meter that says what the
 * gate is doing.
 *
 * The caption is the point. This modulator sits at zero most of the time by
 * design, and without something naming the current mood there is no way to tell
 * "correctly gated shut" from "not wired up".
 */
public class UIDriveTracker implements UIModulatorControls<DriveTracker> {

  private static final float METER_WIDTH = 54;
  private static final float ROW_HEIGHT = UIKnob.HEIGHT + 14;

  @Override
  public void buildModulatorControls(LXStudio.UI ui, UIModulator uiModulator, DriveTracker tracker) {
    uiModulator.setLayout(UI2dContainer.Layout.HORIZONTAL);
    uiModulator.setChildSpacing(4);

    UI2dContainer knobs = UI2dContainer.newHorizontalContainer(ROW_HEIGHT, 4);
    addColumn(knobs, newKnob(tracker.decay));
    addColumn(knobs, newKnob(tracker.gateTime));
    knobs.addToContainer(uiModulator);

    new UIPulseMeter(ui, METER_WIDTH, ROW_HEIGHT,
      tracker::getValue,
      () -> {
        PrimaryController controller = MoodState.get();
        if (controller == null) {
          return "no ctrl";
        }
        Mood mood = controller.getMood();
        // Show the gate percentage while it is on the move, so a slow open reads
        // as progress rather than as the meter being stuck.
        double gate = tracker.getGate();
        if (mood == Mood.DRIVING && gate > .95) {
          return "Driving";
        }
        return mood + " " + Math.round(gate * 100) + "%";
      }).addToContainer(uiModulator);
  }
}
