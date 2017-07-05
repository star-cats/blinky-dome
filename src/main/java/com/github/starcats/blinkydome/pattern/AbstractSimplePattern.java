package com.github.starcats.blinkydome.pattern;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.model.LXPoint;

import java.util.Arrays;
import java.util.List;

/**
 * Demo/boilerplate pattern
 */
public abstract class AbstractSimplePattern extends LXPattern {

  final List<LXPoint> points;

  protected AbstractSimplePattern(LX lx) {
    super(lx);
    this.points = Arrays.asList(model.points);
  }

  protected void setLEDColor(LXPoint led, int c) {
    setColor(led.index, c);
  }
}
