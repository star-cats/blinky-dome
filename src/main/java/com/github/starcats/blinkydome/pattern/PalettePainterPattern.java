package com.github.starcats.blinkydome.pattern;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXPalette;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXCompoundModulation;

/**
 * Paints all the model pixels according to some palette input.  Change the underlying palette to change how the
 * pattern paints the pixels.
 */
public class PalettePainterPattern extends LXPattern {

  private final LXPalette palette;
  public final CompoundParameter brightnessParam;

  public PalettePainterPattern(LX lx) {
    this(lx, lx.palette);
  }

  public PalettePainterPattern(LX lx, LXPalette palette) {
    super(lx);

    this.palette = palette;

    brightnessParam = new CompoundParameter("brightness", 100, 0, 100);
    brightnessParam.setDescription("Sets the brightness (black) of the hue");
    addParameter(brightnessParam);

    // Bonus: Modulate the brightness of the palette using the LX engine's audio VU meter
    // (This can be added/removed in LXStudio UI, but this is how you might programmatically add it)
    LXCompoundModulation brightnessModulation = new LXCompoundModulation(
        lx.engine.audio.meter, brightnessParam
    );
    brightnessModulation.range.setValue(0.7);
    brightnessParam.setValue(10);
    lx.engine.modulation.addModulation(brightnessModulation);
  }

  public void run(double deltaMx) {
    for (LXPoint p : model.points) {
      colors[p.index] = palette.getColor(brightnessParam.getValue());
    }
  }
}
