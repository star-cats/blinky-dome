package com.starcats.blinkydome;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import heronarts.glx.ui.UI;
import heronarts.glx.ui.UI2dComponent;
import heronarts.glx.ui.vg.VGraphics;

/**
 * A vertical bar showing one live 0-1 value, with a caption under it and
 * optionally a line marking a second value the first is chasing.
 *
 * Shared by the supporting trackers, which all need the same thing: see the
 * pulse move, and see why it is the height it is. The caption carries the "why"
 * -- the mood being gated on, the intensity being multiplied in -- because a bar
 * sitting at zero is otherwise indistinguishable from a broken one.
 */
class UIPulseMeter extends UI2dComponent {

  private static final float CAPTION_HEIGHT = 12;
  private static final float BAR_INSET = 2;
  private static final float MARKER_HEIGHT = 1.5f;

  private final DoubleSupplier value;
  private final DoubleSupplier marker;
  private final Supplier<String> caption;

  UIPulseMeter(UI ui, float width, float height, DoubleSupplier value, Supplier<String> caption) {
    this(ui, width, height, value, null, caption);
  }

  /**
   * With a marker: the line is where the bar is heading, so the gap between them
   * is the lag. That gap is the only way to see what a smoothing knob is doing --
   * on its own the bar just moves, and every setting looks equally correct.
   */
  UIPulseMeter(UI ui, float width, float height, DoubleSupplier value, DoubleSupplier marker, Supplier<String> caption) {
    super(0, 0, width, height);
    this.value = value;
    this.marker = marker;
    this.caption = caption;
    setBackgroundColor(ui.theme.controlBackgroundColor);
    setBorderColor(ui.theme.controlBorderColor);
    // Nothing here is a parameter that could invalidate us -- the value moves on
    // its own -- so repaint on the UI loop rather than waiting to be told.
    addLoopTask(deltaMs -> redraw());
  }

  @Override
  public void onDraw(UI ui, VGraphics vg) {
    float barTop = BAR_INSET;
    float barBottom = this.height - CAPTION_HEIGHT - BAR_INSET;
    float barHeight = barBottom - barTop;
    if (barHeight <= 0) {
      return;
    }

    double level = Math.max(0, Math.min(1, this.value.getAsDouble()));
    float filled = (float) (barHeight * level);

    // Track, then fill from the bottom up so it reads like a level meter rather
    // than a progress bar.
    vg.fillColor(ui.theme.controlDisabledColor);
    vg.beginPath();
    vg.rect(BAR_INSET, barTop, this.width - 2 * BAR_INSET, barHeight);
    vg.fill();

    if (filled > 0) {
      vg.fillColor(ui.theme.primaryColor);
      vg.beginPath();
      vg.rect(BAR_INSET, barBottom - filled, this.width - 2 * BAR_INSET, filled);
      vg.fill();
    }

    if (this.marker != null) {
      double at = Math.max(0, Math.min(1, this.marker.getAsDouble()));
      // Clamped so the line stays inside the track at both ends rather than
      // sitting half outside it at 0 and 1.
      float y = Math.min(barBottom - MARKER_HEIGHT, barBottom - (float) (barHeight * at));
      vg.fillColor(ui.theme.controlTextColor);
      vg.beginPath();
      vg.rect(BAR_INSET, y, this.width - 2 * BAR_INSET, MARKER_HEIGHT);
      vg.fill();
    }

    vg.fontFace(ui.theme.getControlFont());
    vg.fillColor(ui.theme.controlTextColor);
    vg.textAlign(VGraphics.Align.CENTER, VGraphics.Align.TOP);
    vg.text(this.width / 2, barBottom + 2, this.caption.get());
  }
}
