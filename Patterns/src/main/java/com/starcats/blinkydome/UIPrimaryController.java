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
 * Panel for {@link PrimaryController}: the controls, then two charts that show
 * what it has concluded and let you check whether to believe it.
 *
 * The charts are the reason this is worth building. A BPM readout and a mood
 * label are both single numbers that look equally confident whether the tracker
 * is locked to the kick or averaging noise. Drawing the sightings against the
 * predicted grid, and the intensity against its own history, turns both into
 * something you can verify at a glance while the music is playing.
 */
public class UIPrimaryController implements UIModulatorControls<PrimaryController> {

  /** Seconds the beat chart spans, left edge to right. */
  private static final float BEAT_WINDOW_SECONDS = 3;

  private static final float BEAT_CHART_HEIGHT = 46;
  private static final float INTENSITY_CHART_HEIGHT = 54;
  private static final float KNOB_ROW_HEIGHT = UIKnob.HEIGHT;
  private static final float CONFIG_ROW_HEIGHT = 34;
  private static final float BEAT_DOT_RADIUS = 2.5f;

  /** Registered beats are red, per the spec -- distinct from the themed grid. */
  private static final int SIGHTING_COLOR = 0xffff4444;

  @Override
  public void buildModulatorControls(LXStudio.UI ui, UIModulator uiModulator, PrimaryController c) {
    uiModulator.setLayout(UI2dContainer.Layout.VERTICAL);
    uiModulator.setChildSpacing(4);

    // Inputs first: these are mapping targets, the things you wire rather than
    // dial, and nothing else works until they are connected.
    UI2dContainer inputs = UI2dContainer.newHorizontalContainer(KNOB_ROW_HEIGHT, 4);
    addColumn(inputs, newKnob(c.low));
    addColumn(inputs, newKnob(c.mid));
    addColumn(inputs, newKnob(c.high));
    inputs.addToContainer(uiModulator);

    UI2dContainer beatConfig = UI2dContainer.newHorizontalContainer(KNOB_ROW_HEIGHT, 4);
    addColumn(beatConfig, newKnob(c.threshold));
    addColumn(beatConfig, newKnob(c.lock));
    addColumn(beatConfig, newKnob(c.shift));
    beatConfig.addToContainer(uiModulator);

    // Tempo config split across two rows so the panel stays readable when
    // the side panel is narrow.
    UI2dContainer tempoConfig1 = UI2dContainer.newHorizontalContainer(CONFIG_ROW_HEIGHT, 4);
    addColumn(tempoConfig1, newDoubleBox(c.minBpm), controlLabel(ui, "Min BPM"));
    addColumn(tempoConfig1, newIntegerBox(c.window), controlLabel(ui, "Avg"));
    addColumn(tempoConfig1, newIntegerBox(c.beatsUntilAmbient), controlLabel(ui, "Amb Bts"));
    addColumn(tempoConfig1, newButton(c.relearn).setTriggerable(true), controlLabel(ui, "Relearn"));
    tempoConfig1.addToContainer(uiModulator);

    UI2dContainer tempoConfig2 = UI2dContainer.newHorizontalContainer(CONFIG_ROW_HEIGHT, 4);
    addColumn(tempoConfig2, newDoubleBox(c.idleThreshold), controlLabel(ui, "Idle Thresh"));
    addColumn(tempoConfig2, newDoubleBox(c.idleDelay), controlLabel(ui, "Idle Delay"));
    addColumn(tempoConfig2, newButton(c.syncTempo), controlLabel(ui, "Sync Tempo"));
    tempoConfig2.addToContainer(uiModulator);

    UI2dContainer octaveRow = UI2dContainer.newHorizontalContainer(CONFIG_ROW_HEIGHT, 4);
    addColumn(octaveRow, newDoubleBox(c.preferredMinBpm), controlLabel(ui, "Prefer Min"));
    addColumn(octaveRow, newDoubleBox(c.preferredMaxBpm), controlLabel(ui, "Prefer Max"));
    octaveRow.addToContainer(uiModulator);

    // Smoothing + band mix — also two rows so it never gets clipped.
    UI2dContainer mix1 = UI2dContainer.newHorizontalContainer(CONFIG_ROW_HEIGHT, 4);
    addColumn(mix1, newDoubleBox(c.charge), controlLabel(ui, "Charge"));
    addColumn(mix1, newDoubleBox(c.discharge), controlLabel(ui, "Release"));
    mix1.addToContainer(uiModulator);

    UI2dContainer mix2 = UI2dContainer.newHorizontalContainer(CONFIG_ROW_HEIGHT, 4);
    addColumn(mix2, newDoubleBox(c.lowWeight), controlLabel(ui, "Lo W"));
    addColumn(mix2, newDoubleBox(c.midWeight), controlLabel(ui, "Mid W"));
    addColumn(mix2, newDoubleBox(c.highWeight), controlLabel(ui, "Hi W"));
    mix2.addToContainer(uiModulator);

    float width = uiModulator.getContentWidth();
    new UIBeatChart(ui, c, width, BEAT_CHART_HEIGHT).addToContainer(uiModulator);
    new UIIntensityChart(ui, c, width, INTENSITY_CHART_HEIGHT).addToContainer(uiModulator);
  }

  /** Shared time-to-x mapping: right edge is now, left edge is windowMs ago. */
  private static float timeToX(double time, double now, double windowMs, float width) {
    return (float) (width * (1 - (now - time) / windowMs));
  }

  /**
   * Sightings against the beat grid, scrolling right to left.
   *
   * Two grids, because there are two different questions. The dashed lines are
   * where the audio beat actually is, and dots landing on those means the
   * tracking is right. The solid lines are where beats are emitted after Shift,
   * and the gap between the two is the offset you dialled in. Drawing only one
   * would make it impossible to tell a mistracked beat from a deliberately
   * shifted one.
   */
  private static class UIBeatChart extends UI2dComponent {

    private final PrimaryController controller;
    private final double[] sightings = new double[64];

    UIBeatChart(UI ui, PrimaryController controller, float width, float height) {
      super(0, 0, width, height);
      this.controller = controller;
      setBackgroundColor(ui.theme.controlBackgroundColor);
      setBorderColor(ui.theme.controlBorderColor);
      addLoopTask(deltaMs -> redraw());
    }

    @Override
    public void onDraw(UI ui, VGraphics vg) {
      BeatClock clock = this.controller.getClock();
      double now = clock.getElapsedMs();
      double period = clock.getPeriodMs();
      double windowMs = BEAT_WINDOW_SECONDS * 1000;

      if (period > 0) {
        // Unshifted: where the audio says the beat is.
        drawGrid(ui, vg, now - clock.getTrackingPhase() * period, period, now, windowMs, true);
        // Shifted: where we actually emit.
        drawGrid(ui, vg, now - clock.getOutputPhase() * period, period, now, windowMs, false);
      }
      drawSightings(vg, clock, now, windowMs);
      drawReadout(ui, vg, period);
    }

    private void drawGrid(UI ui, VGraphics vg, double firstBeat, double period,
                          double now, double windowMs, boolean dashed) {
      vg.strokeColor(dashed ? ui.theme.controlDisabledColor : ui.theme.primaryColor);
      vg.strokeWidth(1);
      double beatTime = firstBeat;
      double earliest = now - windowMs;
      while (beatTime > earliest) {
        float x = timeToX(beatTime, now, windowMs, this.width);
        if (dashed) {
          drawDashed(vg, x);
        } else {
          vg.beginPath();
          vg.moveTo(x, 1);
          vg.lineTo(x, this.height - 1);
          vg.stroke();
        }
        beatTime -= period;
      }
    }

    /** VGraphics has no dash support, so step down the line drawing segments. */
    private void drawDashed(VGraphics vg, float x) {
      final float dash = 3, gap = 3;
      for (float y = 1; y < this.height - 1; y += dash + gap) {
        vg.beginPath();
        vg.moveTo(x, y);
        vg.lineTo(x, Math.min(y + dash, this.height - 1));
        vg.stroke();
      }
    }

    private void drawSightings(VGraphics vg, BeatClock clock, double now, double windowMs) {
      int count = clock.getRecentSightings(this.sightings);
      double earliest = now - windowMs;
      float centerY = this.height / 2;
      vg.fillColor(SIGHTING_COLOR);
      for (int i = 0; i < count; ++i) {
        double time = this.sightings[i];
        if (time < earliest) {
          // Newest first, so everything past here is older still.
          break;
        }
        vg.beginPath();
        vg.circle(timeToX(time, now, windowMs, this.width), centerY, BEAT_DOT_RADIUS);
        vg.fill();
      }
    }

    private void drawReadout(UI ui, VGraphics vg, double period) {
      vg.fontFace(ui.theme.getControlFont());
      vg.fillColor(ui.theme.controlTextColor);
      vg.textAlign(VGraphics.Align.LEFT, VGraphics.Align.TOP);
      vg.text(4, 3, period > 0
        ? String.format("%.1f BPM", this.controller.getBpm())
        : "listening...");
      vg.textAlign(VGraphics.Align.RIGHT, VGraphics.Align.TOP);
      vg.text(this.width - 4, 3,
        String.format("conf %.2f", this.controller.confidence.getValue()));
    }
  }

  /**
   * Smoothed intensity over the last 15 seconds, with the current mood named.
   *
   * The mood machine is driven off this curve, so putting the two together means
   * a transition that looks wrong can be traced to the shape that caused it
   * without instrumenting anything.
   */
  private static class UIIntensityChart extends UI2dComponent {

    private final PrimaryController controller;
    private final double[] times = new double[1024];
    private final double[] values = new double[1024];

    UIIntensityChart(UI ui, PrimaryController controller, float width, float height) {
      super(0, 0, width, height);
      this.controller = controller;
      setBackgroundColor(ui.theme.controlBackgroundColor);
      setBorderColor(ui.theme.controlBorderColor);
      addLoopTask(deltaMs -> redraw());
    }

    @Override
    public void onDraw(UI ui, VGraphics vg) {
      double now = this.controller.getClock().getElapsedMs();
      double windowMs = PrimaryController.HISTORY_SECONDS * 1000;
      int count = this.controller.getIntensityHistory(this.times, this.values);

      if (count > 1) {
        vg.strokeColor(ui.theme.primaryColor);
        vg.strokeWidth(1);
        vg.beginPath();
        boolean started = false;
        // Newest first, so walk backwards to draw left to right.
        for (int i = count - 1; i >= 0; --i) {
          double t = this.times[i];
          if (t < now - windowMs) {
            continue;
          }
          float x = timeToX(t, now, windowMs, this.width);
          float y = (float) (this.height - 1 - this.values[i] * (this.height - 2));
          if (started) {
            vg.lineTo(x, y);
          } else {
            vg.moveTo(x, y);
            started = true;
          }
        }
        if (started) {
          vg.stroke();
        }
      }

      vg.fontFace(ui.theme.getControlFont());
      vg.fillColor(ui.theme.controlTextColor);
      vg.textAlign(VGraphics.Align.LEFT, VGraphics.Align.TOP);
      vg.text(4, 3, this.controller.getMood().toString());
      vg.textAlign(VGraphics.Align.RIGHT, VGraphics.Align.TOP);
      vg.text(this.width - 4, 3, String.format("%.2f", this.controller.getIntensity()));
    }
  }
}
