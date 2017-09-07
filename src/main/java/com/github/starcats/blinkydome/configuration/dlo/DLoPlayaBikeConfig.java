package com.github.starcats.blinkydome.configuration.dlo;

import com.github.starcats.blinkydome.color.GenericColorMappingSourceClan;
import com.github.starcats.blinkydome.configuration.AbstractStarcatsLxConfig;
import com.github.starcats.blinkydome.configuration.CommonScLxConfigUtils;
import com.github.starcats.blinkydome.model.dlo.DLoPlayaBikeModel;
import com.github.starcats.blinkydome.modulator.MinimBeatTriggers;
import com.github.starcats.blinkydome.pattern.PerlinNoisePattern;
import com.github.starcats.blinkydome.pattern.RainbowZPattern;
import com.github.starcats.blinkydome.pattern.mask.*;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LX;
import heronarts.lx.LXChannel;
import heronarts.lx.LXModulationEngine;
import heronarts.lx.LXPattern;
import heronarts.lx.audio.BandGate;
import heronarts.lx.model.LXFixture;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.output.FadecandyOutput;
import heronarts.lx.output.LXOutput;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Standard config for {@link DLoPlayaBikeModel} model
 */

public class DLoPlayaBikeConfig extends AbstractStarcatsLxConfig<DLoPlayaBikeModel> {

  // Components
  private StarCatFFT starCatFFT;
  protected GenericColorMappingSourceClan colorSampler;

  // Modulators
  protected MinimBeatTriggers minimBeatTriggers;
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

    lx.engine.output.brightness.setValue(0.7);

    return Arrays.asList(
        new FadecandyOutput(lx, "localhost", 7890)
    );
  }

  @Override
  public Optional<String> getLxProjectToLoad() {
    return Optional.of("/etc/starcats/icosastar/dlo-playa-bike-beats.lxp");
//    return Optional.of("./dlo-playa-bike-beats.lxp");
  }

  @Override
  protected void initComponents(PApplet p, LX lx, DLoPlayaBikeModel model) {
    starCatFFT = CommonScLxConfigUtils.Components.makeStarcatFft(lx);

    // For fake beat detection
//    AudioDetector.init(null);

    colorSampler = CommonScLxConfigUtils.Components.makeColorSampler(p, lx, starCatFFT);
    colorSampler.getSourceSelect().setValue(12);

    LXModulationEngine.LXModulatorFactory<MinimBeatTriggers> minimFactory =
            (LX lx2, String label) -> new MinimBeatTriggers(lx2, starCatFFT);
    lx.engine.modulation.getModulatorFactoryRegistry().register(MinimBeatTriggers.class, minimFactory);
    minimBeatTriggers = lx.engine.modulation.addModulator(MinimBeatTriggers.class, "minim triggers");
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
    return 3;
  }

  @Override
  protected void configChannel(int channelNum, LXChannel channel) {
    if (channelNum == 1) {
      channel.label.setValue("Mask");
//      configMaskPatterns(channel);
      makeMasks(channel);

    } else if (channelNum == 2) {
      channel.label.setValue("Beat Masks");
//      configMaskPatterns(channel);
      makeMasks(channel);

    } else if (channelNum == 3) {
      channel.label.setValue("Colorizer");
      channel.fader.setValue(1);
      channel.blendMode.setValue(1); // Multiply against masks
      configColorPatterns(channel);
    }
  }


  private void makeMasks(LXChannel channel) {
    List<LXFixture> allFixtures = model.fixtures;

    // Any non-standard LX constructors need their own factory registered

    LX.LXPatternFactory<Mask_AllWhite> allWhiteFactory = (lx2, ch, l) -> new Mask_AllWhite(lx2, Arrays.asList(model.points));
    lx.registerPatternFactory(Mask_AllWhite.class, allWhiteFactory);
    Mask_AllWhite mask_allWhite = quickBuild(allWhiteFactory);

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
            (lx2, ch, l) -> new Mask_PerlinLineTranslator(lx, p, allFixtures);
    lx.registerPatternFactory(Mask_PerlinLineTranslator.class, perlinLineTranslatorFactory);


    LX.LXPatternFactory<TMask_Starlight> starlightFactory = (lx2, ch, l) -> new TMask_Starlight(p, lx2, 1);
    lx.registerPatternFactory(TMask_Starlight.class, starlightFactory);


    Mask_BrightnessBeatBoost mask_bbb = new Mask_BrightnessBeatBoost(lx);
    minimBeatTriggers.triggerWithKick(mask_bbb.trigger);


    LX.LXPatternFactory<Mask_RandomFixtureSelector> rfsFactory =
            (lx2, ch, l) -> new Mask_RandomFixtureSelector(lx2, allFixtures);
    lx.registerPatternFactory(Mask_RandomFixtureSelector.class, rfsFactory);
    Mask_RandomFixtureSelector randomFixtureMask = quickBuild(rfsFactory);
    minimBeatTriggers.triggerWithKick(randomFixtureMask);


    Mask_WipePattern wipeMask = new Mask_WipePattern(lx);
    minimBeatTriggers.triggerWithKick(wipeMask);
    wipeMask.widthPx.setValue(20);


    LX.LXPatternFactory<Mask_FixtureDottedLine> fdlFactory =
            (lx2, ch, l) -> new Mask_FixtureDottedLine(lx2, allFixtures);
    lx.registerPatternFactory(Mask_FixtureDottedLine.class, fdlFactory);


//    LX.LXPatternFactory<Mask_AngleSweep> angleSweepFactory =
//            (lx2, ch, l) -> new Mask_AngleSweep(lx2, new PVector(1, 0, 0), model.allTriangles, lx.tempo);
//    lx.registerPatternFactory(Mask_AngleSweep.class, angleSweepFactory);


//    List<LXPattern> patterns = new ArrayList<>();
//    patterns.add(mask_allWhite);
//    patterns.add(mask_disc);
//    patterns.add(mask_perlin);
//    patterns.add(quickBuild(perlinLineTranslatorFactory).initModulations());
//    patterns.add(quickBuild(starlightFactory));
//    patterns.add(mask_bbb);
//    patterns.add(quickBuild(fdlFactory));
////    patterns.add(quickBuild(angleSweepFactory));
//    patterns.add(randomFixtureMask);
//    patterns.add(wipeMask);
//    patterns.add(new Mask_XyzFilter(lx));

    LXPattern[] patterns = new LXPattern[] {
            mask_allWhite,
            mask_disc,
            mask_perlin,
            quickBuild(perlinLineTranslatorFactory).initModulations(),
            quickBuild(starlightFactory),
            mask_bbb,
            quickBuild(fdlFactory),
            randomFixtureMask,
            wipeMask
    };

    channel.setPatterns(patterns);
  }

  protected LX.LXPatternFactory<Mask_RollingBouncingDisc> getRollingBouncingDiscFactory() {
    return (lx2, ch, l) -> new Mask_RollingBouncingDisc(
            lx2,
            new LXVector(model.cx, model.yMin, model.cz),
            new LXVector(0, model.yMax - model.yMin, 0),
            new LXVector(1, 0, 0)
    );
  }

  private <T extends LXPattern> T quickBuild(LX.LXPatternFactory<T> factory) {
    return factory.build(lx, null, null);
  }

  private void configColorPatterns(LXChannel channel) {
    // PerlinNoisePattern: apply defaults appropriate for Icosastar mapping size
    // --------------------
    LX.LXPatternFactory<PerlinNoisePattern> perlinFactory =
            (lx2, ch, l) -> new PerlinNoisePattern(lx2, p, starCatFFT.beat, colorSampler);
    lx.registerPatternFactory(PerlinNoisePattern.class, perlinFactory);
    PerlinNoisePattern perlinNoisePattern = quickBuild(perlinFactory);
//    PerlinNoisePattern perlinNoisePattern = new PerlinNoisePattern(lx, p, new MockBeatDetect(), colorMappingSources);
    perlinNoisePattern.hueSpeed.setValue(0.2);

    // FixtureColorBarsPattern: Wire it up to engine-wide modulation sources
    // --------------------
//    FixtureColorBarsPattern fixtureColorBarsPattern = new FixtureColorBarsPattern(lx, model.fixtures, colorSampler)
//        .initModulations(kickModulator);


    // PerlinNoisePattern: apply defaults appropriate for BlinkyDomeModel mapping size
    // --------------------
//    PerlinBreathing perlinBreathing = new PerlinBreathing(lx, p, model.getPoints(), colorSampler,
//        new LXVector(0, 0, -1),
//        new LXVector(0, 0, 1),
//        PerlinBreathing.BreathEasingSupplier.EXP_OUT_CUBIC_INOUT
//    ).initModulators();
//    perlinBreathing.perlinNoiseFieldZoom.setValue(0.02);
//    perlinBreathing.getSpeedModulationRange().setValue(0.20);


    channel.setPatterns(new LXPattern[] {
            perlinNoisePattern,
//        perlinBreathing,
            // TODO: bring in FixtureTracerPattern
            new RainbowZPattern(lx),
//        new PalettePainterPattern(lx, lx.palette), // feed it LX default palette (controlled by Studio's palette UI)
//        fixtureColorBarsPattern
    });

    // TODO: config against headless raspi
    // RaspiPerlinNoiseDefaults.applyPresetsIfRaspiGpio(perlinNoise);
  }
}
