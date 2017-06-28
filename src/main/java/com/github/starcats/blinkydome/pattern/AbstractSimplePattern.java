package com.github.starcats.blinkydome.pattern;
import com.github.starcats.blinkydome.model.StarcatsLxModel;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.model.LXPoint;

import java.util.Arrays;
import java.util.List;

/**
 * Demo/boilerplate pattern
 */
public abstract class AbstractSimplePattern extends LXPattern implements SCPattern {
  protected StarcatsLxModel model;

  final List<LXPoint> points;

  protected AbstractSimplePattern(LX lx) {
    super(lx);
    this.model = (StarcatsLxModel) super.model;
    this.points = Arrays.asList(model.points);

    model.visit(this);
  }

  protected void setLEDColor(LXPoint led, int c) {
    setColor(led.index, c);
  }
}
