package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.color.ImageColorSampler;
import com.github.starcats.blinkydome.color.ImageColorSamplerGroup;
import com.github.starcats.blinkydome.model.blinky_dome.BlinkyDomeFactory;
import com.github.starcats.blinkydome.model.blinky_dome.BlinkyModel;
import com.github.starcats.blinkydome.modulator.MinimBeatTriggers;
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
import com.github.starcats.blinkydome.pattern.mask.Mask_PerlinLineTranslator;
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
 * Headless configuration for the {@link BlinkyModel} model
 */
public class BlinkyDomeConfig extends AbstractStarcatsLxConfig<BlinkyModel> {

  // Components
  private StarCatFFT starCatFFT;
  protected ImageColorSamplerGroup colorSampler;
  protected ImageColorSampler gradientColorSource;
  protected ImageColorSampler patternColorSource;

  // Modulators
  protected MinimBeatTriggers minimBeatTriggers;
  private BandGate kickModulator;

  public BlinkyDomeConfig(PApplet p) {
    super(p);
  }

  @Override
  protected BlinkyModel makeModel() {
    return BlinkyDomeFactory.makeModel(p);
//    return TestHarnessFactory.makeModel();
//    return Meowloween.makeModel();
  }

  @Override
  protected void initComponents(PApplet p, LX lx, BlinkyModel model) {
    starCatFFT = CommonScLxConfigUtils.Components.makeStarcatFft(lx);

    gradientColorSource = new ImageColorSampler(p, lx, "gradients.png");
    patternColorSource = new ImageColorSampler(p, lx, "patterns.png");
    colorSampler = new ImageColorSamplerGroup(lx, "color samplers", new ImageColorSampler[] {
        gradientColorSource,
        patternColorSource
    });
  }

  @Override
  protected List<LXModulator> constructModulators(PApplet p, LX lx, BlinkyModel model) {
    minimBeatTriggers = new MinimBeatTriggers(lx, starCatFFT);
    kickModulator = CommonScLxConfigUtils.Modulators.makeKickModulator(lx);

    return Arrays.asList(
        minimBeatTriggers,
        kickModulator
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
      channel.goIndex(4);
    }
  }

  private List<LXPattern> makeMasks() {
    // Any non-standard LX constructors need their own factory registered


    LX.LXPatternFactory<Mask_RollingBouncingDisc> rbdFactory = (lx2) -> new Mask_RollingBouncingDisc(
        lx2,
        new LXVector(model.cx, model.yMin, model.cz),
        new LXVector(0, model.yMax - model.yMin, 0),
        new LXVector(1, 0, 0)
    );
    lx.patternFactoryRegistry.register(Mask_RollingBouncingDisc.class, rbdFactory);
    Mask_RollingBouncingDisc mask_disc = rbdFactory.build(lx)
        .addDemoBounce();
    mask_disc.discThicknessRad.setValue(0.15);


    LX.LXPatternFactory<Mask_Perlin> perlinFactory = (lx2) -> new Mask_Perlin(lx2, p);
    lx.patternFactoryRegistry.register(Mask_Perlin.class, perlinFactory);
    Mask_Perlin mask_perlin = perlinFactory.build(lx);
    mask_perlin.speed.setValue(0.02);
    mask_perlin.zoom.setValue(0.2);
    // Bias noise to be mostly going up/down
    mask_perlin.perlinNoise.xPosBias.setValue(0.2);
    mask_perlin.perlinNoise.xNegBias.setValue(0.2);
    mask_perlin.perlinNoise.zPosBias.setValue(0.2);
    mask_perlin.perlinNoise.zNegBias.setValue(0.2);


    Mask_BrightnessBeatBoost mask_bbb = new Mask_BrightnessBeatBoost(lx);
    minimBeatTriggers.triggerWithKick(mask_bbb.trigger);


    LX.LXPatternFactory<Mask_RandomFixtureSelector> rfsFactory =
        (lx2) -> new Mask_RandomFixtureSelector(lx2, model.allTriangles);
    lx.patternFactoryRegistry.register(Mask_RandomFixtureSelector.class, rfsFactory);
    Mask_RandomFixtureSelector randomFixtureMask = rfsFactory.build(lx);
    minimBeatTriggers.triggerWithKick(randomFixtureMask);


    Mask_WipePattern wipeMask = new Mask_WipePattern(lx);
    minimBeatTriggers.triggerWithKick(wipeMask);
    wipeMask.widthPx.setValue(20);


    LX.LXPatternFactory<Mask_FixtureDottedLine> fdlFactory =
        (lx2) -> new Mask_FixtureDottedLine(lx2, model.allTriangles);
    lx.patternFactoryRegistry.register(Mask_FixtureDottedLine.class, fdlFactory);


    LX.LXPatternFactory<Mask_AngleSweep> angleSweepFactory =
        (lx2) -> new Mask_AngleSweep(lx2, new PVector(1, 0, 0), model.allTriangles, lx.tempo);
    lx.patternFactoryRegistry.register(Mask_AngleSweep.class, angleSweepFactory);


    List<LXPattern> patterns = new ArrayList<>();
    patterns.add(mask_disc);
    patterns.add(mask_perlin);
    patterns.add(new Mask_PerlinLineTranslator(lx, p, model.allTriangles).initModulations());
    patterns.add(mask_bbb);
    patterns.add(fdlFactory.build(lx));
    patterns.add(angleSweepFactory.build(lx));
    patterns.add(randomFixtureMask);
    patterns.add(wipeMask);
    patterns.add(new Mask_XyzFilter(lx));

    // patterns.addAll(makeStandardPatterns());

    return patterns;

  }

  /** Creates standard set of BlinkyModel patterns */
  private List<LXPattern> makeStandardPatterns() {
    // Need to build and register factories for any patterns that don't have standard LX constructor

    // FixtureColorBarsPattern: Wire it up to engine-wide modulation sources
    // --------------------
    LX.LXPatternFactory<FixtureColorBarsPattern> fcbpFactory =
        (lx2) -> new FixtureColorBarsPattern(lx2, model.allTriangles, colorSampler)
            .initModulations(() -> minimBeatTriggers.kickTrigger);
    lx.patternFactoryRegistry.register(FixtureColorBarsPattern.class, fcbpFactory);
    FixtureColorBarsPattern fcbp = fcbpFactory.build(lx);


    // PerlinNoisePattern: apply defaults appropriate for BlinkyModel mapping size
    // --------------------
    LX.LXPatternFactory<PerlinNoisePattern> perlinNoisePatternFactory = (lx2) -> {
      PerlinNoisePattern perlinNoisePattern = new PerlinNoisePattern(lx2, p, starCatFFT.beat, colorSampler);

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

      return perlinNoisePattern;
    };
    lx.patternFactoryRegistry.register(PerlinNoisePattern.class, perlinNoisePatternFactory);


    // PerlinBreathingPattern
    // --------------------
    LX.LXPatternFactory<PerlinBreathing> perlinBreathingFactory =
        (lx2) -> new PerlinBreathing(lx2, p, model.getPoints(), colorSampler,
            new LXVector(0, -1, 0), // mapping seems reversed... 'up' is y:-1
            new LXVector(0, 1, 0),
            PerlinBreathing.BreathEasingSupplier.EXP_OUT_CUBIC_INOUT
        );
    lx.patternFactoryRegistry.register(PerlinBreathing.class, perlinBreathingFactory);


    // FFTBandPattern
    // ------------------
    LX.LXPatternFactory<FFTBandPattern> fftBandPatternFactory =
        (lx2) -> new FFTBandPattern(lx2, model, starCatFFT);
    lx.patternFactoryRegistry.register(FFTBandPattern.class, fftBandPatternFactory);


    // BlinkyDomeFixtureSelectorPattern
    // ---------------------
    LX.LXPatternFactory<BlinkyDomeFixtureSelectorPattern> bdfspFactory =
        (lx2) -> new BlinkyDomeFixtureSelectorPattern(lx2, model);
    lx.patternFactoryRegistry.register(BlinkyDomeFixtureSelectorPattern.class, bdfspFactory);


    // Normal patterns
    // --------------------
    return Arrays.asList(
        perlinNoisePatternFactory.build(lx),
        perlinBreathingFactory.build(lx),
        fcbp,
        new RainbowZPattern(lx),
        new PalettePainterPattern(lx), // by default uses Studio's palette UI)
        fftBandPatternFactory.build(lx),
        bdfspFactory.build(lx)
    );
  }


}
