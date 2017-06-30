package com.github.starcats.blinkydome.ui;

import com.github.starcats.blinkydome.util.GradientSupplier;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.p3lx.ui.UI;
import heronarts.p3lx.ui.UI2dContainer;
import heronarts.p3lx.ui.component.UISwitch;
import heronarts.p3lx.ui.component.UIToggleSet;
import heronarts.p3lx.ui.studio.UICollapsibleSection;
import processing.core.PGraphics;
import processing.event.MouseEvent;

/**
 * Gradient picker UI component.  Provides a UI to control a {@link GradientSupplier}
 */
public class UIGradientPicker extends UICollapsibleSection {

  private static final float TOGGLES_W = 20;
  private static final float ROW_SPACING = 5;
  private static final float GRADIENT_SUPPLIER_UIS_ROW_Y = UISwitch.WIDTH + ROW_SPACING;

  private final DiscreteParameter gradientSupplierSelector;

  public UIGradientPicker(UI ui, GradientSupplier gradientSupplier, float x, float y, float w) {
    this(ui, new GradientSupplier[]{ gradientSupplier }, x, y, w);
  }

  public UIGradientPicker(UI ui, GradientSupplier[] gradientSuppliers, float x, float y, float w) {
    super(
        ui, x, y, w,
        30 + GRADIENT_SUPPLIER_UIS_ROW_Y
    );
    setTitle("GRADIENT PICKER");

    // First, pre-build the UI elements for each gradient supplier.
    SupplierWithUIPair[] suppliersAndUis = new SupplierWithUIPair[gradientSuppliers.length];
    for (int i=0; i<gradientSuppliers.length; i++) {
      suppliersAndUis[i] = new SupplierWithUIPair(
          gradientSuppliers[i],
          new UIGradientSupplier(gradientSuppliers[i], 0, GRADIENT_SUPPLIER_UIS_ROW_Y, getContentWidth())
      );
    }

    // Use the pair of supplier and UI as the data objects for the selector param
    gradientSupplierSelector = new DiscreteParameter("family", suppliersAndUis)
    .setDescription("Select a family of gradients");


    // Create UI elements:

    // First row: shuffle and gradient supplier toggle
    BooleanParameter gradientRandomizerParam = new BooleanParameter("Shuffle")
    .setDescription("Select a random gradient")
    .setMode(BooleanParameter.Mode.MOMENTARY);
    gradientRandomizerParam.addListener(parameter -> {
      if (((BooleanParameter) parameter).getValueb()) {
        this.setRandomGradient();
      }
    });
    new UISwitch(0, 0)
    .setParameter(gradientRandomizerParam)
    .addToContainer(this);


    new UIToggleSet(
        UISwitch.WIDTH, 0, getContentWidth() - UISwitch.WIDTH, UISwitch.SWITCH_SIZE
    )
    .setParameter(gradientSupplierSelector)
    .addToContainer(this);


    // Second row: add all the supplier UI's (but hide inactive ones)
    for (SupplierWithUIPair supplierWithUi : suppliersAndUis) {
      supplierWithUi.getUi()
      .addToContainer(this)
      .setVisible(gradientSupplierSelector.getObject() == supplierWithUi);
    }

    // Wire up resizing according to whichever gradient supplier is visible
    resize();
    gradientSupplierSelector.addListener(parameter -> {
      resize();

      // Show only the matching UI
      for (SupplierWithUIPair supplierWithUi : suppliersAndUis) {
        supplierWithUi.getUi().setVisible(supplierWithUi == ((DiscreteParameter) parameter).getObject());
      }
    });

  }

  private static class SupplierWithUIPair {
    private final GradientSupplier gradientSupplier;
    private final UIGradientSupplier ui;

    private SupplierWithUIPair(GradientSupplier supplier, UIGradientSupplier ui) {
      this.gradientSupplier = supplier;
      this.ui = ui;
    }

    public GradientSupplier getGradientSupplier() {
      return gradientSupplier;
    }

    public UIGradientSupplier getUi() {
      return ui;
    }

    public String toString() {
      return gradientSupplier.toString();
    }
  }

  /** Helper component that shows the gradients and toggle switches for a given {@link GradientSupplier} instance */
  private static class UIGradientSupplier extends UI2dContainer {
    private UIGradientSupplier(GradientSupplier gradientSupplier, float x, float y, float w) {
      super(x, y, w, gradientSupplier.gradients.height + 2);

      new UIVerticalToggleSet(0, 0, TOGGLES_W, gradientSupplier.gradients.height + 2)
      .setParameter(gradientSupplier.gradientSelect)
      .addToContainer(this);

      new UIGradientImage(gradientSupplier, TOGGLES_W, 1, getContentWidth() - TOGGLES_W)
      .addToContainer(this);
    }
  }

  private static class UIGradientImage extends UI2dContainer {

    private GradientSupplier gradientSupplier;

    protected UIGradientImage(GradientSupplier gradientSupplier, float x, float y, float width) {
      super(x, y, width, gradientSupplier.gradients.height);
      this.gradientSupplier = gradientSupplier;
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

  private void resize() {
    this.setHeight(
        30 + GRADIENT_SUPPLIER_UIS_ROW_Y +
        ((SupplierWithUIPair) gradientSupplierSelector.getObject()).getUi().getHeight()
    );
  }

  public void setRandomGradient() {
    ((SupplierWithUIPair) gradientSupplierSelector.getObject()).getGradientSupplier().setRandomGradient();
  }
}
