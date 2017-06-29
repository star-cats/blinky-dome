package com.github.starcats.blinkydome.ui;

import com.github.starcats.blinkydome.util.GradientSupplier;
import heronarts.p3lx.ui.UI;
import heronarts.p3lx.ui.UI2dComponent;
import heronarts.p3lx.ui.studio.UICollapsibleSection;
import processing.core.PGraphics;
import processing.event.MouseEvent;

/**
 * Gradient picker UI component.  Provides a UI to control a {@link GradientSupplier}
 */
public class UIGradientPicker extends UICollapsibleSection {

  private GradientSupplier gradientSupplier;

  public UIGradientPicker(UI ui, GradientSupplier gradientSupplier, float x, float y, float w) {
    super(ui, x, y, w, gradientSupplier.gradients.height + 30);
    setTitle("GRADIENT PICKER");

    this.gradientSupplier = gradientSupplier;

    new UIVerticalToggleSet(2, 1, 18, gradientSupplier.gradients.height + 1)
    .setParameter(gradientSupplier.gradientSelect)
    .addToContainer(this);

    new UIGradientImage(20, 2, getContentWidth() - 20)
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
