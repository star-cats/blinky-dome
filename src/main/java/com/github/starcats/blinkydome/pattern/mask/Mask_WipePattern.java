package com.github.starcats.blinkydome.pattern.mask;

import com.github.starcats.blinkydome.pattern.effects.WhiteWipe;
import com.github.starcats.blinkydome.util.SCTriggerable;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Perform a random wipe in x, y, or z direction
 */
public class Mask_WipePattern extends LXPattern implements SCTriggerable {

  public final BooleanParameter wipeTrigger = new BooleanParameter("Trigger")
      .setDescription("Hit to start a wipe")
      .setMode(BooleanParameter.Mode.MOMENTARY);

  private final WhiteWipe[] allWipes;

  public final BoundedParameter durationMs = new BoundedParameter("durationMs", 400, 50, 1000)
      .setDescription("Duration for wipes to travel across model (ms)");

  public final BoundedParameter widthPx = (BoundedParameter) new BoundedParameter("width", 5, 0, 100)
      .setDescription("Width of the wipe, in model units")
      .setExponent(2);


  public Mask_WipePattern(LX lx) {
    this(lx, Collections.emptyList());
  }

  public Mask_WipePattern(LX lx, LXFixture[] fixtures) {
    this(
        lx,
        Arrays.stream(fixtures).flatMap(f -> f.getPoints().stream() ).collect(Collectors.toList())
    );
  }

  public Mask_WipePattern(LX lx, List<LXPoint> points) {
    super(lx);

    if (points == null || points.isEmpty()) {
      points = lx.model.getPoints();
    }

    allWipes = new WhiteWipe[] {
        new WhiteWipe(lx, this, points, pt -> pt.y, durationMs, widthPx),
        new WhiteWipe(lx, this, points, pt -> -pt.y, durationMs, widthPx),

        new WhiteWipe(lx, this, points, pt -> pt.x, durationMs, widthPx),
        new WhiteWipe(lx, this, points, pt -> -pt.x, durationMs, widthPx),

        new WhiteWipe(lx, this, points, pt -> pt.z, durationMs, widthPx),
        new WhiteWipe(lx, this, points, pt -> -pt.z, durationMs, widthPx)
    };

    addParameter(durationMs);
    addParameter(widthPx);

    addParameter(wipeTrigger);
    wipeTrigger.addListener(param -> {
      if (param.getValue() == 1) {
        this.startRandomWipe();
      }
    });
  }

  @Override
  public BooleanParameter getTrigger() {
    return wipeTrigger;
  }

  public void startRandomWipe() {
    allWipes[ (int) (Math.random() * allWipes.length) ].start();
  }

  public void run(double deltaMs) {
    for (LXPoint p : getModel().points) {
      setColor(p.index, LXColor.BLACK);
    }
    for (WhiteWipe w : allWipes) {
      w.run(deltaMs);
    }
  }
}
