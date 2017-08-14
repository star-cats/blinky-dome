package com.github.starcats.blinkydome.pattern.totem;

import com.github.starcats.blinkydome.model.totem.TotemModel;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.parameter.LXCompoundModulation;

/**
 * Mask pattern that generates two eye masks
 */
public class Mask_EyePattern extends LXPattern {

  private final TotemModel model;
  private final EyePatternLayer leftEye;
  private final EyePatternLayer rightEye;

  public Mask_EyePattern(LX lx, TotemModel totemModel) {
    super(lx);
    this.model = totemModel;

    leftEye = new EyePatternLayer(lx, totemModel.leftEye, "l ");
    rightEye = new EyePatternLayer(lx, totemModel.rightEye, "r ").setLockableEye(leftEye);

    addLayer(leftEye);
    addLayer(rightEye);

    addParameter(leftEye.posX);
    addParameter(leftEye.posY);
    addParameter(leftEye.eyeType);

    addParameter(rightEye.lockToOther);
    rightEye.lockToOther.setValue(true);
    addParameter(rightEye.posX);
    addParameter(rightEye.posY);
    addParameter(rightEye.eyeType);
  }

  public Mask_EyePattern initModulators() {
    VariableLFO eyeXMod = new VariableLFO("eye x");
    eyeXMod.start();

    LXCompoundModulation eyeXModln = new LXCompoundModulation(eyeXMod, leftEye.posX);
    eyeXModln.range.setValue(1);
    leftEye.posX.setValue(0);

    this.modulation.addModulator(eyeXMod);
    this.modulation.addModulation(eyeXModln);

    return this;
  }

  @Override
  protected void run(double deltaMs) {
    model.rightWhiskers.forEach(f -> setColor(f, LXColor.WHITE));
    model.leftWhiskers.forEach(f -> setColor(f, LXColor.WHITE));
  }
}
