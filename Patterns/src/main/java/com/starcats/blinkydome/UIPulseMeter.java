package com.starcats.blinkydome;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import heronarts.glx.ui.UI;
import heronarts.glx.ui.UI2dComponent;
import heronarts.glx.ui.vg.VGraphics;

/**
 * A vertical bar showing one live 0-1 value, with a caption under it.
 *
 * Shared by the three supporting trackers, which all need the same thing: see
 * the pulse move, and see why it is the height it is. The caption carries the
 * "why" -- the mood being gated on, the intensity being multiplied in -- because
 * a bar sitting at zero is otherwise indistinguishable from a broken one.
 */
class UIPulseMeter extends UI2dComponent {

  private static final float CAPTION_HEIGHT = 12;
  private static final float BAR_INSET = 2;

  private final DoubleSupplier value;
  private final Supplier<String> caption;

  UIPulseMeter(UI ui, float width, float height, DoubleSupplier value, Supplier<String> caption) {
    super(0, 0, width, height);
    this.value = value;
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

    vg.fontFace(ui.theme.getControlFont());
    vg.fillColor(ui.theme.controlTextColor);
    vg.textAlign(VGraphics.Align.CENTER, VGraphics.Align.TOP);
    vg.text(this.width / 2, barBottom + 2, this.caption.get());
  }
}
