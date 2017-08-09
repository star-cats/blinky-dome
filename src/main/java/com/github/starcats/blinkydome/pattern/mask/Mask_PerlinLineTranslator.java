package com.github.starcats.blinkydome.pattern.mask;

import com.github.starcats.blinkydome.pattern.perlin.PerlinNoiseLineTranslator;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.LXWaveshape;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.parameter.LXCompoundModulation;
import processing.core.PApplet;

import java.util.List;

/**
 * Mask implementation of {@link PerlinNoiseLineTranslator}
 */
public class Mask_PerlinLineTranslator extends LXPattern {

  private List<? extends LXFixture> fixtures;
  private PerlinNoiseLineTranslator fixtureMapper;

  public Mask_PerlinLineTranslator(LX lx, PApplet p, List<? extends LXFixture> fixtures) {
    super(lx);

    this.fixtures = fixtures;
    this.fixtureMapper = new PerlinNoiseLineTranslator(p, fixtures, "m", "mask");

    addParameter(fixtureMapper.noiseZoom);
    addParameter(fixtureMapper.noiseSpeed);
    addParameter(fixtureMapper.fixtureRotation);
  }

  public Mask_PerlinLineTranslator initModulations() {
    VariableLFO rotationLFO = new VariableLFO("rotation");
    rotationLFO.period.setValue(10000);
    rotationLFO.waveshape.setValue(LXWaveshape.UP);
    this.modulation.addModulator(rotationLFO);

    LXCompoundModulation rotationModulation = new LXCompoundModulation(rotationLFO, fixtureMapper.fixtureRotation);
    rotationModulation.range.setValue(1);
    this.modulation.addModulation(rotationModulation);

    rotationLFO.start();

    return this;
  }

  @Override
  protected void run(double deltaMs) {
    this.fixtureMapper.step(deltaMs);

    for (LXFixture fixture : this.fixtures) {
      for (LXPoint pt : fixture.getPoints()) {
        int v = (int) (fixtureMapper.getPtValue(pt) * 255.);
        setColor(pt.index, LXColor.rgb(v, v, v));
      }
    }
  }
}
