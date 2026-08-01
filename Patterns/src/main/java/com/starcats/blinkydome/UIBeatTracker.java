package com.starcats.blinkydome;

import heronarts.glx.ui.UI;
import heronarts.glx.ui.UI2dComponent;
import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UIKnob;
import heronarts.glx.ui.vg.VGraphics;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.modulation.UIModulator;
import heronarts.lx.studio.ui.modulation.UIModulatorControls;

/**
 * Device panel for {@link BeatTracker}: the knobs, plus a scrolling chart of
 * what the tracker is actually hearing versus what it believes.
 *
 * The chart is the reason this class exists. A beat tracker either works or it
 * doesn't, and a BPM readout alone cannot tell you which -- 128.0 looks equally
 * convincing whether it is locked to the kick or averaging garbage. Drawing the
 * raw gate sightings against the predicted grid makes the answer immediate: when
 * the circles sit on the lines, it is tracking; when they scatter, the threshold
 * is wrong.
 *
 * Registration takes care of itself. Chromatik's class loader scans package jars
 * and auto-registers anything implementing UIModulatorControls, pairing it with
 * the modulator named in the generic parameter -- so this must implement
 * {@code UIModulatorControls<BeatTracker>} directly, since that is read back off
 * the class's generic interfaces at runtime.
 *
 * Kept apart from BeatTracker rather than folded into it: this half is the only
 * half that touches glx, and leaving the modulator free of UI imports keeps it
 * loadable, and testable, without a UI.
 */
public class UIBeatTracker implements UIModulatorControls<BeatTracker> {

  /** Seconds of history the chart spans, left edge to right. */
  private static final float WINDOW_SECONDS = 3;

  private static final float CHART_HEIGHT = 46;

  /** Knobs carry their own labels, so the row is exactly one knob tall. */
  private static final float KNOB_ROW_HEIGHT = UIKnob.HEIGHT;

  /** A value box stacked over a label. */
  private static final float CONFIG_ROW_HEIGHT = 34;

  /** Radius of a sighting dot. */
  private static final float BEAT_DOT_RADIUS = 2.5f;

  @Override
  public void buildModulatorControls(LXStudio.UI ui, UIModulator uiModulator, BeatTracker tracker) {
    uiModulator.setLayout(UI2dContainer.Layout.VERTICAL);
    uiModulator.setChildSpacing(4);

    // Row one is what you touch while the music is playing. Input comes first
    // because it is the one control you wire rather than dial: it is a
    // modulation target, and the gate gets mapped onto it. Shift goes last,
    // next to the chart it is dialled against.
    UI2dContainer performRow = UI2dContainer.newHorizontalContainer(KNOB_ROW_HEIGHT, 4);
    addColumn(performRow, newKnob(tracker.input));
    addColumn(performRow, newKnob(tracker.threshold));
    addColumn(performRow, newKnob(tracker.lock));
    addColumn(performRow, newKnob(tracker.shift));
    performRow.addToContainer(uiModulator);

    // Row two is set-and-forget: the tempo range and how much history to average.
    UI2dContainer configRow = UI2dContainer.newHorizontalContainer(CONFIG_ROW_HEIGHT, 4);
    addColumn(configRow, newEnumBox(tracker.rate), controlLabel(ui, "Rate"));
    addColumn(configRow, newDoubleBox(tracker.minBpm), controlLabel(ui, "Min BPM"));
    addColumn(configRow, newIntegerBox(tracker.window), controlLabel(ui, "Avg"));
    addColumn(configRow, newButton(tracker.relearn).setTriggerable(true), controlLabel(ui, "Relearn"));
    configRow.addToContainer(uiModulator);

    new UIBeatChart(ui, tracker, uiModulator.getContentWidth(), CHART_HEIGHT)
      .addToContainer(uiModulator);
  }

  /**
   * Sightings and the predicted beat grid, scrolling right to left.
   *
   * Time maps straight to x: the right edge is now, the left edge is
   * WINDOW_SECONDS ago, so everything drifts leftward as the clock advances and
   * falls off the end.
   */
  private static class UIBeatChart extends UI2dComponent {

    private final BeatTracker tracker;

    /** Scratch for the sighting copy, so drawing allocates nothing per frame. */
    private final double[] sightings = new double[64];

    UIBeatChart(UI ui, BeatTracker tracker, float width, float height) {
      super(0, 0, width, height);
      this.tracker = tracker;
      setBackgroundColor(ui.theme.controlBackgroundColor);
      setBorderColor(ui.theme.controlBorderColor);

      // The chart is animated, but nothing it draws is a parameter that could
      // fire a listener -- the clock simply moves. So it repaints on the UI
      // loop instead of waiting to be invalidated.
      addLoopTask(deltaMs -> redraw());
    }

    @Override
    public void onDraw(UI ui, VGraphics vg) {
      double now = this.tracker.getElapsedMs();
      // The grid marks beats as *emitted*, so it follows the output period --
      // twice as many lines at double time, half as many at half.
      double outputPeriodMs = this.tracker.getOutputPeriodMs();
      double windowMs = WINDOW_SECONDS * 1000;

      drawPredictedGrid(ui, vg, now, outputPeriodMs, windowMs);
      drawSightings(ui, vg, now, windowMs);
      drawReadout(ui, vg, outputPeriodMs);
    }

    /**
     * Vertical lines where the tracker believes the beats are.
     *
     * Walked backwards from the current beat rather than forwards from zero, so
     * the grid stays pinned to the live phase instead of accumulating drift
     * from wherever the modulator happened to start.
     */
    private void drawPredictedGrid(UI ui, VGraphics vg, double now, double periodMs, double windowMs) {
      if (periodMs <= 0) {
        return;
      }
      vg.strokeColor(ui.theme.primaryColor);
      vg.strokeWidth(1);
      double beatTime = now - this.tracker.getValue() * periodMs;
      double earliest = now - windowMs;
      while (beatTime > earliest) {
        float x = timeToX(beatTime, now, windowMs);
        vg.beginPath();
        vg.moveTo(x, 1);
        vg.lineTo(x, this.height - 1);
        vg.stroke();
        beatTime -= periodMs;
      }
    }

    /** Circles for what the gate actually reported, filtered or not. */
    private void drawSightings(UI ui, VGraphics vg, double now, double windowMs) {
      int count = this.tracker.getRecentSightings(this.sightings);
      double earliest = now - windowMs;
      float centerY = this.height / 2;
      vg.fillColor(ui.theme.attentionColor);
      for (int i = 0; i < count; ++i) {
        double time = this.sightings[i];
        if (time < earliest) {
          // Newest first, so everything past here is older still.
          break;
        }
        vg.beginPath();
        vg.circle(timeToX(time, now, windowMs), centerY, BEAT_DOT_RADIUS);
        vg.fill();
      }
    }

    private void drawReadout(UI ui, VGraphics vg, double periodMs) {
      vg.fontFace(ui.theme.getControlFont());
      vg.fillColor(ui.theme.controlTextColor);
      vg.textAlign(VGraphics.Align.LEFT, VGraphics.Align.TOP);
      // Always the tracked tempo, never the emission rate -- BPM should mean
      // what the music is doing. The suffix says how often we act on it.
      BeatTracker.Rate rate = this.tracker.rate.getEnum();
      String suffix = rate == BeatTracker.Rate.SINGLE ? "" : "  " + rate + " time";
      vg.text(4, 3, periodMs > 0
        ? String.format("%.1f BPM%s", this.tracker.bpm.getValue(), suffix)
        : "listening...");

      vg.textAlign(VGraphics.Align.RIGHT, VGraphics.Align.TOP);
      vg.text(this.width - 4, 3, String.format("conf %.2f", this.tracker.confidence.getValue()));
    }

    private float timeToX(double time, double now, double windowMs) {
      return (float) (this.width * (1 - (now - time) / windowMs));
    }
  }
}
