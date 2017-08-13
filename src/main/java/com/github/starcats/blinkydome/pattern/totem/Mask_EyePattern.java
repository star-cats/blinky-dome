package com.github.starcats.blinkydome.pattern.totem;

import com.github.starcats.blinkydome.model.totem.TotemModel;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;

/**
 * Created by dlopuch on 8/12/17.
 */
public class Mask_EyePattern extends LXPattern {

  private final TotemModel model;

  public Mask_EyePattern(LX lx, TotemModel totemModel) {
    super(lx);
    this.model = totemModel;

    EyePatternLayer leftEye = new EyePatternLayer(lx, totemModel.leftEye, "l ");
    EyePatternLayer rightEye = new EyePatternLayer(lx, totemModel.rightEye, "r ").setLockableEye(leftEye);

    addLayer(leftEye);
    addLayer(rightEye);

    addParameter(leftEye.posX);
    addParameter(leftEye.posY);
    addParameter(leftEye.eyeType);

    addParameter(rightEye.lockToOther);
    addParameter(rightEye.posX);
    addParameter(rightEye.posY);
    addParameter(rightEye.eyeType);
  }

  @Override
  protected void run(double deltaMs) {
    model.rightWhiskers.forEach(f -> setColor(f, LXColor.WHITE));
    model.leftWhiskers.forEach(f -> setColor(f, LXColor.WHITE));
  }
}
