package com.github.starcats.blinkydome.pattern.mask;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;

/**
 * Simple masks that lights everything below a certain x, y, z value
 */
public class Mask_XyzFilter extends LXPattern {
  public final CompoundParameter xFilter;
  public final CompoundParameter yFilter;
  public final CompoundParameter zFilter;

  public Mask_XyzFilter(LX lx) {
    super(lx);

    xFilter = new CompoundParameter("x", model.xMin, model.xMin, model.xMax + 1);
    yFilter = new CompoundParameter("y", model.yMin, model.yMin, model.yMax + 1);
    zFilter = new CompoundParameter("z", model.zMin, model.zMin, model.zMax + 1);

    addParameter(xFilter);
    addParameter(yFilter);
    addParameter(zFilter);
  }

  @Override
  protected void run(double deltaMs) {
    for (LXPoint pt : model.getPoints()) {
      if (pt.x < xFilter.getValue() &&
          pt.y < yFilter.getValue() &&
          pt.z < zFilter.getValue())
      {
        setColor(pt.index, LXColor.WHITE);
      } else {
        setColor(pt.index, LXColor.BLACK);
      }
    }
  }
}
