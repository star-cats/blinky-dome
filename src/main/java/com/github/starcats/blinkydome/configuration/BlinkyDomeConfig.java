package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.color.ImageColorSampler;
import com.github.starcats.blinkydome.color.ImageColorSamplerClan;
import com.github.starcats.blinkydome.model.BlinkyDome;
import com.github.starcats.blinkydome.pattern.FixtureColorBarsPattern;
import com.github.starcats.blinkydome.pattern.PalettePainterPattern;
import com.github.starcats.blinkydome.pattern.PerlinNoisePattern;
import com.github.starcats.blinkydome.pattern.RainbowZPattern;
import com.github.starcats.blinkydome.pattern.blinky_dome.BlinkyDomeFixtureSelectorPattern;
import com.github.starcats.blinkydome.pattern.blinky_dome.FFTBandPattern;
import com.github.starcats.blinkydome.pattern.effects.WhiteWipePattern;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LX;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
import heronarts.lx.audio.BandGate;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.output.FadecandyOutput;
import heronarts.lx.output.LXOutput;
import heronarts.lx.parameter.DiscreteParameter;
import processing.core.PApplet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Headless configuration for the {@link BlinkyDome} model
 */
public class BlinkyDomeConfig extends AbstractStarcatsLxConfig<BlinkyDome> {

  // Components
  private StarCatFFT starCatFFT;
  protected ImageColorSamplerClan colorSampler;
  protected ImageColorSampler gradientColorSource;
  protected ImageColorSampler patternColorSource;

  // Modulators
  private BandGate kickModulator;
  private VariableLFO colorMappingLFO;

  public BlinkyDomeConfig(PApplet p) {
    super(p);
  }

  @Override
  protected BlinkyDome makeModel() {
    return BlinkyDome.makeModel(p);
  }

  @Override
  protected void initComponents(PApplet p, LX lx, BlinkyDome model) {
    starCatFFT = CommonScLxConfigUtils.Components.makeStarcatFft(lx);
    colorSampler = CommonScLxConfigUtils.Components.makeColorSampler(p);

    gradientColorSource = new ImageColorSampler(p, "gradients.png");
    patternColorSource = new ImageColorSampler(p, "patterns.png");
    colorSampler = new ImageColorSamplerClan(new ImageColorSampler[] {
        gradientColorSource,
        patternColorSource
    });
  }

  @Override
  protected List<LXModulator> constructModulators(PApplet p, LX lx, BlinkyDome model) {
    kickModulator = CommonScLxConfigUtils.Modulators.makeKickModulator(lx);
    colorMappingLFO = CommonScLxConfigUtils.Modulators.makeColorMappingLFO();

    return Arrays.asList(
        kickModulator,
        colorMappingLFO
    );
  }

  @Override
  protected List<LXOutput> constructOutputs(LX lx) {
    return Collections.singletonList(
        new FadecandyOutput(lx, "localhost", 7890)
        // TODO: pixelpusher, etc.
    );
  }

  @Override
  protected int getNumChannels() {
    return 2;
  }

  @Override
  protected void configChannel(int channelNum, LXChannel channel) {
    List<LXPattern> patterns;
    if (channelNum == 1) {
      patterns = makeStandardPatterns();

    // Channel 2:
    } else {
      channel.fader.setValue(1.0);
      patterns = new ArrayList<>();
      patterns.add(new WhiteWipePattern(lx));
      patterns.addAll(makeStandardPatterns());

    }

    // common:
    channel.setPatterns( patterns.toArray( new LXPattern[patterns.size()] ) );
  }

  /** Creates standard set of BlinkyDome patterns */
  private List<LXPattern> makeStandardPatterns() {

    // FixtureColorBarsPattern: Wire it up to engine-wide modulation sources
    // --------------------
    FixtureColorBarsPattern fixtureColorBarsPattern =
        CommonScLxConfigUtils.Patterns.wireUpFixtureColorBarsPattern(
            lx, model.allTriangles, colorSampler, colorMappingLFO, kickModulator
        );


    // PerlinNoisePattern: apply defaults appropriate for BlinkyDome mapping size
    // --------------------
    PerlinNoisePattern perlinNoisePattern = new PerlinNoisePattern(lx, p, starCatFFT.beat, colorSampler);
    // If the color sampler changes, adjust perlin settings to be appropriate for selected color sampler family
    colorSampler.samplerSelector.addListener(param -> {
      if (perlinNoisePattern.getChannel().getActivePattern() != perlinNoisePattern) {
        return;
      }

      DiscreteParameter parameter = (DiscreteParameter) param;
      if (parameter.getObject() == gradientColorSource) {
        perlinNoisePattern.hueSpeed.setValue(0.25);
      } else if (parameter.getObject() == patternColorSource) {
        perlinNoisePattern.hueSpeed.setValue(0.10);
      }
    });
    perlinNoisePattern.hueSpeed.setValue(0.25);
    perlinNoisePattern.brightnessBoostNoise.noiseSpeed.setValue(2.0 * perlinNoisePattern.hueSpeed.getValue());
    perlinNoisePattern.brightnessBoostNoise.noiseZoom.setValue(0.5 * perlinNoisePattern.hueXForm.getValue());


    // Normal patterns
    // --------------------
    return Arrays.asList(
        perlinNoisePattern,
        new FFTBandPattern(lx, model, starCatFFT),
        new RainbowZPattern(lx),
        new PalettePainterPattern(lx, lx.palette), // feed it LX default palette (controlled by Studio's palette UI)
        new BlinkyDomeFixtureSelectorPattern(lx, model),
        fixtureColorBarsPattern
    );
  }


}
