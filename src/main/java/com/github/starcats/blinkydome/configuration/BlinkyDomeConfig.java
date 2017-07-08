package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.color.ImageColorSampler;
import com.github.starcats.blinkydome.color.ImageColorSamplerClan;
import com.github.starcats.blinkydome.model.BlinkyDome;
import com.github.starcats.blinkydome.pattern.FixtureColorBarsPattern;
import com.github.starcats.blinkydome.pattern.PalettePainterPattern;
import com.github.starcats.blinkydome.pattern.PerlinBreathing;
import com.github.starcats.blinkydome.pattern.PerlinNoisePattern;
import com.github.starcats.blinkydome.pattern.RainbowZPattern;
import com.github.starcats.blinkydome.pattern.blinky_dome.BlinkyDomeFixtureSelectorPattern;
import com.github.starcats.blinkydome.pattern.blinky_dome.FFTBandPattern;
import com.github.starcats.blinkydome.pattern.mask.Mask_AngleSweep;
import com.github.starcats.blinkydome.pattern.mask.Mask_BrightnessBeatBoost;
import com.github.starcats.blinkydome.pattern.mask.Mask_FixtureDottedLine;
import com.github.starcats.blinkydome.pattern.mask.Mask_Perlin;
import com.github.starcats.blinkydome.pattern.mask.Mask_RandomFixtureSelector;
import com.github.starcats.blinkydome.pattern.mask.Mask_RollingBouncingDisc;
import com.github.starcats.blinkydome.pattern.mask.Mask_WipePattern;
import com.github.starcats.blinkydome.pattern.mask.Mask_XyzFilter;
import com.github.starcats.blinkydome.pixelpusher.PixelPusherOutput;
import com.github.starcats.blinkydome.util.StarCatFFT;
import com.heroicrobot.dropbit.registry.DeviceRegistry;
import heronarts.lx.LX;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
import heronarts.lx.audio.BandGate;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.output.FadecandyOutput;
import heronarts.lx.output.LXOutput;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXCompoundModulation;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;
import processing.core.PVector;

import java.util.ArrayList;
import java.util.Arrays;
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
  protected CommonScLxConfigUtils.MinimBeatTriggers minimBeatTriggers;

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

    minimBeatTriggers = new CommonScLxConfigUtils.MinimBeatTriggers(lx, starCatFFT);
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

    DeviceRegistry ppRegistry = new DeviceRegistry();
    ppRegistry.setLogging(false);
    ppRegistry.setExtraDelay(0);
    ppRegistry.setAutoThrottle(true);
    ppRegistry.setAntiLog(true);

    return Arrays.asList(
        new FadecandyOutput(lx, "localhost", 7890),
        new PixelPusherOutput(lx, getModel(), ppRegistry)
            .addDebugOutput()
    );
  }

  @Override
  protected int getNumChannels() {
    return 3;
  }

  @Override
  protected void configChannel(int channelNum, LXChannel channel) {
    List<LXPattern> patterns;
    if (channelNum == 1) {
      channel.label.setValue("BasePatterns");
      patterns = makeStandardPatterns();

    // Channel 2: Primary masks
    } else if (channelNum == 2) {
      channel.label.setValue("Masks 1");
      channel.blendMode.setValue(1); // Multiply

      // Modulate visibility of this mask down on kick drums
      channel.fader.setValue(1.0);
      LXCompoundModulation ch2MaskVisibilityMod = new LXCompoundModulation(kickModulator, channel.fader);
      ch2MaskVisibilityMod.range.setValue(-1);
      lx.engine.modulation.addModulation(ch2MaskVisibilityMod);

      patterns = makeMasks();

    // Channel 3: Secondary masks
    } else {
      channel.label.setValue("Masks 2");
      channel.blendMode.setValue(1); // Multiply

      // Modulate visibility of this mask up on kick drums (opposite of ch2)
      channel.fader.setValue(0.1);
      LXCompoundModulation ch3MaskVisibilityMod = new LXCompoundModulation(kickModulator, channel.fader);
      ch3MaskVisibilityMod.range.setValue(1);
      lx.engine.modulation.addModulation(ch3MaskVisibilityMod);

      patterns = makeMasks();
    }

    // common:
    channel.setPatterns( patterns.toArray( new LXPattern[patterns.size()] ) );

    if (channelNum == 3) {
      // Select default mask2
      channel.goIndex(3);
    }
  }

  private List<LXPattern> makeMasks() {
    Mask_RollingBouncingDisc mask_disc = new Mask_RollingBouncingDisc(lx,
        new LXVector(0, model.yMin, 0),
        new LXVector(0, model.yMax - model.yMin, 0),
        new LXVector(1, 0, 0)
    )
        .addDemoBounce();
    mask_disc.discThicknessRad.setValue(0.15);



    Mask_Perlin mask_perlin = new Mask_Perlin(lx, p);
    mask_perlin.speed.setValue(0.02);
    mask_perlin.zoom.setValue(0.2);
    // Bias noise to be mostly going up/down
    mask_perlin.perlinNoise.xPosBias.setValue(0.2);
    mask_perlin.perlinNoise.xNegBias.setValue(0.2);
    mask_perlin.perlinNoise.zPosBias.setValue(0.2);
    mask_perlin.perlinNoise.zNegBias.setValue(0.2);


    Mask_BrightnessBeatBoost mask_bbb = new Mask_BrightnessBeatBoost(lx);
    minimBeatTriggers.triggerWithKick(mask_bbb.trigger);


    Mask_RandomFixtureSelector randomFixtureMask = new Mask_RandomFixtureSelector(lx, model.allTriangles);
    minimBeatTriggers.triggerWithKick(randomFixtureMask);


    Mask_WipePattern wipeMask = new Mask_WipePattern(lx);
    minimBeatTriggers.triggerWithKick(wipeMask);
    wipeMask.widthPx.setValue(20);


    List<LXPattern> patterns = new ArrayList<>();
    patterns.add(mask_disc);
    patterns.add(mask_perlin);
    patterns.add(mask_bbb);
    patterns.add(new Mask_FixtureDottedLine(lx, model.allTriangles));
    patterns.add(new Mask_AngleSweep(lx, new PVector(1, 0, 0), model.allTriangles, lx.tempo));
    patterns.add(randomFixtureMask);
    patterns.add(wipeMask);
    patterns.add(new Mask_XyzFilter(lx));

    // patterns.addAll(makeStandardPatterns());

    return patterns;

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
    // Start with mapping-appropriate defaults, but if user changes them, use the last sampler's param
    double[] hueSpeedDefaultsBySampler = new double[] { 0.25, 0.10 };
    perlinNoisePattern.hueSpeed.addListener(param -> {
      if (colorSampler.samplerSelector.getObject() == gradientColorSource) {
        hueSpeedDefaultsBySampler[0] = param.getValue();
      } else if (colorSampler.samplerSelector.getObject() == patternColorSource) {
        hueSpeedDefaultsBySampler[1] = param.getValue();
      }
    });
    colorSampler.samplerSelector.addListener(param -> {
      if (perlinNoisePattern.getChannel().getActivePattern() != perlinNoisePattern) {
        return;
      }

      DiscreteParameter parameter = (DiscreteParameter) param;
      if (parameter.getObject() == gradientColorSource) {
        perlinNoisePattern.hueSpeed.setValue(hueSpeedDefaultsBySampler[0]);
      } else if (parameter.getObject() == patternColorSource) {
        perlinNoisePattern.hueSpeed.setValue(hueSpeedDefaultsBySampler[1]);
      }
    });
    perlinNoisePattern.hueSpeed.setValue(0.25);


    // Normal patterns
    // --------------------
    return Arrays.asList(
        new CombJellyPattern(lx, lx.palette, model.allTriangles),
        perlinNoisePattern,
        new PerlinBreathing(lx, p, model.getPoints(), colorSampler,
            new LXVector(0, -1, 0), // mapping seems reversed... 'up' is y:-1
            new LXVector(0, 1, 0),
            PerlinBreathing.BreathEasingSupplier.EXP_OUT_CUBIC_INOUT
        ),
        fixtureColorBarsPattern,
        new RainbowZPattern(lx),
        new PalettePainterPattern(lx, lx.palette), // feed it LX default palette (controlled by Studio's palette UI)
        new FFTBandPattern(lx, model, starCatFFT),
        new BlinkyDomeFixtureSelectorPattern(lx, model)
    );
  }


}
