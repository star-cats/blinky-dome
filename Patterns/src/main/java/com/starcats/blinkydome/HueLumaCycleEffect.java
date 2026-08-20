package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.effect.LXEffect;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;

/**
 * Cycles the hue and the luminosity of whatever is underneath, independently.
 * Ported from Scripts/HueLumaCycle.js.
 *
 * Both controls are in cycles per second, integrated into a phase rather than
 * read off a clock, so a speed change bends the cycle from where it is instead
 * of jumping it to wherever the new rate says it should have been.
 *
 * Hue wraps into [0, 1). Luminosity is advanced by a phase of its own and
 * wrapped into [0, 0.65) — short of white on purpose, because a luminosity that
 * wrapped through 1 would blow every color out to flat white once a cycle. As
 * the wrapped luminosity climbs, saturation is pulled from whatever the input
 * had toward 1, so the brightening reads as color deepening rather than as
 * everything washing out. Alpha is carried through from the input untouched.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Hue Luma Cycle")
@LXComponent.Description("Cycle hue and luminosity of the input independently")
public class HueLumaCycleEffect extends LXEffect {

  /** Ceiling the luminosity cycle wraps at. */
  private static final double LUMA_MAX = .65;

  public final CompoundParameter hueSpeed =
    new CompoundParameter("Hue Speed", 0, 0, 1)
    .setDescription("Hue cycles per second");

  public final CompoundParameter lumaSpeed =
    new CompoundParameter("Luma Speed", 0, 0, 1)
    .setDescription("Luminosity cycles per second");

  private double huePhase = 0;
  private double lumaPhase = 0;

  public HueLumaCycleEffect(LX lx) {
    super(lx);
    addParameter("hueSpeed", this.hueSpeed);
    addParameter("lumaSpeed", this.lumaSpeed);
  }

  @Override
  protected void run(double deltaMs, double enabledAmount) {
    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs * .001, 0, .25) : 0;
    this.huePhase = wrap(this.huePhase + this.hueSpeed.getValue() * dt, 1);
    this.lumaPhase =
      wrap(this.lumaPhase + this.lumaSpeed.getValue() * dt * LUMA_MAX, LUMA_MAX);

    double blend = clamp(enabledAmount, 0, 1);

    for (LXPoint p : this.model.points) {
      int input = this.colors[p.index];

      double r = (LXColor.red(input) & 0xff) / 255.;
      double g = (LXColor.green(input) & 0xff) / 255.;
      double b = (LXColor.blue(input) & 0xff) / 255.;

      double maxChannel = Math.max(r, Math.max(g, b));
      double minChannel = Math.min(r, Math.min(g, b));
      double chroma = maxChannel - minChannel;
      double lightness = (maxChannel + minChannel) * .5;
      double hue = 0;
      double saturation = 0;

      if (chroma > 0) {
        saturation = chroma / (1 - Math.abs(2 * lightness - 1));
        if (maxChannel == r) {
          hue = ((g - b) / chroma) / 6;
        } else if (maxChannel == g) {
          hue = ((b - r) / chroma + 2) / 6;
        } else {
          hue = ((r - g) / chroma + 4) / 6;
        }
        hue = wrap(hue, 1);
      }

      double outputLuma = wrap(lightness + this.lumaPhase, LUMA_MAX);
      double lumaPosition = outputLuma / LUMA_MAX;
      double outputSaturation = lerp(saturation, 1, lumaPosition);

      int output = hslToColor(
        wrap(hue + this.huePhase, 1),
        outputSaturation,
        outputLuma,
        LXColor.alpha(input) & 0xff
      );

      this.colors[p.index] = LXColor.lerp(input, output, blend);
    }
  }

  /** Normalized HSL back to a packed color, keeping the input's alpha. */
  private static int hslToColor(double hue, double saturation, double lightness, int alpha) {
    double chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
    double sector = hue * 6;
    double secondary = chroma * (1 - Math.abs((sector % 2) - 1));
    double r = 0;
    double g = 0;
    double b = 0;

    if (sector < 1) {
      r = chroma;
      g = secondary;
    } else if (sector < 2) {
      r = secondary;
      g = chroma;
    } else if (sector < 3) {
      g = chroma;
      b = secondary;
    } else if (sector < 4) {
      g = secondary;
      b = chroma;
    } else if (sector < 5) {
      r = secondary;
      b = chroma;
    } else {
      r = chroma;
      b = secondary;
    }

    double match = lightness - chroma * .5;
    return LXColor.rgba(
      (int) Math.round((r + match) * 255),
      (int) Math.round((g + match) * 255),
      (int) Math.round((b + match) * 255),
      alpha
    );
  }

  /** Positive modulo — JS % keeps the sign of the dividend and this must not. */
  private static double wrap(double value, double max) {
    return ((value % max) + max) % max;
  }

  private static double clamp(double v, double low, double high) {
    return (v < low) ? low : (v > high) ? high : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
