package com.github.starcats.blinkydome.pattern.mask;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXParameter;
import processing.core.PApplet;

import static processing.core.PApplet.constrain;

/**
 * Adapted from Tenere's PatternClouds, by Mark C. Slee
 */
public class TMask_Clouds extends LXPattern {
  // Needed for noise() dependency
  private final PApplet p;

  public final CompoundParameter thickness =
          new CompoundParameter("Thickness", 50, 100, 0)
                  .setDescription("Thickness of the cloud formation");

  public final CompoundParameter xSpeed = (CompoundParameter)
          new CompoundParameter("XSpd", 0, -1, 1)
                  .setPolarity(LXParameter.Polarity.BIPOLAR)
                  .setDescription("Motion along the X axis");

  public final CompoundParameter ySpeed = (CompoundParameter)
          new CompoundParameter("YSpd", 0, -1, 1)
                  .setPolarity(LXParameter.Polarity.BIPOLAR)
                  .setDescription("Motion along the Y axis");

  public final CompoundParameter zSpeed = (CompoundParameter)
          new CompoundParameter("ZSpd", 0, -1, 1)
                  .setPolarity(LXParameter.Polarity.BIPOLAR)
                  .setDescription("Motion along the Z axis");

  public final CompoundParameter scale = (CompoundParameter)
          new CompoundParameter("Scale", 3, .25, 10)
                  .setDescription("Scale of the clouds")
                  .setExponent(2);

  public final CompoundParameter xScale =
          new CompoundParameter("XScale", 0, 0, 10)
                  .setDescription("Scale along the X axis");

  public final CompoundParameter yScale =
          new CompoundParameter("YScale", 0, 0, 10)
                  .setDescription("Scale along the Y axis");

  public final CompoundParameter zScale =
          new CompoundParameter("ZScale", 0, 0, 10)
                  .setDescription("Scale along the Z axis");

  private float xBasis = 0, yBasis = 0, zBasis = 0;

  public TMask_Clouds(LX lx, PApplet p) {
    super(lx);

    this.p = p;

    addParameter("thickness", this.thickness);
    addParameter("xSpeed", this.xSpeed);
    addParameter("ySpeed", this.ySpeed);
    addParameter("zSpeed", this.zSpeed);
    addParameter("scale", this.scale);
    addParameter("xScale", this.xScale);
    addParameter("yScale", this.yScale);
    addParameter("zScale", this.zScale);
  }

  private static final double MOTION = .0005;

  public void run(double deltaMs) {
    this.xBasis -= deltaMs * MOTION * this.xSpeed.getValuef();
    this.yBasis -= deltaMs * MOTION * this.ySpeed.getValuef();
    this.zBasis -= deltaMs * MOTION * this.zSpeed.getValuef();
    float thickness = this.thickness.getValuef();
    float scale = this.scale.getValuef();
    float xScale = this.xScale.getValuef();
    float yScale = this.yScale.getValuef();
    float zScale = this.zScale.getValuef();
    for (LXPoint point : model.getPoints()) {
      float nv = this.p.noise(
              (scale + point.xn * xScale) * point.xn + this.xBasis,
              (scale + point.yn * yScale) * point.yn + this.yBasis,
              (scale + point.zn * zScale) * point.zn + this.zBasis
      );
      setColor(point.index, LXColor.gray(constrain(-thickness + (150 + thickness) * nv, 0, 100)));
    }
  }
}
