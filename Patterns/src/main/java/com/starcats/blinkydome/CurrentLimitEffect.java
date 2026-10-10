package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.effect.LXEffect;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.BoundedParameter;

/**
 * Current limiter — a fader-mask that only engages when the frame as a whole
 * would draw too much current.
 *
 * The problem it solves: an undersized PSU + a frame full of near-white or
 * low-saturation pixels pulls the rail down, LEDs flicker, controller
 * sometimes browns out.  Headroom-based dimming helps, but a flat dim leaves
 * saturated frames looking dull even though they're well under the budget.
 *
 * What this does instead: every frame it measures the average of
 * (R+G+B)/3 over all pixels on the bus — that's a direct proxy for current
 * draw if every channel sinks the same per-value current, which is the usual
 * approximation for WS2812-style strips.  Compare that "load" to a user-set
 * Budget.  Only when load exceeds the budget does the effect start pulling,
 * and only hard enough to bring load back to the budget.  Fully saturated
 * frames (R=255, G=B=0) run at load ≈ 0.33 of a solid-white frame and are
 * usually safe even at modest budgets.
 *
 * How it pulls: two selectable modes.
 *
 *   Dim          — scale every RGB channel by (budget / load).  Hue is
 *                  preserved exactly, brightness proportional to overage.
 *                  Classic power-limiter behavior.
 *
 *   Desaturate   — subtract the pixel's minimum channel (times the overage
 *                  factor) from all three channels.  This pulls each pixel
 *                  toward its own primary color, cutting the "white part"
 *                  of its spectrum without touching the chroma that
 *                  defines its hue.  Dim-looking frames stay visually
 *                  colorful; perceived brightness is less affected.
 *
 * Shaping: a two-pole response (fast attack, slow release) keeps a frame
 * that briefly peaks from making the whole picture pump up and down.  Attack
 * is milliseconds — kick in before the PSU sags.  Release is seconds — ease
 * off as the content thins.
 *
 * Read-only Load output shows the live metric so you can watch the margin.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Current Limit")
@LXComponent.Description(
  "Dim or desaturate only when the frame's total RGB load exceeds a budget — protects undersized PSUs from flicker on near-white content.")
public class CurrentLimitEffect extends LXEffect {

  // ── Parameters ────────────────────────────────────────────────────────────

  public final CompoundParameter budget =
    new CompoundParameter("Budget", 0.5, 0.05, 1.0)
    .setDescription(
      "Max allowed average of (R+G+B)/3 across every pixel. 1.0 = no limit; 0.33 = equivalent current of a solid saturated color.");

  /** Dim vs. Desaturate. Int param because the UI shows this as a selector. */
  public final BooleanParameter desaturateMode =
    new BooleanParameter("Desat", false)
    .setDescription(
      "When ON, over-budget frames pull toward primary colors (keeps chroma, drops the 'white part'). When OFF, every channel is scaled down (classic dim).");

  public final CompoundParameter attackMs =
    new CompoundParameter("Attack", 30, 1, 500)
    .setDescription(
      "How quickly the limiter clamps down when a frame exceeds budget. Shorter = catches peaks faster, but can make limiting audible as flicker on bass-synced frames.");

  public final CompoundParameter releaseMs =
    new CompoundParameter("Release", 400, 10, 5000)
    .setDescription(
      "How slowly the limiter unclamps when the frame drops back under budget. Longer = smoother; avoids pumping on strobe-like content.");

  /**
   * Live display of the frame's current load — the mean of (R+G+B)/(3*255)
   * across every pixel.  Written each frame by {@link #run}.  Exposed as a
   * normal {@link BoundedParameter} on 0..1 so Chromatik renders it as a
   * meter in the control strip.  It isn't user-driven; the engine overwrites
   * whatever a user drags it to on the next frame.
   */
  public final BoundedParameter load =
    new BoundedParameter("Load", 0, 0, 1)
    .setDescription(
      "Live average of (R+G+B)/3 across every pixel on this bus. The limiter engages only when this value exceeds Budget.");

  // ── Internal state ────────────────────────────────────────────────────────

  /** The scale currently being applied, after smoothing. 1.0 = no limiting. */
  private double smoothedScale = 1.0;

  public CurrentLimitEffect(LX lx) {
    super(lx);
    addParameter("budget", this.budget);
    addParameter("desaturate", this.desaturateMode);
    addParameter("attack", this.attackMs);
    addParameter("release", this.releaseMs);
    addParameter("load", this.load);
  }

  @Override
  protected void run(double deltaMs, double enabledAmount) {
    final int n = this.model.points.length;
    if (n == 0) return;

    // ── 1. Measure the frame's current load as mean of (r+g+b)/(3*255) ────
    long sumChannels = 0;
    for (LXPoint p : this.model.points) {
      final int c = this.colors[p.index];
      sumChannels += LXColor.red(c)   & 0xff;
      sumChannels += LXColor.green(c) & 0xff;
      sumChannels += LXColor.blue(c)  & 0xff;
    }
    final double loadNow = sumChannels / (3.0 * 255.0 * n);
    this.load.setValue(loadNow);

    // ── 2. Pick a target scale ────────────────────────────────────────────
    final double budgetV = this.budget.getValue();
    final double targetScale;
    if (loadNow <= budgetV || loadNow <= 1e-6) {
      targetScale = 1.0;
    } else {
      targetScale = budgetV / loadNow;
    }

    // ── 3. Smooth toward target with asymmetric attack / release ──────────
    // We integrate the one-pole smoother on a time-constant basis so the
    // response is frame-rate independent: alpha = 1 - exp(-dt / tau).
    final double tau = (targetScale < this.smoothedScale)
        ? Math.max(1.0, this.attackMs.getValue())    // clamping down fast
        : Math.max(1.0, this.releaseMs.getValue());  // easing back up slow
    final double alpha = 1.0 - Math.exp(-deltaMs / tau);
    this.smoothedScale += (targetScale - this.smoothedScale) * alpha;

    // Blend by enabledAmount so damping on/off ramps the limiter cleanly in
    // and out instead of popping.
    final double activeScale = 1.0 + (this.smoothedScale - 1.0) * enabledAmount;
    if (activeScale >= 0.9995) {
      // Within rounding of no-op: skip the inner loop entirely.
      return;
    }

    // ── 4. Apply the chosen reduction ─────────────────────────────────────
    if (this.desaturateMode.isOn()) {
      // Desaturate: subtract (min channel * (1-scale)) from each channel.
      // The min channel is the "white content"; removing it rotates toward
      // primary colors without changing hue, preserving chroma.
      final double pull = 1.0 - activeScale;
      for (LXPoint p : this.model.points) {
        final int c = this.colors[p.index];
        final int r = LXColor.red(c)   & 0xff;
        final int g = LXColor.green(c) & 0xff;
        final int b = LXColor.blue(c)  & 0xff;
        final int lo = Math.min(r, Math.min(g, b));
        final int sub = (int)(lo * pull);
        this.colors[p.index] =
            (c & 0xff000000)
          | (clamp8(r - sub) << 16)
          | (clamp8(g - sub) <<  8)
          |  clamp8(b - sub);
      }
    } else {
      // Dim: multiply RGB by scale.  Keep alpha channel intact.
      for (LXPoint p : this.model.points) {
        final int c = this.colors[p.index];
        final int r = LXColor.red(c)   & 0xff;
        final int g = LXColor.green(c) & 0xff;
        final int b = LXColor.blue(c)  & 0xff;
        this.colors[p.index] =
            (c & 0xff000000)
          | (clamp8((int)(r * activeScale)) << 16)
          | (clamp8((int)(g * activeScale)) <<  8)
          |  clamp8((int)(b * activeScale));
      }
    }
  }

  private static int clamp8(int v) {
    return v < 0 ? 0 : (v > 255 ? 255 : v);
  }
}
