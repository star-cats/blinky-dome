package com.github.starcats.blinkydome.ui;

import com.github.starcats.blinkydome.util.GradientSupplier;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.p3lx.ui.UI;
import heronarts.p3lx.ui.UI2dComponent;
import heronarts.p3lx.ui.component.UISwitch;
import heronarts.p3lx.ui.studio.UICollapsibleSection;
import processing.core.PGraphics;
import processing.event.MouseEvent;

/**
 * Gradient picker UI component.  Provides a UI to control a {@link GradientSupplier}
 */
public class UIGradientPicker extends UICollapsibleSection {

  private static final float TOGGLES_W = 20;
  private static final float ROW_SPACING = 5;

  private GradientSupplier gradientSupplier;

  public UIGradientPicker(UI ui, GradientSupplier gradientSupplier, float x, float y, float w) {
    super(ui, x, y, w, gradientSupplier.gradients.height + 30 + (UISwitch.WIDTH + ROW_SPACING));
    setTitle("GRADIENT PICKER");

    this.gradientSupplier = gradientSupplier;


    // Create UI elements:
    float rowY = 0;

    BooleanParameter gradientRandomizerToggle = new BooleanParameter("Shuffle")
    .setDescription("Select a random gradient")
    .setMode(BooleanParameter.Mode.MOMENTARY);
    gradientRandomizerToggle.addListener(parameter -> {
      if (((BooleanParameter) parameter).getValueb()) {
        gradientSupplier.setRandomGradient();
      }
    });
    new UISwitch(0, rowY)
    .setParameter(gradientRandomizerToggle)
    .addToContainer(this);

    // next row:
    rowY += UISwitch.WIDTH + ROW_SPACING; // .WIDTH is also height

    new UIVerticalToggleSet(0, rowY + 1, TOGGLES_W, gradientSupplier.gradients.height + 1)
    .setParameter(gradientSupplier.gradientSelect)
    .addToContainer(this);

    new UIGradientImage(TOGGLES_W, rowY + 2, getContentWidth() - TOGGLES_W)
    .addToContainer(this);
  }

  private class UIGradientImage extends UI2dComponent {

    protected UIGradientImage(float x, float y, float width) {
      super(x, y, width, gradientSupplier.gradients.height);
    }

    @Override
    public void onDraw(UI ui, PGraphics pg) {
      pg.image(gradientSupplier.gradients, 0, 0, width, gradientSupplier.gradients.height);
    }

    @Override
    protected void onMousePressed(MouseEvent mouseEvent, float mx, float my) {
      gradientSupplier.gradientSelect.setValue(gradientSupplier.getSamplerFromMouseY(my));
    }
  }

}
