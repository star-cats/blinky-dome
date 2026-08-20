package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * One solid hue across the whole model, ported from Scripts/SolidHue.js.
 *
 * A test pattern, and useful as one: full saturation and full brightness
 * everywhere means anything that is not the hue you dialed in is the fixture,
 * the mapping or the output stage rather than the pattern.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Solid Hue")
@LXComponent.Description("Every point as one solid hue")
public class SolidHuePattern extends LXPattern {

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 1)
    .setDescription("Solid color hue");

  public SolidHuePattern(LX lx) {
    super(lx);
    addParameter("hue", this.hue);
  }

  @Override
  protected void run(double deltaMs) {
    int color = LXColor.hsb(this.hue.getValue() * 360, 100, 100);
    for (LXPoint p : this.model.points) {
      this.colors[p.index] = color;
    }
  }
}
