package com.github.starcats.blinkydome.ui;

import com.github.starcats.blinkydome.color.ColorMappingSourceClan;
import com.github.starcats.blinkydome.color.ColorMappingSourceFamily;
import com.github.starcats.blinkydome.color.ImageColorSampler;
import com.github.starcats.blinkydome.color.RotatingHueColorMappingSourceFamily;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.p3lx.ui.UI;
import heronarts.p3lx.ui.UI2dComponent;
import heronarts.p3lx.ui.UI2dContainer;
import heronarts.p3lx.ui.UIContainer;
import heronarts.p3lx.ui.component.UIKnob;
import heronarts.p3lx.ui.component.UILabel;
import heronarts.p3lx.ui.component.UISwitch;
import heronarts.p3lx.ui.component.UIToggleSet;
import heronarts.p3lx.ui.studio.UICollapsibleSection;
import processing.core.PGraphics;
import processing.event.MouseEvent;

/**
 * UI to select a {@link com.github.starcats.blinkydome.color.ColorMappingSource} from a
 * {@link com.github.starcats.blinkydome.color.ColorMappingSourceClan}
 *
 * Currently chooses between gradients and patterns.
 */
public class UIColorMappingSource extends UICollapsibleSection {

  private static final float TOGGLES_W = 20;
  private static final float GROUP_SELECT_H = 15;
  private static final float ROW_SPACING = 5;
  private static final float GRADIENT_SUPPLIER_UIS_ROW_Y = UISwitch.WIDTH + ROW_SPACING + GROUP_SELECT_H;

  private final DiscreteParameter groupSelector;
  private final ColorMappingSourceFamilyUI[] colorMappingSourceFamilyUIs;
  private ColorMappingSourceFamilyUI currentCmsFamilyUI;

  public UIColorMappingSource(UI lxUI, ColorMappingSourceClan cmsClan, float x, float y, float w) {
    super(
        lxUI, x, y, w,
        30 + GRADIENT_SUPPLIER_UIS_ROW_Y
    );
    setTitle("COLOR MAPPING SOURCE");

    groupSelector = cmsClan.getFamilySelect();


    // Create UI elements:

    // First row: shuffle buttons
    new UISwitch(0, 0)
        .setParameter(cmsClan.getRandomSourceInFamilyTrigger())
        .addToContainer(this);

    new UISwitch(UISwitch.WIDTH, 0)
        .setParameter(cmsClan.getRandomSourceTrigger())
        .addToContainer(this);


    // Second row: group select buttons
    new UIToggleSet(
        0, UISwitch.WIDTH + ROW_SPACING, getContentWidth(), GROUP_SELECT_H
    )
    .setParameter(groupSelector)
    .addToContainer(this);


    // Third row: add all the sampler UI's (but hide inactive ones)

    ColorMappingSourceFamily[] imageSamplers = cmsClan.getFamilies();
    this.colorMappingSourceFamilyUIs = new ColorMappingSourceFamilyUI[imageSamplers.length];
    for (int i=0; i<imageSamplers.length; i++) {
      if (imageSamplers[i] instanceof ImageColorSampler) {
        colorMappingSourceFamilyUIs[i] = new ImageColorSamplerUI(
            (ImageColorSampler) imageSamplers[i], 0, GRADIENT_SUPPLIER_UIS_ROW_Y, getContentWidth()
        );
      } else if (imageSamplers[i] instanceof RotatingHueColorMappingSourceFamily) {
        colorMappingSourceFamilyUIs[i] = new RotatingHueCmsUI(
            (RotatingHueColorMappingSourceFamily) imageSamplers[i], 0, GRADIENT_SUPPLIER_UIS_ROW_Y, getContentWidth()
        );
      } else {
        colorMappingSourceFamilyUIs[i] = new GenericColorSamplerUI(
            imageSamplers[i], 0, GRADIENT_SUPPLIER_UIS_ROW_Y, getContentWidth()
        );
      }

      boolean isSelected = colorMappingSourceFamilyUIs[i].getSourceFamily() == groupSelector.getObject();

      ((UI2dComponent) colorMappingSourceFamilyUIs[i])
      .addToContainer(this)
      .setVisible(isSelected);

      if (isSelected) {
        currentCmsFamilyUI = colorMappingSourceFamilyUIs[i];
      }
    }

    // Wire up resizing according to whichever gradient supplier is visible
    resize();
    groupSelector.addListener(parameter -> {
      // Show only the matching UI
      for (ColorMappingSourceFamilyUI ui : colorMappingSourceFamilyUIs) {
        boolean isSelected = ui.getSourceFamily() == ((DiscreteParameter) parameter).getObject();
        ((UI2dComponent) ui).setVisible(isSelected);

        if (isSelected) {
          currentCmsFamilyUI = ui;
        }
      }

      // and resize to whichever one became visible
      resize();
    });

  }

  private interface ColorMappingSourceFamilyUI extends UIContainer {
    ColorMappingSourceFamily getSourceFamily();
  }

  private static class GenericColorSamplerUI extends UI2dContainer implements ColorMappingSourceFamilyUI {
    private final ColorMappingSourceFamily sourceGroup;

    private GenericColorSamplerUI(ColorMappingSourceFamily samplingSourceGroup, float x, float y, float w) {
      super(x, y, w, 12);

      sourceGroup = samplingSourceGroup;

      new UILabel(0, 0, getContentWidth(), 10).setLabel("No UI Available")
      .addToContainer(this);
    }

    public ColorMappingSourceFamily getSourceFamily() {
      return sourceGroup;
    }
  }

  private static class RotatingHueCmsUI extends UI2dContainer implements ColorMappingSourceFamilyUI {
    private final RotatingHueColorMappingSourceFamily sourceFamily;

    private RotatingHueCmsUI(RotatingHueColorMappingSourceFamily sourceFamily, float x, float y, float w) {
      super(x, y, w, UIKnob.KNOB_SIZE + 15);

      this.sourceFamily = sourceFamily;

      new UIKnob(0, 0)
          .setParameter(sourceFamily.huePeriodMs)
          .addToContainer(this);
    }

    public ColorMappingSourceFamily getSourceFamily() {
      return sourceFamily;
    }
  }

  /** Helper component that shows the patternMap and toggle switches for a given {@link ImageColorSampler} instance */
  private static class ImageColorSamplerUI extends UI2dContainer implements ColorMappingSourceFamilyUI {
    private final ImageColorSampler sourceGroup;

    private ImageColorSamplerUI(ImageColorSampler samplingSourceGroup, float x, float y, float w) {
      super(x, y, w, samplingSourceGroup.patternMap.height + 2);

      sourceGroup = samplingSourceGroup;

      new UIVerticalToggleSet(0, 0, TOGGLES_W, samplingSourceGroup.patternMap.height + 2)
      .setParameter(samplingSourceGroup.getSourceSelect())
      .addToContainer(this);

      new UIGradientImage(samplingSourceGroup, TOGGLES_W, 1, getContentWidth() - TOGGLES_W)
      .addToContainer(this);
    }

    public ColorMappingSourceFamily getSourceFamily() {
      return sourceGroup;
    }
  }

  private static class UIGradientImage extends UI2dContainer {

    private ImageColorSampler gradientSupplier;

    protected UIGradientImage(ImageColorSampler gradientSupplier, float x, float y, float width) {
      super(x, y, width, gradientSupplier.patternMap.height);
      this.gradientSupplier = gradientSupplier;
    }

    @Override
    public void onDraw(UI ui, PGraphics pg) {
      pg.image(gradientSupplier.patternMap, 0, 0, width, gradientSupplier.patternMap.height);
    }

    @Override
    protected void onMousePressed(MouseEvent mouseEvent, float mx, float my) {
      gradientSupplier.getSourceSelect().setValue(gradientSupplier.getSamplerFromMouseY(my));
    }
  }

  private void resize() {
    this.setContentHeight(
        GRADIENT_SUPPLIER_UIS_ROW_Y +
        ((UI2dComponent) currentCmsFamilyUI).getHeight()
    );
  }
}
