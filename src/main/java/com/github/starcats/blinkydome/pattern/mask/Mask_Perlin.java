package com.github.starcats.blinkydome.pattern.mask;

import com.github.starcats.blinkydome.pattern.perlin.MaskingColorizer;
import com.github.starcats.blinkydome.pattern.perlin.PerlinNoiseExplorer;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import processing.core.PApplet;

/**
 * Perlin noise mask
 */
public class Mask_Perlin extends LXPattern {

  /** The speed of the perlin noise pattern used for hue mapping */
  public final CompoundParameter speed = (CompoundParameter) new CompoundParameter("m speed", .1)
      .setDescription("The speed of the perlin noise pattern used for masking mapping")
      .setExponent(2)
      .setFormatter(value -> String.format("%.3f", value));

  /** Multiplier ('zoom') of the perlin noise pattern used for hue mapping */
  public final CompoundParameter zoom = (CompoundParameter) new CompoundParameter("m zoom", 0.01, 0.005, 0.25)
      .setDescription("Multiplier ('zoom') of the perlin noise pattern used for masking mapping")
      .setExponent(2)
      .setFormatter(value -> String.format("%.3f", value));

  public final BooleanParameter randomizeDirection = new BooleanParameter("ran dir", false)
      .setDescription("Randomize direction of perlin noise travel")
      .setMode(BooleanParameter.Mode.MOMENTARY);

  /** Perlin noise travel generator.  Consider changing the bias params appropriately for your model */
  public final PerlinNoiseExplorer perlinNoise;

  private final MaskingColorizer colorizer;

  public Mask_Perlin(LX lx, PApplet p) {
    super(lx);

    this.perlinNoise = new PerlinNoiseExplorer(p, this.model.getPoints(), "m ", "masking", speed, zoom);

    addParameter(this.speed);
    addParameter(this.zoom);

    colorizer = new MaskingColorizer(perlinNoise);
    addParameter(colorizer.levelCurveParam);

    randomizeDirection.addListener(param -> {
      if (param.getValue() != 1) return;

      perlinNoise.randomizeDirection();
    });
    addParameter(randomizeDirection);

  }

  @Override
  protected void run(double deltaMs) {
    perlinNoise.step(deltaMs);

    for (LXPoint p : model.getPoints()) {
      setColor(p.index, colorizer.getColor(p));
    }
  }
}
