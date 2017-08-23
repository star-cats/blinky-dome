package com.github.starcats.blinkydome.pattern.mask;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXPoint;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * All-white
 */
public class Mask_AllWhite extends LXPattern {
  private final List<LXPoint> points;

  public Mask_AllWhite(LX lx, LXFixture[] fixtures) {
    this(lx, Arrays.stream(fixtures).flatMap(f -> f.getPoints().stream()).collect(Collectors.toList()));
  }

  public Mask_AllWhite(LX lx) {
    this(lx, Collections.emptyList());
  }

  public Mask_AllWhite(LX lx, List<LXPoint> points) {
    super(lx);

    if (points == null || points.isEmpty()) {
      points = lx.model.getPoints();
    }
    this.points = points;
  }

  @Override
  protected void run(double deltaMs) {
    for (LXPoint pt : points) {
      setColor(pt.index, LXColor.WHITE);
    }
  }
}
