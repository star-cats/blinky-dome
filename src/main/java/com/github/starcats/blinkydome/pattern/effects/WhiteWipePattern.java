package com.github.starcats.blinkydome.pattern.effects;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;

/**
 * Created by dlopuch on 6/27/17.
 */
public class WhiteWipePattern extends LXPattern {

  private final WhiteWipe[] allWipes;

  private final BoundedParameter durationMs = new BoundedParameter("durationMs", 400, 50, 1000)
      .setDescription("Duration for wipes to travel across model (ms)");

  private final BoundedParameter widthPx = new BoundedParameter("width", 5, 0, 20)
      .setDescription("Width of the wipe, in model units");

  private final BooleanParameter triggerWipe = new BooleanParameter("Trigger")
      .setDescription("Hit to start a wipe")
      .setMode(BooleanParameter.Mode.MOMENTARY);

  public WhiteWipePattern(LX lx) {
    super(lx);

    allWipes = new WhiteWipe[] {
        new WhiteWipe(lx, this, m -> m.yMin, m -> m.yMax, pt -> pt.y, durationMs, widthPx),
        new WhiteWipe(lx, this, m -> m.yMax, m -> m.yMin, pt -> pt.y, durationMs, widthPx),

        new WhiteWipe(lx, this, m -> m.xMin, m -> m.xMax, pt -> pt.x, durationMs, widthPx),
        new WhiteWipe(lx, this, m -> m.xMax, m -> m.xMin, pt -> pt.x, durationMs, widthPx),

        new WhiteWipe(lx, this, m -> m.zMin, m -> m.zMax, pt -> pt.z, durationMs, widthPx),
        new WhiteWipe(lx, this, m -> m.zMax, m -> m.zMin, pt -> pt.z, durationMs, widthPx)
    };

    addParameter(durationMs);
    addParameter(widthPx);

    addParameter(triggerWipe);
    triggerWipe.addListener(param -> {
      if (param.getValue() == 1) {
        this.startRandomWipe();
      }
    });
  }

  public void startRandomWipe() {
    allWipes[ (int) (Math.random() * allWipes.length) ].start();
  }

  public void run(double deltaMs) {
    for (LXPoint p : getModel().points) {
      getColors()[p.index] = LX.hsb(0, 0, 0);
    }
    for (WhiteWipe w : allWipes) {
      w.run(deltaMs);
    }
  }
}
