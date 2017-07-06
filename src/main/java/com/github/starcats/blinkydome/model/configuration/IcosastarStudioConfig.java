package com.github.starcats.blinkydome.model.configuration;

import com.github.starcats.blinkydome.model.Icosastar;
import com.github.starcats.blinkydome.model.util.ConnectedVectorStripModel;
import com.github.starcats.blinkydome.pattern.FixtureColorBarsPattern;
import com.github.starcats.blinkydome.pattern.PalettePainterPattern;
import com.github.starcats.blinkydome.pattern.PerlinNoisePattern;
import com.github.starcats.blinkydome.pattern.RainbowZPattern;
import com.github.starcats.blinkydome.pattern.effects.WhiteWipePattern;
import heronarts.lx.LX;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.output.FadecandyOutput;
import heronarts.lx.output.LXOutput;
import heronarts.p3lx.LXStudio;
import processing.core.PApplet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * LXStudio / GUI config for {@link com.github.starcats.blinkydome.model.Icosastar} model
 */
public class IcosastarStudioConfig extends CommonStarcatsLxModelConfig<Icosastar> {

  public IcosastarStudioConfig(PApplet p) {
    super(p);
  }

  @Override
  protected Icosastar makeModel() {
    return Icosastar.makeModel();
  }

  @Override
  protected List<LXOutput> constructOutputs(LX lx) {
    return Arrays.asList(
        new FadecandyOutput(lx, "localhost", 7890)
    );
  }

  @Override
  protected void initComponentsImpl(PApplet p, LX lx, Icosastar model) {
    // no-op
  }

  @Override
  protected List<LXModulator> constructModulatorsImpl(PApplet p, LX lx, Icosastar model) {
    return Collections.emptyList();
  }

  @Override
  protected void configChannel(int channelNum, LXChannel channel) {

    // FixtureColorBarsPattern: Wire it up to engine-wide modulation sources
    // --------------------
    List<ConnectedVectorStripModel> allSpokes = new ArrayList<>();
    allSpokes.addAll(model.innerSpokeLeds);
    allSpokes.addAll(model.outerSpokeLeds);
    allSpokes.addAll(model.ring1Leds);
    FixtureColorBarsPattern fixtureColorBarsPattern = wireUpFixtureColorBarsPattern(allSpokes);

    // PerlinNoisePattern: apply defaults appropriate for BlinkyDome mapping size
    // --------------------
    PerlinNoisePattern perlinNoisePattern = new PerlinNoisePattern(lx, p, starCatFFT.beat, colorSamplers);
    perlinNoisePattern.brightnessBoostNoise.noiseSpeed.setValue(2.0 * perlinNoisePattern.hueSpeed.getValue());
    perlinNoisePattern.brightnessBoostNoise.noiseXForm.setValue(0.5 * perlinNoisePattern.hueXForm.getValue());

    channel.setPatterns(new LXPattern[] {
        perlinNoisePattern,
        // TODO: bring in FixtureTracerPattern
        new RainbowZPattern(lx),
        new PalettePainterPattern(lx, lx.palette), // feed it LX default palette (controlled by Studio's palette UI)
        new WhiteWipePattern(lx),
        fixtureColorBarsPattern
    });

    // TODO: config against headless raspi
    // RaspiPerlinNoiseDefaults.applyPresetsIfRaspiGpio(perlinNoise);
  }

  @Override
  protected void onUIReadyImpl(LXStudio lx, LXStudio.UI ui) {
    // no-op
  }
}
