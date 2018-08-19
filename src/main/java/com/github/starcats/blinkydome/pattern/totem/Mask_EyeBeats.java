package com.github.starcats.blinkydome.pattern.totem;

import com.github.starcats.blinkydome.model.totem.EyeModel;
import com.github.starcats.blinkydome.model.totem.TotemModel;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXNormalizedParameter;

import java.util.function.Consumer;


/**
 * Makes totem eyes beat-reactive by having a controllable radius
 */
public class Mask_EyeBeats extends LXPattern {

  private EyePainter leftEye;
  private EyePainter rightEye;

  LXNormalizedParameter eyeRadius = new CompoundParameter("rad", 0);

  public Mask_EyeBeats(LX lx, TotemModel totemModel) {
    super(lx);
    this.model = totemModel;

    addParameter(eyeRadius);

    this.leftEye = new EyePainter(totemModel.leftEye, 0, 0);
    this.rightEye = new EyePainter(totemModel.rightEye, -1, 0);
  }

  @Override
  protected void run(double deltaMs) {
    leftEye.paintEye(eyeRadius);
    rightEye.paintEye(eyeRadius);
  }

  private class EyePainter {

    private final EyeModel.EyeGridView eyeView;
    private final EyeModel eye;
    private int rangeX;
    private int rangeY;

    public EyePainter(EyeModel eye, int offsetX, int offsetY) {
      this.eye = eye;
      this.eyeView = new EyeModel.EyeGridView(eye);

      int centerX = (int) Math.floor(eye.getNumX() / 2f) + offsetX;
      int centerY = (int) Math.floor(eye.getNumY() / 2f) + offsetY;

      rangeX = Math.max(centerX, eye.getNumX() - centerX);
      rangeY = Math.max(centerY, eye.getNumY() - centerY);

      this.eyeView.reset(centerX, centerY);
    }

    public void paintEye(LXNormalizedParameter param) {

      this.eye.getPoints().forEach(pt -> setColor(pt.index, LXColor.BLACK));

      double targetVal = param.getNormalized();

      int maxRangeX = (int) Math.ceil(rangeX * targetVal);
      int maxRangeY = (int) Math.ceil(rangeY * targetVal);


      for (int x = 0; x < maxRangeX; x++) {
        for (int y = 0; y < maxRangeY; y++) {
          double dist = Math.sqrt(x*x + y*y);
          double normalized = dist / (double)(Math.max(maxRangeX, maxRangeY));

          int color;
          if (normalized > targetVal) {
            color = LXColor.BLACK;
          } else if (targetVal - normalized > 1) {
            color = LXColor.WHITE;
          } else {
            double diff = targetVal - normalized;
            color = LXColor.lerp(LXColor.WHITE, LXColor.BLACK, diff);
          }

          Consumer<LXPoint> paintColor = pt -> setColor(pt.index, color);

          eyeView.getEyePx(x, y).ifPresent(paintColor);
          eyeView.getEyePx(x, -y).ifPresent(paintColor);
          eyeView.getEyePx(-x, y).ifPresent(paintColor);
          eyeView.getEyePx(-x, -y).ifPresent(paintColor);
        }
      }


    }
  }

}
