package com.github.starcats.blinkydome.util;

import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.DiscreteParameter;
import processing.core.PApplet;
import processing.core.PImage;

/**
 * Color source that samples colors from an image containing stips of gradients (see gradients.png or patterns.png).
 *
 * Exposes .gradientSelect, which is a DiscreteParameter of {@link GradientSampler} instances.  Use the parameter to
 * select an appropriate gradient, then use {@link GradientSampler#getColor} to extract a color from the gradient.
 */
public class GradientSupplier {
  private static final int GRADIENT_HEIGHT = 10; // normal screenshot
  //private static final int GRADIENT_HEIGHT = 20; // retina screenshot (2x resolution)

  private final int numGradients;
  private final GradientSampler[] samplers;
  private String label;

  public final DiscreteParameter gradientSelect;

  public final PImage gradients;

  public GradientSupplier(PApplet p) {
    this(p, false);
  }

  public GradientSupplier(PApplet p, boolean isPatterns) {
    label = isPatterns ? "patterns.png" : "gradients.png";
    gradients = p.loadImage(isPatterns ? "patterns.png" : "gradients.png");
    numGradients = gradients.height / GRADIENT_HEIGHT;

    samplers = new GradientSampler[numGradients];
    for (int i=0; i < numGradients; i++) {
      samplers[i] = new GradientSampler(i);
    }

    gradientSelect = new DiscreteParameter(isPatterns ? "pattern" : "gradient", samplers);
  }

  /**
   * Helper class that actually picks out a color from the gradient.
   *
   * When just the DiscreteParameter is referenced, these instances are the Objects that come out of the
   * DiscreteParameter so you don't need a reference to the actual GradientSupplier.
   */
  public class GradientSampler {

    private final int gradientI;

    private GradientSampler(int gradientI) {
      this.gradientI = gradientI;
    }

    public int getColor(double pos) {
      return gradients.get(
          (int)(Math.abs(pos * gradients.width) % (gradients.width - 1)),
          gradientI * GRADIENT_HEIGHT + GRADIENT_HEIGHT / 2 // add half height to hit the middle of grad strips
      );
    }

    /** Used to generate value labels when extracted from parameter */
    public String toString() {
      return "" + gradientI;
    }
  }

  public void setRandomGradient() {
    gradientSelect.setValue(
        gradientSelect.getMinValue() +
        (int)(Math.random() * gradientSelect.getRange())
    );
  }

  /**
   * Gets a color of the gradient
   * @param pos Position in the gradient, from 0.0 to 1.0
   * @return color
   */
  public int getColor(double pos) {
    return ((GradientSampler) gradientSelect.getObject()).getColor(pos);
  }

  public float getHue(double pos) {
    return LXColor.h(getColor(pos));
  }

  public int getNumGradients() {
    return numGradients;
  }

  /**
   * Given a y-position on the .gradients PImage (eg mouse click), returns the GradientSampler associated with it.
   *
   * Useful for getting the appropriate parameter option for UI elements.
   */
  public GradientSampler getSamplerFromMouseY(float my) {
    if (my < 0) {
      return samplers[0];
    } else if (my > gradients.height - 1) {
      return samplers[samplers.length - 1];
    }

    int i = (int) (my / GRADIENT_HEIGHT);

    return samplers[ Math.max(0, Math.min(i, samplers.length - 1)) ];
  }

  public String getLabel() {
    return label;
  }

  public GradientSupplier setLabel(String label) {
    this.label = label;
    return this;
  }

  public String toString() {
    return label;
  }
}
