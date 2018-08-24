package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.color.GenericColorMappingSourceClan;
import com.github.starcats.blinkydome.configuration.dlo.DloRaspiGpio;
import com.github.starcats.blinkydome.model.Icosastar;
import com.github.starcats.blinkydome.model.util.ConnectedVectorStripModel;
import com.github.starcats.blinkydome.modulator.MinimBeatTriggers;
import com.github.starcats.blinkydome.pattern.FixtureColorBarsPattern;
import com.github.starcats.blinkydome.pattern.PalettePainterPattern;
import com.github.starcats.blinkydome.pattern.PerlinBreathing;
import com.github.starcats.blinkydome.pattern.PerlinNoisePattern;
import com.github.starcats.blinkydome.pattern.RainbowZPattern;
import com.github.starcats.blinkydome.pattern.mask.*;
import com.github.starcats.blinkydome.util.StarCatFFT;
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
import heronarts.lx.parameter.LXCompoundModulation;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;
import processing.core.PVector;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Standard config for {@link com.github.starcats.blinkydome.model.Icosastar} model
 */
public class IcosastarConfig extends AbstractStarcatsLxConfig<Icosastar> {

  private static final String CONFIG_BEATS = "/etc/starcats/icosastar/icosastar-2018-beats.lxp";
  private static final String CONFIG_CALM = "/etc/starcats/icosastar/icosastar-2018-calm.lxp";


  // Components
  private StarCatFFT starCatFFT;
  protected GenericColorMappingSourceClan colorSampler;


  // GPIO Brightness:

  private static final float MAX_BRIGHTNESS = 0.75f;  // power supply breakers trip above this

  // 2 brightness modifiers: DIP switches to set max brightness, then push button for quick toggling
  private final FloatContainer quickToggleMultiplier = new FloatContainer(1f);
  private final FloatContainer dipMultiplier = new FloatContainer(1f);


  // Modulators
  protected MinimBeatTriggers minimBeatTriggers;
  private BandGate kickModulator;

  public IcosastarConfig(PApplet p) {
    super(p);
  }

  @Override
  protected Icosastar makeModel() {
    return Icosastar.makeModel();
  }

  private void setOverallBrightness(LX lx) {
    lx.engine.output.brightness.setValue(MAX_BRIGHTNESS * dipMultiplier.get() * quickToggleMultiplier.get());
  }

  @Override
  protected List<LXOutput> constructOutputs(LX lx) {
    return Arrays.asList(
        new FadecandyOutput(lx, "localhost", 7890)
    );
  }

  @Override
  public Optional<String> getLxProjectToLoad() {
    if (DloRaspiGpio.isActive()) {
      return Optional.of(DloRaspiGpio.isToggle() ? CONFIG_BEATS : CONFIG_CALM);
    }

    return Optional.of(CONFIG_BEATS);
  }

  @Override
  protected void initComponents(PApplet p, LX lx, Icosastar model) {
    starCatFFT = CommonScLxConfigUtils.Components.makeStarcatFft(lx);
    colorSampler = CommonScLxConfigUtils.Components.makeColorSampler(p, lx, starCatFFT);

    LXModulationEngine.LXModulatorFactory<MinimBeatTriggers> minimFactory =
            (LX lx2, String label) -> new MinimBeatTriggers(lx2, starCatFFT);
    lx.engine.modulation.getModulatorFactoryRegistry().register(MinimBeatTriggers.class, minimFactory);


    this.setOverallBrightness(lx);


    // Raspi GPIO
    // =============================================
    DloRaspiGpio.init(lx.engine.output);


    // GPIO Brightness:
    // ------------------
    // 2 methods: DIP switches to set max brightness, then push button for quick toggling
    // Dip Switches: Adjust brightness, reset quickToggle
    DloRaspiGpio.DipSwitchListener defaultDipSwitchListener = (float dipValuef) -> {
      quickToggleMultiplier.set(1f);
      dipMultiplier.set(dipValuef);
      setOverallBrightness(lx);
//      System.out.println("Setting brightness to " + MAX_BRIGHTNESS * dipValuef + " (input: " + dipValuef + ")");
    };
    DloRaspiGpio.addDipSwitchListener(defaultDipSwitchListener);

    // Black moment: Decrease brightness by 25%
    GpioPinListenerDigital quickBrightnessListener = (GpioPinDigitalStateChangeEvent event) -> {
      if (!event.getState().isHigh()) {
        return; // release
      }

      if (quickToggleMultiplier.get() > 0) {
        quickToggleMultiplier.set(quickToggleMultiplier.get() - 0.25f);
      } else {
        quickToggleMultiplier.set(1);
      }

      setOverallBrightness(lx);
    };


    // Toggle switch: Load the BEATS or CALM config
    // ------------------
    GpioPinListenerDigital toggleSwitchListener = (GpioPinDigitalStateChangeEvent event) -> {
      loadConfig(event.getState().isHigh());
    };


    // Add listeners only if GPIO enabled (otherwise NPE's)
    if (DloRaspiGpio.isActive()) {
      defaultDipSwitchListener.onDipSwitchChange(DloRaspiGpio.getDipValuef());

      DloRaspiGpio.yellowMoment.addListener(quickBrightnessListener);

      DloRaspiGpio.toggle.addListener(toggleSwitchListener);
      // Don't do the toggleSwitchListener behavior -- that's effectively done in getLxProjectToLoad()
    }
  }

  @Override
  protected List<LXModulator> constructModulators(PApplet p, LX lx, Icosastar model) {
    // I don't think we need this one given the instantiation below?
//    minimBeatTriggers = lx.engine.modulation.addModulator(MinimBeatTriggers.class, "minim triggers");
    minimBeatTriggers = new MinimBeatTriggers(lx, starCatFFT);
    kickModulator = CommonScLxConfigUtils.Modulators.makeKickModulator(lx);

    return Arrays.asList(
        minimBeatTriggers,
        kickModulator
    );
  }

  @Override
  protected int getNumChannels() {
    return 3;
  }

  @Override
  protected void configChannel(int channelNum, LXChannel channel) {
    LXPattern[] patterns;

    // CHANNEL 1: Colorizing patterns
    if (channelNum == 1) {
      channel.label.setValue("Colorizer");
      patterns = makeColorizerPatterns(lx);


    // CHANNEL 2: PATTERN MASKS
    } else if (channelNum == 2) {
      channel.label.setValue("Pattern Masks");
      channel.blendMode.setValue(1); // Multiply
      patterns = makeMasks();

      // Modulate visibility of this mask down on kick drums
      channel.fader.setValue(1.0);
      LXCompoundModulation chVisibilityMod = new LXCompoundModulation(minimBeatTriggers, channel.fader);
      chVisibilityMod.range.setValue(-1);
      lx.engine.modulation.addModulation(chVisibilityMod);


    // CHANNEL 3: Beat Masks
    } else if (channelNum == 3) {
      channel.label.setValue("Beat Masks");
      channel.fader.setValue(0);
      channel.blendMode.setValue(1); // Multiply
      patterns = makeMasks();

      // Modulate visibility of this mask down on kick drums
      channel.fader.setValue(1.0);
      LXCompoundModulation chVisibilityMod = new LXCompoundModulation(minimBeatTriggers, channel.fader);
      chVisibilityMod.range.setValue(1);
      lx.engine.modulation.addModulation(chVisibilityMod);

    } else {
      // unexpected -- maybe a config will make a new one
      patterns = new LXPattern[0];
    }


    channel.setPatterns(patterns);

    // TODO: config against headless raspi
    // RaspiPerlinNoiseDefaults.applyPresetsIfRaspiGpio(perlinNoise);
  }

  private <T extends LXPattern> T quickBuild(LX.LXPatternFactory<T> factory) {
    return factory.build(lx, null, null);
  }

  private LXPattern[] makeColorizerPatterns(LX lx) {
    // PerlinNoisePattern: apply defaults appropriate for Icosastar mapping size
    // --------------------
    LX.LXPatternFactory<PerlinNoisePattern> perlinNoisePatternFactory = (lx2, ch, l) ->
            new PerlinNoisePattern(lx, p, starCatFFT.beat, colorSampler);
    lx.registerPatternFactory(PerlinNoisePattern.class, perlinNoisePatternFactory);
    PerlinNoisePattern perlinNoisePattern = quickBuild(perlinNoisePatternFactory);


    // PerlinBreathing
    // --------------------
    LX.LXPatternFactory<PerlinBreathing> perlinBreathingFactory = (lx2, ch, l) -> {
      PerlinBreathing pattern = new PerlinBreathing(lx, p, model.getPoints(), colorSampler,
              new LXVector(0, 0, -1),
              new LXVector(0, 0, 1),
              PerlinBreathing.BreathEasingSupplier.EXP_OUT_CUBIC_INOUT
      );
      pattern.initModulators();
      pattern.perlinNoiseFieldZoom.setValue(0.02);
      pattern.getSpeedModulationRange().setValue(0.20);
      return pattern;
    };
    lx.registerPatternFactory(PerlinBreathing.class, perlinBreathingFactory);
    PerlinBreathing perlinBreathing = quickBuild(perlinBreathingFactory);

    return new LXPattern[] {
            perlinNoisePattern,
            perlinBreathing,
            new RainbowZPattern(lx),
    };
  }

  protected LX.LXPatternFactory<Mask_RollingBouncingDisc> getRollingBouncingDiscFactory() {
    return (lx2, ch, l) -> new Mask_RollingBouncingDisc(
            lx2,
            new LXVector(model.cx, model.cy, model.zMax),
            new LXVector(0, 0, model.zMin - model.zMax),
            new LXVector(1, 0, 0)
    );
  }

  private LXPattern[] makeMasks() {
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


    LX.LXPatternFactory<TMask_Starlight> starlightFactory = (lx2, ch, l) -> new TMask_Starlight(p, lx2, 2);
    lx.registerPatternFactory(TMask_Starlight.class, starlightFactory);


    Mask_BrightnessBeatBoost mask_bbb = new Mask_BrightnessBeatBoost(lx);
    minimBeatTriggers.triggerWithKick(mask_bbb.trigger);


    LX.LXPatternFactory<Mask_RandomFixtureSelector> rfsFactory =
            (lx2, ch, l) -> new Mask_RandomFixtureSelector(lx2, model.allSegments);
    lx.registerPatternFactory(Mask_RandomFixtureSelector.class, rfsFactory);
    Mask_RandomFixtureSelector randomFixtureMask = quickBuild(rfsFactory);
    minimBeatTriggers.triggerWithKick(randomFixtureMask);


    Mask_WipePattern wipeMask = new Mask_WipePattern(lx);
    minimBeatTriggers.triggerWithKick(wipeMask);
    wipeMask.widthPx.setValue(20);


    // TODO: Will this work on the icosastar? Need fixtures to operate on
//    LX.LXPatternFactory<Mask_FixtureDottedLine> fdlFactory =
//            (lx2, ch, l) -> new Mask_FixtureDottedLine(lx2, model.allTriangles);
//    lx.registerPatternFactory(Mask_FixtureDottedLine.class, fdlFactory);


    LX.LXPatternFactory<Mask_AngleSweep> angleSweepFactory =
            (lx2, ch, l) -> new Mask_AngleSweep(lx2, new PVector(1, 0, 0), model.allSegments, lx.tempo);
    lx.registerPatternFactory(Mask_AngleSweep.class, angleSweepFactory);

    LX.LXPatternFactory<Mask_AllWhite> allWhiteFactory =
            (lx2, ch, l) -> new Mask_AllWhite(lx);
    lx.registerPatternFactory(Mask_AllWhite.class, allWhiteFactory);


    // TODO: Legacy patterns
    // ================================
//    FixtureColorBarsPattern fixtureColorBarsPattern = new FixtureColorBarsPattern(lx, model.allSegments, colorSampler)
//            .initModulations(kickModulator);
    // ================================


    List<LXPattern> patterns = new ArrayList<>();
    patterns.add(mask_disc);
    patterns.add(mask_perlin);
//    patterns.add(quickBuild(perlinLineTranslatorFactory).initModulations());
    patterns.add(quickBuild(starlightFactory));
    patterns.add(mask_bbb);
//    patterns.add(quickBuild(fdlFactory));
    patterns.add(quickBuild(angleSweepFactory));
    patterns.add(randomFixtureMask);
    patterns.add(wipeMask);
    patterns.add(new Mask_XyzFilter(lx));
    patterns.add(quickBuild(allWhiteFactory));

    // patterns.addAll(makeStandardPatterns());

    return patterns.toArray(new LXPattern[0]);

  }

  protected void loadConfig(boolean loadBeats) {
    String lxProjectToLoad = loadBeats ? CONFIG_BEATS : CONFIG_CALM;


    System.out.println("Loading preset: " + lxProjectToLoad);


    File file = p.saveFile(lxProjectToLoad);
    if (!file.exists()) {
      System.out.println("Presets file '" + lxProjectToLoad + "' not found! Not loading presets!");
    } else {

      lx.engine.addTask(new Runnable() {
        public void run() {
          lx.openProject(file);
          setOverallBrightness(lx);
        }
      });

      System.out.println("Successfully loaded presets: " + lxProjectToLoad);
    }
  }

  /** Small float container-class to get around lambdas-need-to-reference-finals */
  private final class FloatContainer {
    private float value;

    public FloatContainer(float initialValue) {
      this.value = initialValue;
    }

    public float get() {
      return this.value;
    }

    public void set(float newValue) {
      this.value = newValue;
    }
  }
}
