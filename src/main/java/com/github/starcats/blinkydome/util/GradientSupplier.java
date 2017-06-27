package com.github.starcats.blinkydome.util;

import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.DiscreteParameter;
import processing.core.PApplet;
import processing.core.PImage;

/**
 * Picks a color from the gradients.png image
 */
public class GradientSupplier {
  private static final int GRADIENT_HEIGHT = 10; // normal screenshot
  //private static final int GRADIENT_HEIGHT = 20; // retina screenshot (2x resolution)

  public final DiscreteParameter gradientSelect;

  private final PImage gradients;

  public GradientSupplier(PApplet p) {
    this(p, false);
  }

  public GradientSupplier(PApplet p, boolean isPatterns) {
    gradients = p.loadImage(isPatterns ? "patterns.png" : "gradients.png");
    gradientSelect = new DiscreteParameter(isPatterns ? "pattern" : "gradient", 0, gradients.height / GRADIENT_HEIGHT);
  }

  public void setRandomGradient() {
    gradientSelect.setValue((int)(Math.random() * gradientSelect.getMaxValue()));
  }

  /**
   * Gets a color of the gradient
   * @param pos Position in the gradient, from 0.0 to 1.0
   * @return color
   */
  public int getColor(double pos) {
    return gradients.get(
        (int)(Math.abs(pos * gradients.width) % (gradients.width - 1)),
        GRADIENT_HEIGHT / 2 + gradientSelect.getValuei() * GRADIENT_HEIGHT
    );
  }

  public float getHue(double pos) {
    return LXColor.h(getColor(pos));
  }

}
