package com.github.starcats.blinkydome.pattern.mask;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.transform.LXVector;

/**
 * A disc of LEDs that can tilt and bounce across a model
 */
public class Mask_RollingBouncingDisc extends LXPattern {

  private LXVector origin;
  private LXVector direction;

  public final CompoundParameter discThicknessRad = (CompoundParameter) new CompoundParameter(
      "thcknss", Math.PI / 4, 0, Math.PI/2)
      .setDescription("How many degrees (radians) up/down from the hoop should be lit up")
      .setExponent(2);

  public final CompoundParameter position = new CompoundParameter("pos", 0., 0., 1.)
      .setDescription("Current position along the direction vector");

  public Mask_RollingBouncingDisc(LX lx, LXVector origin, LXVector direction) {
    super(lx);
    this.origin = origin;
    this.direction = direction;

    addParameter(position);
    addParameter(discThicknessRad);
  }

  @Override
  protected void run(double deltaMs) {
    LXVector curPosOffset = new LXVector(direction).mult(position.getValuef()).add(origin);

    // reverse the position to get LED-normalization vector
    curPosOffset.mult(-1);

    LXVector led = new LXVector(0, 0, 0);
    for (LXPoint pt : model.getPoints()) {
      set(led, pt);
      led.add(curPosOffset);
      double theta = LXVector.angleBetween(direction, led);

      if (theta < discThicknessRad.getValue() && theta > -discThicknessRad.getValue()) {
        setColor(pt.index, LXColor.WHITE);
      } else {
        setColor(pt.index, LXColor.BLACK);
      }
    }
  }

  private static void set(LXVector vector, LXPoint from) {
    vector.x = from.x;
    vector.y = from.y;
    vector.z = from.z;
  }
}
