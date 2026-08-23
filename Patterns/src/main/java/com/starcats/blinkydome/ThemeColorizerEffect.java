package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.GradientUtils;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXSwatch;
import heronarts.lx.effect.LXEffect;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;

/**
 * Maps input luminosity across the active theme gradient. Ported from
 * Scripts/ThemeColorizer.js.
 *
 * <p>Gain is applied first as a raw multiplier on incident luminosity. That
 * gained signal selects a position in the complete active swatch and is also
 * tested against Cutoff Threshold. Below the cutoff, the mapped theme RGB is
 * multiplied by Shade; above it, the mapped RGB is used at full strength.
 * Input alpha is retained.</p>
 *
 * <p>The active swatch is read on every frame because its dynamic colors may
 * be changing or transitioning. Gradient interpolation uses LX's native RGB
 * blend function, matching the script and native Colorize effect.</p>
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Theme Colorizer")
@LXComponent.Description("Map input luminosity through the active theme gradient")
public class ThemeColorizerEffect extends LXEffect {

  public final CompoundParameter threshold =
    new CompoundParameter("Cutoff Threshold", 0, 0, 1)
    .setDescription("Shade gained luminosity below this 0-1 level");

  public final CompoundParameter shade =
    new CompoundParameter("Shade", .3, 0, 1)
    .setDescription("RGB multiplier below the cutoff threshold");

  /** Java parameters can expose the requested physical range directly. */
  public final CompoundParameter gain =
    new CompoundParameter("Gain", 1, .5, 3)
    .setDescription("Raw multiplier on incident luminosity");

  private final GradientUtils.ColorStops colorStops =
    new GradientUtils.ColorStops();

  public ThemeColorizerEffect(LX lx) {
    super(lx);
    addParameter("threshold", this.threshold);
    addParameter("shade", this.shade);
    addParameter("gain", this.gain);
  }

  @Override
  protected void run(double deltaMs, double enabledAmount) {
    double blend = clamp(enabledAmount, 0, 1);
    if (blend <= 0) {
      return;
    }

    // Use every color in the active theme as one evenly spaced RGB gradient.
    this.colorStops.setPaletteGradient(
      this.lx.engine.palette,
      0,
      LXSwatch.MAX_COLORS
    );

    double gainAmount = this.gain.getValue();
    double cutoff = this.threshold.getValue();
    double shadeAmount = this.shade.getValue();

    for (LXPoint point : this.model.points) {
      int index = point.index;
      int input = this.colors[index];

      double r = (LXColor.red(input) & 0xff) / 255.;
      double g = (LXColor.green(input) & 0xff) / 255.;
      double b = (LXColor.blue(input) & 0xff) / 255.;
      double luminosity = (0.375 * r + 0.5 * g + 0.125 * b) * gainAmount;

      int mapped = this.colorStops.getColor(
        (float) clamp(luminosity, 0, 1),
        GradientUtils.BlendFunction.RGB
      );

      double rgbScale = (luminosity < cutoff) ? shadeAmount : 1;
      int output = LXColor.rgba(
        (int) Math.round((LXColor.red(mapped) & 0xff) * rgbScale),
        (int) Math.round((LXColor.green(mapped) & 0xff) * rgbScale),
        (int) Math.round((LXColor.blue(mapped) & 0xff) * rgbScale),
        LXColor.alpha(input) & 0xff
      );

      this.colors[index] = lerpRgbPreserveAlpha(input, output, blend);
    }
  }

  /** Interpolate RGB for effect enable amount without changing input alpha. */
  private static int lerpRgbPreserveAlpha(int input, int output, double amount) {
    int r = (int) Math.round(
      LXColor.red(input) + (LXColor.red(output) - LXColor.red(input)) * amount
    );
    int g = (int) Math.round(
      LXColor.green(input) + (LXColor.green(output) - LXColor.green(input)) * amount
    );
    int b = (int) Math.round(
      LXColor.blue(input) + (LXColor.blue(output) - LXColor.blue(input)) * amount
    );
    return LXColor.rgba(r, g, b, LXColor.alpha(input) & 0xff);
  }

  private static double clamp(double value, double low, double high) {
    return (value < low) ? low : (value > high) ? high : value;
  }
}
