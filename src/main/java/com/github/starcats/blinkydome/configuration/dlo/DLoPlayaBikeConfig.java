package com.github.starcats.blinkydome.configuration.dlo;

import com.github.starcats.blinkydome.color.ImageColorSamplerGroup;
import com.github.starcats.blinkydome.configuration.AbstractStarcatsLxConfig;
import com.github.starcats.blinkydome.configuration.CommonScLxConfigUtils;
import com.github.starcats.blinkydome.model.dlo.DLoPlayaBikeModel;
import com.github.starcats.blinkydome.pattern.FixtureColorBarsPattern;
import com.github.starcats.blinkydome.pattern.PalettePainterPattern;
import com.github.starcats.blinkydome.pattern.PerlinBreathing;
import com.github.starcats.blinkydome.pattern.PerlinNoisePattern;
import com.github.starcats.blinkydome.pattern.RainbowZPattern;
import com.github.starcats.blinkydome.pattern.mask.Mask_Perlin;
import com.github.starcats.blinkydome.pattern.mask.Mask_WipePattern;
import com.github.starcats.blinkydome.pattern.mask.TMask_Starlight;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LX;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
import heronarts.lx.audio.BandGate;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.output.FadecandyOutput;
import heronarts.lx.output.LXOutput;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;

import java.util.Arrays;
import java.util.List;

/**
 * Standard config for {@link DLoPlayaBikeModel} model
 */
public class DLoPlayaBikeConfig extends AbstractStarcatsLxConfig<DLoPlayaBikeModel> {

  // Components
  private StarCatFFT starCatFFT;
  protected ImageColorSamplerGroup colorSampler;

  // Modulators
  private BandGate kickModulator;

  public DLoPlayaBikeConfig(PApplet p) {
    super(p);
  }

  @Override
  protected DLoPlayaBikeModel makeModel() {
    return DLoPlayaBikeModel.makeModel(false);
  }

  @Override
  protected List<LXOutput> constructOutputs(LX lx) {

    lx.engine.output.brightness.setValue(0.90); // TODO: 0.8 is safer if doing strobe effects

    return Arrays.asList(
        new FadecandyOutput(lx, "localhost", 7890)
    );
  }

  @Override
  protected void initComponents(PApplet p, LX lx, DLoPlayaBikeModel model) {
    starCatFFT = CommonScLxConfigUtils.Components.makeStarcatFft(lx);

    // For fake beat detection
//    AudioDetector.init(null);

    colorSampler = CommonScLxConfigUtils.Components.makeColorSampler(p, lx);
    colorSampler.getSourceSelect().setValue(12);
  }

  @Override
  protected List<LXModulator> constructModulators(PApplet p, LX lx, DLoPlayaBikeModel model) {
    kickModulator = CommonScLxConfigUtils.Modulators.makeKickModulator(lx);

    return Arrays.asList(
        kickModulator
    );
  }

  @Override
  protected int getNumChannels() {
    return 2;
  }

  @Override
  protected void configChannel(int channelNum, LXChannel channel) {
    if (channelNum == 1) {
      channel.label.setValue("Mask");
      configMaskPatterns(channel);

    } else {
      channel.label.setValue("Colorizer");
      channel.fader.setValue(1);
      channel.blendMode.setValue(1); // Multiply against masks
      configColorPatterns(channel);
    }
  }

  private void configMaskPatterns(LXChannel channel) {
//    Mask_AllWhite allWhite = new Mask_AllWhite(lx, model.getWhiskerFixtures());

    Mask_Perlin perlin = new Mask_Perlin(lx, p);
    perlin.speed.setValue(0.19);
    perlin.zoom.setValue(0.010);

    TMask_Starlight starlight = new TMask_Starlight(p, lx, 1);
    starlight.numStars.setValue(170);

    Mask_WipePattern wipe = new Mask_WipePattern(lx);
    wipe.widthPx.setValue(1.05);

    channel.setPatterns(new LXPattern[] {
//        allWhite,
        perlin,
        starlight,
        wipe
    });
  }

  private void configColorPatterns(LXChannel channel) {
    // PerlinNoisePattern: apply defaults appropriate for Icosastar mapping size
    // --------------------
    PerlinNoisePattern perlinNoisePattern = new PerlinNoisePattern(lx, p, starCatFFT.beat, colorSampler);
//    PerlinNoisePattern perlinNoisePattern = new PerlinNoisePattern(lx, p, new MockBeatDetect(), colorSampler);
    perlinNoisePattern.hueSpeed.setValue(0.2);

    // FixtureColorBarsPattern: Wire it up to engine-wide modulation sources
    // --------------------
    FixtureColorBarsPattern fixtureColorBarsPattern = new FixtureColorBarsPattern(lx, model.fixtures, colorSampler)
        .initModulations(kickModulator);


    // PerlinNoisePattern: apply defaults appropriate for BlinkyDomeModel mapping size
    // --------------------
    PerlinBreathing perlinBreathing = new PerlinBreathing(lx, p, model.getPoints(), colorSampler,
        new LXVector(0, 0, -1),
        new LXVector(0, 0, 1),
        PerlinBreathing.BreathEasingSupplier.EXP_OUT_CUBIC_INOUT
    ).initModulators();
    perlinBreathing.perlinNoiseFieldZoom.setValue(0.02);
    perlinBreathing.getSpeedModulationRange().setValue(0.20);


    channel.setPatterns(new LXPattern[] {
        perlinNoisePattern,
        perlinBreathing,
        // TODO: bring in FixtureTracerPattern
        new RainbowZPattern(lx),
        new PalettePainterPattern(lx, lx.palette), // feed it LX default palette (controlled by Studio's palette UI)
        fixtureColorBarsPattern
    });

    // TODO: config against headless raspi
    // RaspiPerlinNoiseDefaults.applyPresetsIfRaspiGpio(perlinNoise);
  }
}
