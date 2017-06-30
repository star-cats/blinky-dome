package com.github.starcats.blinkydome.ui;

import heronarts.lx.LXUtils;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.LXParameterListener;
import heronarts.p3lx.ui.UI;
import heronarts.p3lx.ui.UI2dComponent;
import heronarts.p3lx.ui.UIControlTarget;
import heronarts.p3lx.ui.UIFocus;
import heronarts.p3lx.ui.component.UIParameterControl;
import processing.core.PConstants;
import processing.core.PGraphics;
import processing.event.KeyEvent;
import processing.event.MouseEvent;

/**
 * Copy-paste of P3LX's ToggleSet, but adapted to make buttons vertical not horizontal (mostly width vars changed to h)
 */
public class UIVerticalToggleSet extends UI2dComponent implements UIFocus, UIControlTarget, LXParameterListener {

  private String[] options = null;

  private int[] boundaries = null;

  private int value = -1;

  private DiscreteParameter parameter = null;

  public UIVerticalToggleSet() {
    this(0, 0, 0, 0);
  }

  public UIVerticalToggleSet(float x, float y, float w, float h) {
    super(x, y, w, h);
  }

  @Override
  public String getDescription() {
    return UIParameterControl.getDescription(this.parameter);
  }

  @Override
  protected void onResize() {
    computeBoundaries();
  }

  public UIVerticalToggleSet setOptions(String[] options) {
    if (this.options != options) {
      this.options = options;
      this.value = 0;
      this.boundaries = new int[options.length];
      computeBoundaries();
      redraw();
    }
    return this;
  }

  public UIVerticalToggleSet setParameter(DiscreteParameter parameter) {
    if (this.parameter != parameter) {
      if (this.parameter != null) {
        this.parameter.removeListener(this);
      }
      this.parameter = parameter;
      if (this.parameter != null) {
        this.parameter.addListener(this);
        String[] options = this.parameter.getOptions();
        if (options != null) {
          setOptions(options);
        }
        setValue(this.parameter.getValuei());
      }
    }
    return this;
  }

  public void onParameterChanged(LXParameter parameter) {
    if (parameter == this.parameter) {
      setValue(this.options[this.parameter.getValuei()]);
    }
  }

  /** computs the y position of the bottom of each button */
  private void computeBoundaries() {
    if (this.boundaries == null) {
      return;
    }
    for (int i = 0; i < this.boundaries.length; ++i) {
      this.boundaries[i] = (int) ((i + 1) * (this.height-1) / this.boundaries.length);
    }
  }

  public int getValueIndex() {
    return this.value;
  }

  public String getValue() {
    return this.options[this.value];
  }

  public UIVerticalToggleSet setValue(String value) {
    for (int i = 0; i < this.options.length; ++i) {
      if (this.options[i] == value) {
        return setValue(i);
      }
    }

    // That string doesn't exist
    String optStr = "{" + String.join(",", this.options) + "}";
    throw new IllegalArgumentException("Not a valid option in UIVerticalToggleSet: "
        + value + " " + optStr);
  }

  public UIVerticalToggleSet setValue(int value) {
    if (this.value != value) {
      if (value < 0 || value >= this.options.length) {
        throw new IllegalArgumentException("Invalid index to setValue(): "  + value);
      }
      this.value = value;
      if (this.parameter != null) {
        this.parameter.setValue(value);
      }
      onToggle(getValue());
      redraw();
    }
    return this;
  }

  @Override
  public void onDraw(UI ui, PGraphics pg) {
    pg.stroke(ui.theme.getControlBorderColor());
    pg.fill(ui.theme.getControlBackgroundColor());
    pg.rect(0, 0, this.width-1, this.height-1);
    for (int yB : this.boundaries) {
      pg.line(1, yB, this.width - 2, yB);
    }

    pg.noStroke();
    pg.textAlign(PConstants.CENTER, PConstants.CENTER);
    pg.textFont(hasFont() ? getFont() : ui.theme.getControlFont());
    int topBoundary = 0;

    for (int i = 0; i < this.options.length; ++i) {
      boolean isActive = (i == this.value);
      if (isActive) {
        pg.fill(ui.theme.getPrimaryColor());
        pg.rect(1, topBoundary + 1, this.width - 2, this.boundaries[i] - topBoundary - 2);
      }
      pg.fill(isActive ? UI.WHITE : ui.theme.getControlTextColor());
      pg.text(this.options[i], this.width / 2, (topBoundary + this.boundaries[i]) / 2.f);
      topBoundary = this.boundaries[i];
    }
  }

  protected void onToggle(String option) {
  }

  @Override
  protected void onMousePressed(MouseEvent mouseEvent, float mx, float my) {
    for (int i = 0; i < this.boundaries.length; ++i) {
      if (my < this.boundaries[i]) {
        setValue(i);
        break;
      }
    }
  }

  @Override
  protected void onKeyPressed(KeyEvent keyEvent, char keyChar, int keyCode) {
    if ((keyCode == java.awt.event.KeyEvent.VK_LEFT)
        || (keyCode == java.awt.event.KeyEvent.VK_UP)) {
      consumeKeyEvent();
      setValue(LXUtils.constrain(this.value - 1, 0, this.options.length - 1));
    } else if ((keyCode == java.awt.event.KeyEvent.VK_RIGHT)
        || (keyCode == java.awt.event.KeyEvent.VK_DOWN)) {
      consumeKeyEvent();
      setValue(LXUtils.constrain(this.value + 1, 0, this.options.length - 1));
    }
  }

  @Override
  public LXParameter getControlTarget() {
    return this.parameter;
  }
}
