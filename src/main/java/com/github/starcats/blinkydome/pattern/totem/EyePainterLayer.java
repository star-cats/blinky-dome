package com.github.starcats.blinkydome.pattern.totem;

import com.github.starcats.blinkydome.model.totem.EyeModel;
import heronarts.lx.LX;
import heronarts.lx.LXLayer;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Draws eyes on the {@link com.github.starcats.blinkydome.model.totem.EyeModel} grid
 */
public class EyePainterLayer extends LXLayer {

  public final CompoundParameter posX;
  public final CompoundParameter posY;
  public final DiscreteParameter eyeType;

  public final BooleanParameter lockToOther;

  private Consumer<LXPoint> paintPxBlack = pt -> setColor(pt.index, LXColor.BLACK);
  private Consumer<LXPoint> paintPxWhite = pt -> setColor(pt.index, LXColor.WHITE);

  public interface EyePainter {
    void paintEyes(EyeModel.EyeGridView eyeView, EyeModel eye, double centerX, double centerY);
  }

  private final EyeModel eye;
  private final EyeModel.EyeGridView eyeView;
  private final List<EyePainter> eyePainters;

  private EyePainterLayer lockableEye;

  public EyePainterLayer(LX lx, EyeModel eye, String prefix) {
    super(lx);

    EyePainter[] eyePainters = new EyePainter[]{
        new SquarePainter(),
        new PlusPainter(),
        new SquintyPainter(),
        new JoyPainter()
    };
    this.eyePainters = Arrays.asList(eyePainters);

    this.eye = eye;
    this.eyeView = new EyeModel.EyeGridView(eye);

    this.posX = new CompoundParameter(prefix + "x", eye.getNumX() / 2f, 0, eye.getNumX() - 0.01);
    this.posY = new CompoundParameter(prefix + "y", eye.getNumY() / 2f, 0, eye.getNumY() - 0.01);

    this.eyeType = new DiscreteParameter(prefix + "eye", eyePainters)
        .setDescription("What kind of eyeball to paint");

    this.lockToOther = new BooleanParameter(prefix + "lck", false)
        .setDescription("Set true to lock to another eye");
  }

  public EyePainterLayer setLockableEye(EyePainterLayer lockableEye) {
    this.lockableEye = lockableEye;
    return this;
  }

  @Override
  public void run(double deltaMs) {
    eye.getPoints().forEach(paintPxBlack);

    if (lockToOther.getValueb() && lockableEye != null) {
      this.eyeType.setValue( lockableEye.eyeType.getValuei() );
      EyePainter painter = (EyePainter) this.eyeType.getObject();
      painter.paintEyes(eyeView, eye, lockableEye.posX.getValue(), lockableEye.posY.getValue());
    } else {
      EyePainter painter = (EyePainter) this.eyeType.getObject();
      painter.paintEyes(eyeView, eye, posX.getValue(), posY.getValue());
    }
  }

  /** Draws eyes as squares*/
  private class SquarePainter implements EyePainter {
    @Override
    public void paintEyes(EyeModel.EyeGridView eyeView, EyeModel eye, double centerX, double centerY) {
      eyeView.reset((int) centerX, (int) centerY);

      eyeView.getEyePx(0, 0).ifPresent(paintPxWhite);
      eyeView.getEyePx(0, 1).ifPresent(paintPxWhite);
      eyeView.getEyePx(1, 0).ifPresent(paintPxWhite);
      eyeView.getEyePx(1, 1).ifPresent(paintPxWhite);
    }

    @Override
    public String toString() {
      return "Square";
    }
  }

  /** Draws eyes as pluses */
  private class PlusPainter implements EyePainter {
    @Override
    public void paintEyes(EyeModel.EyeGridView eyeView, EyeModel eye, double centerX, double centerY) {
      eyeView.reset((int) centerX - 1, (int) centerY - 1);

      eyeView.getEyePx(0, 1).ifPresent(paintPxWhite);
      eyeView.getEyePx(1, 0).ifPresent(paintPxWhite);
      eyeView.getEyePx(1, 1).ifPresent(paintPxWhite);
      eyeView.getEyePx(1, 2).ifPresent(paintPxWhite);
      eyeView.getEyePx(2, 1).ifPresent(paintPxWhite);
    }

    @Override
    public String toString() {
      return "Plus";
    }
  }

  /** Draws eyes as 3px dashs */
  private class SquintyPainter implements EyePainter {
    @Override
    public void paintEyes(EyeModel.EyeGridView eyeView, EyeModel eye, double centerX, double centerY) {
      eyeView.reset((int) centerX - 1, (int) centerY);

      eyeView.getEyePx(0, 0).ifPresent(paintPxWhite);
      eyeView.getEyePx(1, 0).ifPresent(paintPxWhite);
      eyeView.getEyePx(2, 0).ifPresent(paintPxWhite);
    }

    @Override
    public String toString() {
      return "Squint";
    }
  }

  /** Draws eyes as ^'s, eg ^_^ */
  private class JoyPainter implements EyePainter {
    @Override
    public void paintEyes(EyeModel.EyeGridView eyeView, EyeModel eye, double centerX, double centerY) {
      eyeView.reset((int) centerX - 1, (int) centerY);

      eyeView.getEyePx(0, 0).ifPresent(paintPxWhite);
      eyeView.getEyePx(1, 1).ifPresent(paintPxWhite);
      eyeView.getEyePx(2, 0).ifPresent(paintPxWhite);
    }

    @Override
    public String toString() {
      return "Yayy!";
    }
  }
}
