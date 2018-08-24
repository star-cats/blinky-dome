package com.github.starcats.blinkydome.pattern.mask;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import processing.core.PApplet;

import static heronarts.lx.LXUtils.constrain;

/**
 * Adapted from Tenere's PatternBorealis by Mark Slee
 */
public class TMask_Borealis extends LXPattern {
  private PApplet p;

  public final CompoundParameter speed =
          new CompoundParameter("Speed", .5, .01, 1)
                  .setDescription("Speed of motion");

  public final CompoundParameter scale =
          new CompoundParameter("Scale", .5, .1, 1)
                  .setDescription("Scale of lights");

  public final CompoundParameter spread =
          new CompoundParameter("Spread", 6, .1, 10)
                  .setDescription("Spreading of the motion");

  public final CompoundParameter base =
          new CompoundParameter("Base", .5, .2, 1)
                  .setDescription("Base brightness level");

  public final CompoundParameter contrast =
          new CompoundParameter("Contrast", 1, .5, 2)
                  .setDescription("Contrast of the lights");

  public TMask_Borealis(LX lx, PApplet p) {
    super(lx);

    this.p = p;

    addParameter("speed", this.speed);
    addParameter("scale", this.scale);
    addParameter("spread", this.spread);
    addParameter("base", this.base);
    addParameter("contrast", this.contrast);
  }

  private float yBasis = 0;

  public void run(double deltaMs) {
    this.yBasis -= deltaMs * .0005 * this.speed.getValuef();
    float scale = this.scale.getValuef();
    float spread = this.spread.getValuef();
    float base = .01f * this.base.getValuef();
    float contrast = this.contrast.getValuef();
    for (LXPoint pt : model.getPoints()) {
      float nv = p.noise(
              scale * (base * pt.rxz - spread * pt.yn),
              pt.yn + this.yBasis
      );
      setColor(pt.index, LXColor.gray((float) constrain(contrast * (-50f + 180f * nv), 0f, 100f)));
    }
  }
}