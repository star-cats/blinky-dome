package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.color.ColorMappingSourceFamily;
import com.github.starcats.blinkydome.color.GenericColorMappingSourceClan;
import com.github.starcats.blinkydome.color.ImageColorSampler;
import com.github.starcats.blinkydome.color.RotatingHueColorMappingSourceFamily;
import com.github.starcats.blinkydome.configuration.dlo.BlinkyDomeOdroidGpio;
import com.github.starcats.blinkydome.model.blinky_dome.BlinkyDomeFactory;
import com.github.starcats.blinkydome.model.blinky_dome.BlinkyModel;
import com.github.starcats.blinkydome.model.blinky_dome.TestHarnessFactory;
import com.github.starcats.blinkydome.modulator.MinimBeatTriggers;
import com.github.starcats.blinkydome.pattern.FixtureColorBarsPattern;
import com.github.starcats.blinkydome.pattern.PalettePainterPattern;
import com.github.starcats.blinkydome.pattern.PerlinBreathing;
import com.github.starcats.blinkydome.pattern.PerlinNoisePattern;
import com.github.starcats.blinkydome.pattern.RainbowZPattern;
import com.github.starcats.blinkydome.pattern.blinky_dome.BlinkyDomeFixtureSelectorPattern;
import com.github.starcats.blinkydome.pattern.blinky_dome.BlinkyDomeTriangleRotatorPattern;
import com.github.starcats.blinkydome.pattern.blinky_dome.FFTBandPattern;
import com.github.starcats.blinkydome.pattern.mask.*;
import com.github.starcats.blinkydome.pixelpusher.PixelPusherOutput;
import com.github.starcats.blinkydome.util.StarCatFFT;
import com.heroicrobot.dropbit.registry.DeviceRegistry;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;
import heronarts.lx.LX;
import heronarts.lx.LXChannel;
import heronarts.lx.LXModulationEngine;
import heronarts.lx.LXPattern;
import heronarts.lx.audio.BandGate;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.output.FadecandyOutput;
import heronarts.lx.output.LXOutput;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXCompoundModulation;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.LXParameterListener;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;
import processing.core.PVector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Headless configuration for the {@link BlinkyModel} model
 */
public class BlinkyDomeConfig extends AbstractStarcatsLxConfig<BlinkyModel> {

  // Components
  private StarCatFFT starCatFFT;
  protected GenericColorMappingSourceClan colorMappingSources;
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
  public Optional<String> getLxProjectToLoad() {
    return Optional.of("/etc/starcats/icosastar/blinky-dome-2018-calm.lxp");
  }

  @Override
  protected void initComponents(PApplet p, LX lx, BlinkyModel model) {
    starCatFFT = CommonScLxConfigUtils.Components.makeStarcatFft(lx);

    gradientColorSource = new ImageColorSampler(p, lx, "gradients.png");
    patternColorSource = new ImageColorSampler(p, lx, "patterns.png");
    colorMappingSources = new GenericColorMappingSourceClan(
        lx, "color mapping sources",
        new ColorMappingSourceFamily[] {
            gradientColorSource,
            patternColorSource,
            new RotatingHueColorMappingSourceFamily(lx)
        },
        starCatFFT.beat
    );

    LXModulationEngine.LXModulatorFactory<MinimBeatTriggers> minimFactory =
        (LX lx2, String label) -> new MinimBeatTriggers(lx2, starCatFFT);
    lx.engine.modulation.getModulatorFactoryRegistry().register(MinimBeatTriggers.class, minimFactory);
    minimBeatTriggers = lx.engine.modulation.addModulator(MinimBeatTriggers.class, "minim triggers");


    // ODroid GPIO
    // ================
    BlinkyDomeOdroidGpio.init();

    GpioPinListenerDigital pinPressListener = (GpioPinDigitalStateChangeEvent event) -> {
      System.out.println("Pin change! " + event.getPin().getName() + " went " + event.getState().isHigh());
    };

    if (BlinkyDomeOdroidGpio.isActive()) {
      BlinkyDomeOdroidGpio.orange.addListener(pinPressListener);
      BlinkyDomeOdroidGpio.yellow.addListener(pinPressListener);
      BlinkyDomeOdroidGpio.green.addListener(pinPressListener);
      BlinkyDomeOdroidGpio.blue.addListener(pinPressListener);
      BlinkyDomeOdroidGpio.pink.addListener(pinPressListener);
      //    BlinkyDomeOdroidGpio.brown.addListener(pinPressListener);
    }
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
//        new FadecandyOutput(lx, "localhost", 7890),
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

  protected LX.LXPatternFactory<Mask_RollingBouncingDisc> getRollingBouncingDiscFactory() {
    return (lx2, ch, l) -> new Mask_RollingBouncingDisc(
        lx2,
        new LXVector(model.cx, model.yMin, model.cz),
        new LXVector(0, model.yMax - model.yMin, 0),
        new LXVector(1, 0, 0)
    );
  }

  private List<LXPattern> makeMasks() {
    // Any non-standard LX constructors need their own factory registered

    LX.LXPatternFactory<Mask_RollingBouncingDisc> rbdFactory = getRollingBouncingDiscFactory();
    lx.registerPatternFactory(Mask_RollingBouncingDisc.class, rbdFactory);
    Mask_RollingBouncingDisc mask_disc = quickBuild(rbdFactory)
        .addDemoBounce();
    mask_disc.discThicknessRad.setValue(0.15);


    LX.LXPatternFactory<Mask_Perlin> perlinFactory = (lx2, ch, l) -> new Mask_Perlin(lx2, p);
    lx.registerPatternFactory(Mask_Perlin.class, perlinFactory);
    Mask_Perlin mask_perlin = quickBuild(perlinFactory);
    mask_perlin.speed.setValue(0.02);
    mask_perlin.zoom.setValue(0.2);
    // Bias noise to be mostly going up/down
    mask_perlin.perlinNoise.xPosBias.setValue(0.2);
    mask_perlin.perlinNoise.xNegBias.setValue(0.2);
    mask_perlin.perlinNoise.zPosBias.setValue(0.2);
    mask_perlin.perlinNoise.zNegBias.setValue(0.2);


    LX.LXPatternFactory<Mask_PerlinLineTranslator> perlinLineTranslatorFactory =
        (lx2, ch, l) -> new Mask_PerlinLineTranslator(lx, p, model.allTriangles);
    lx.registerPatternFactory(Mask_PerlinLineTranslator.class, perlinLineTranslatorFactory);


    LX.LXPatternFactory<TMask_Starlight> starlightFactory = (lx2, ch, l) -> new TMask_Starlight(p, lx2, 2);
    lx.registerPatternFactory(TMask_Starlight.class, starlightFactory);


    Mask_BrightnessBeatBoost mask_bbb = new Mask_BrightnessBeatBoost(lx);
    minimBeatTriggers.triggerWithKick(mask_bbb.trigger);


    LX.LXPatternFactory<Mask_RandomFixtureSelector> rfsFactory =
        (lx2, ch, l) -> new Mask_RandomFixtureSelector(lx2, model.allTriangles);
    lx.registerPatternFactory(Mask_RandomFixtureSelector.class, rfsFactory);
    Mask_RandomFixtureSelector randomFixtureMask = quickBuild(rfsFactory);
    minimBeatTriggers.triggerWithKick(randomFixtureMask);


    Mask_WipePattern wipeMask = new Mask_WipePattern(lx);
    minimBeatTriggers.triggerWithKick(wipeMask);
    wipeMask.widthPx.setValue(20);


    LX.LXPatternFactory<Mask_FixtureDottedLine> fdlFactory =
        (lx2, ch, l) -> new Mask_FixtureDottedLine(lx2, model.allTriangles);
    lx.registerPatternFactory(Mask_FixtureDottedLine.class, fdlFactory);


    LX.LXPatternFactory<Mask_AngleSweep> angleSweepFactory =
        (lx2, ch, l) -> new Mask_AngleSweep(lx2, new PVector(1, 0, 0), model.allTriangles, lx.tempo);
    lx.registerPatternFactory(Mask_AngleSweep.class, angleSweepFactory);

    LX.LXPatternFactory<Mask_AllWhite> allWhiteFactory =
            (lx2, ch, l) -> new Mask_AllWhite(lx);
    lx.registerPatternFactory(Mask_AllWhite.class, allWhiteFactory);


    LX.LXPatternFactory<TMask_Waves> wavesFactory =
            (lx2, ch, l) -> new TMask_Waves(lx);
    lx.registerPatternFactory(TMask_Waves.class, wavesFactory);

    LX.LXPatternFactory<TMask_Borealis> borealisFactory =
            (lx2, ch, l) -> new TMask_Borealis(lx, p);
    lx.registerPatternFactory(TMask_Borealis.class, borealisFactory);


    List<LXPattern> patterns = new ArrayList<>();
    patterns.add(mask_disc);
    patterns.add(mask_perlin);
    patterns.add(quickBuild(borealisFactory));
    patterns.add(quickBuild(wavesFactory));
    patterns.add(quickBuild(perlinLineTranslatorFactory).initModulations());
    patterns.add(quickBuild(starlightFactory));
    patterns.add(mask_bbb);
    patterns.add(quickBuild(fdlFactory));
    patterns.add(quickBuild(angleSweepFactory));
    patterns.add(randomFixtureMask);
    patterns.add(wipeMask);
    patterns.add(new Mask_XyzFilter(lx));
    patterns.add(quickBuild(allWhiteFactory));

    // patterns.addAll(makeStandardPatterns());

    return patterns;

  }

  private <T extends LXPattern> T quickBuild(LX.LXPatternFactory<T> factory) {
    return factory.build(lx, null, null);
  }

  /** Creates standard set of BlinkyModel patterns */
  private List<LXPattern> makeStandardPatterns() {
    // Need to build and register factories for any patterns that don't have standard LX constructor

    // FixtureColorBarsPattern: Wire it up to engine-wide modulation sources
    // --------------------
    LX.LXPatternFactory<FixtureColorBarsPattern> fcbpFactory =
        (lx2, channel, label) -> new FixtureColorBarsPattern(lx2, model.allTriangles, colorMappingSources);
    lx.registerPatternFactory(FixtureColorBarsPattern.class, fcbpFactory);
    FixtureColorBarsPattern fcbp = quickBuild(fcbpFactory).initModulations(() -> minimBeatTriggers.kickTrigger);


    // PerlinNoisePattern: apply defaults appropriate for BlinkyModel mapping size
    // --------------------
    LX.LXPatternFactory<PerlinNoisePattern> perlinNoisePatternFactory = (lx2, ch, l) -> {
      PerlinNoisePattern perlinNoisePattern = new PerlinNoisePattern(lx2, p, starCatFFT.beat, colorMappingSources);

      // If the color sampler changes, adjust perlin settings to be appropriate for selected color sampler family
      // Start with mapping-appropriate defaults, but if user changes them, use the last sampler's param
      double[] hueSpeedDefaultsBySampler = new double[] { 0.25, 0.10 };
      perlinNoisePattern.hueSpeed.addListener(param -> {
        if (colorMappingSources.familySelector.getObject() == gradientColorSource) {
          hueSpeedDefaultsBySampler[0] = param.getValue();
        } else if (colorMappingSources.familySelector.getObject() == patternColorSource) {
          hueSpeedDefaultsBySampler[1] = param.getValue();
        }
      });
      colorMappingSources.familySelector.addListener(new LXParameterListener() {
        @Override
        public void onParameterChanged(LXParameter param) {
          if (perlinNoisePattern.getChannel() == null) {
            // Remove stale listeners (eg if pattern was unloaded
            // TODO: When loading lx presents, this causes concurrent write exceptions.  Memory leak, but just leave it
//            colorMappingSources.familySelector.removeListener(this);
            return;
          }

          if (perlinNoisePattern.getChannel().getActivePattern() != perlinNoisePattern) {
            return;
          }

          DiscreteParameter parameter = (DiscreteParameter) param;
          if (parameter.getObject() == gradientColorSource) {
            perlinNoisePattern.hueSpeed.setValue(hueSpeedDefaultsBySampler[0]);
          } else if (parameter.getObject() == patternColorSource) {
            perlinNoisePattern.hueSpeed.setValue(hueSpeedDefaultsBySampler[1]);
          }
        }
      });

      perlinNoisePattern.hueSpeed.setValue(0.25);

      return perlinNoisePattern;
    };
    lx.registerPatternFactory(PerlinNoisePattern.class, perlinNoisePatternFactory);


    // PerlinBreathingPattern
    // --------------------
    LX.LXPatternFactory<PerlinBreathing> perlinBreathingFactory =
        (lx2, ch, l) -> new PerlinBreathing(lx2, p, model.getPoints(), colorMappingSources,
            new LXVector(0, -1, 0), // mapping seems reversed... 'up' is y:-1
            new LXVector(0, 1, 0),
            PerlinBreathing.BreathEasingSupplier.EXP_OUT_CUBIC_INOUT
        );
    lx.registerPatternFactory(PerlinBreathing.class, perlinBreathingFactory);


    // FFTBandPattern
    // ------------------
    LX.LXPatternFactory<FFTBandPattern> fftBandPatternFactory =
        (lx2, ch, l) -> new FFTBandPattern(lx2, model, starCatFFT);
    lx.registerPatternFactory(FFTBandPattern.class, fftBandPatternFactory);


    // BlinkyDomeFixtureSelectorPattern
    // ---------------------
    LX.LXPatternFactory<BlinkyDomeFixtureSelectorPattern> bdfspFactory =
        (lx2, ch, l) -> new BlinkyDomeFixtureSelectorPattern(lx2, model);
    lx.registerPatternFactory(BlinkyDomeFixtureSelectorPattern.class, bdfspFactory);


    // BlinkyDomeTriangleRotatorPattern
    // ---------------------
    LX.LXPatternFactory<BlinkyDomeTriangleRotatorPattern> bdtrpFactory =
            (lx2, ch, l) -> new BlinkyDomeTriangleRotatorPattern(lx2, model);
    lx.registerPatternFactory(BlinkyDomeTriangleRotatorPattern.class, bdtrpFactory);


    // Normal patterns
    // --------------------
    return Arrays.asList(
        quickBuild(perlinNoisePatternFactory),
        quickBuild(perlinBreathingFactory).initModulators(),
        fcbp,
        new RainbowZPattern(lx),
        new PalettePainterPattern(lx), // by default uses Studio's palette UI)
        quickBuild(fftBandPatternFactory),
        quickBuild(bdfspFactory)
    );
  }


}
