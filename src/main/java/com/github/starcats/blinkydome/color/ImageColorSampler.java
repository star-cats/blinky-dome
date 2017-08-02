package com.github.starcats.blinkydome.color;

import heronarts.lx.LX;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXNormalizedParameter;
import processing.core.PApplet;
import processing.core.PImage;

/**
 * Color source that samples colors from an image containing strips of color patterns.  Strips could be simple patternMap
 * (eg patternMap.png) or could be elaborate colorful patterns (eg patterns.png).
 *
 * Image is sampled by mapping a normalized value 0.0-1.0 to a width on some strip in the image.
 *
 * Image path should exist somewhere in the resources path (eg src/main/resources).
 *
 * Exposes .patternSelect, which is a DiscreteParameter of {@link PatternSampler} instances.  Use the parameter to
 * select an appropriate pattern, then use {@link PatternSampler#getColor} to extract a color from the pattern image.
 */
public class ImageColorSampler extends LXComponent implements ColorMappingSourceFamily {
  private static final int GRADIENT_HEIGHT = 10; // normal screenshot
  //private static final int GRADIENT_HEIGHT = 20; // retina screenshot (2x resolution)

  private final BooleanParameter randomSourceTrigger;

  private final PatternSampler[] samplers;
  private String label;

  /**
   * DiscreteParameter of {@link PatternSampler} objects.
   * Controls which color strip in the image is used for sampling.
   */
  private final DiscreteParameter patternSelect;

  /**
   * Processing PImage with the loaded sample source
   */
  public final PImage patternMap;

  public ImageColorSampler(PApplet p, LX lx) {
    this(p, lx, "patterns.png");
  }

  public ImageColorSampler(PApplet p, LX lx, String filepath) {
    super(lx, "ImageColorSampler:" + filepath);
    label = filepath;
    patternMap = p.loadImage(filepath);

    randomSourceTrigger = (BooleanParameter) new BooleanParameter("shuffle", false)
        .setMode(BooleanParameter.Mode.MOMENTARY)
        .setDescription("Trigger a random source")
        .addListener(param -> {
          if (param.getValue() == 0) return;

          this.setRandomSource();
        });
    addParameter(randomSourceTrigger);


    int numPatterns = patternMap.height / GRADIENT_HEIGHT;

    samplers = new PatternSampler[numPatterns];
    for (int i = 0; i < numPatterns; i++) {
      samplers[i] = new PatternSampler(i);
    }

    patternSelect = new DiscreteParameter(filepath, samplers);
  }

  /**
   * Helper class that actually picks out a color from the patternMap image.
   *
   * When just the .patternSelect DiscreteParameter is referenced, these instances are the Objects that come out of the
   * DiscreteParameter so you don't need a reference to the actual ImageColorSampler.
   */
  public class PatternSampler implements ColorMappingSource {

    private final int patternI;

    private PatternSampler(int patternI) {
      this.patternI = patternI;
    }

    @Override
    public int getColor(double pos) {
      return patternMap.get(
          (int)(Math.abs(pos * patternMap.width) % (patternMap.width - 1)),
          patternI * GRADIENT_HEIGHT + GRADIENT_HEIGHT / 2 // add half height to hit the middle of gradient strips
      );
    }

    @Override
    public int getColor(LXNormalizedParameter param) {
      return getColor(param.getNormalized());
    }

    /** Used to generate value labels when extracted from parameter */
    public String toString() {
      return "" + patternI;
    }
  }

  @Override
  public BooleanParameter getRandomSourceTrigger() {
    return randomSourceTrigger;
  }

  protected void setRandomSource() {
    patternSelect.setValue(
        patternSelect.getMinValue() +
        (int)(Math.random() * patternSelect.getRange())
    );
  }

  @Override
  public DiscreteParameter getSourceSelect() {
    return patternSelect;
  }

  @Override
  public int getColor(double pos) {
    return ((PatternSampler) patternSelect.getObject()).getColor(pos);
  }

  @Override
  public int getColor(LXNormalizedParameter pos) {
    return getColor(pos.getNormalized());
  }

  public float getHue(double pos) {
    return LXColor.h(getColor(pos));
  }

  public int getNumPatterns() {
    return samplers.length;
  }

  /**
   * Given a y-position on the .patternMap PImage (eg mouse click), returns the PatternSampler associated with it.
   *
   * Useful for getting the appropriate parameter option for UI elements.
   */
  public PatternSampler getSamplerFromMouseY(float my) {
    if (my < 0) {
      return samplers[0];
    } else if (my > patternMap.height - 1) {
      return samplers[samplers.length - 1];
    }

    int i = (int) (my / GRADIENT_HEIGHT);

    return samplers[ Math.max(0, Math.min(i, samplers.length - 1)) ];
  }

  public String getLabel() {
    return label;
  }

  public ImageColorSampler setLabel(String label) {
    this.label = label;
    return this;
  }

  @Override
  public int getNumSources() {
    return samplers.length;
  }

  public String toString() {
    return label;
  }
}
