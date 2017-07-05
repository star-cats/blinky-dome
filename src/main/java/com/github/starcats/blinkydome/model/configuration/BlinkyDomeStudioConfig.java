package com.github.starcats.blinkydome.model.configuration;

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
import com.github.starcats.blinkydome.ui.UIGradientPicker;
import com.github.starcats.blinkydome.util.AudioDetector;
import com.github.starcats.blinkydome.util.LXTriggerLinkModulation;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
import heronarts.lx.audio.BandGate;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.output.FadecandyOutput;
import heronarts.lx.output.LXOutput;
import heronarts.lx.parameter.LXCompoundModulation;
import heronarts.lx.parameter.LXTriggerModulation;
import heronarts.p3lx.LXStudio;
import heronarts.p3lx.ui.UI2dScrollContext;
import processing.core.PApplet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * LXStudio-based (GUI, not headless) configuration for the {@link BlinkyDome} model
 */
public class BlinkyDomeStudioConfig extends StarcatsLxModelConfig<BlinkyDome> {

  /** minim-based FFT.  Offers better beat-detection algorithms than LX built-in stuff */
  private StarCatFFT starCatFFT;


  // Color sources
  // ----------------
  /** Grouping of color sources */
  private ImageColorSamplerClan colorSamplers;


  // Modulators
  // ----------------
  /** Beat-detect for kick drums */
  private BandGate kickModulator;

  /** Default time-varying modulator for {@link com.github.starcats.blinkydome.color.ColorMappingSource}'s */
  private VariableLFO colorMappingSourceLfo;


  public BlinkyDomeStudioConfig(PApplet p) {
    super(p);
  }

  @Override
  protected BlinkyDome makeModel() {
    return BlinkyDome.makeModel(p);
  }

  @Override
  protected List<LXOutput> constructOutputs() {
    return Collections.singletonList(
        new FadecandyOutput(lx, "localhost", 7890)
        // TODO: pixelpusher, etc.
    );
  }

  @Override
  protected void initComponents() {
    // FFT
    this.starCatFFT = new StarCatFFT();
    AudioDetector.init(starCatFFT.in.mix);
    lx.engine.addLoopTask(deltaMs -> {
      starCatFFT.forward();
      AudioDetector.LINE_IN.tick(deltaMs, false);
    });


    // Color Samplers
    ImageColorSampler gradientColorSource = new ImageColorSampler(p, "gradients.png");
    ImageColorSampler patternColorSource = new ImageColorSampler(p, "patterns.png");
    this.colorSamplers = new ImageColorSamplerClan(new ImageColorSampler[] {
        gradientColorSource,
        patternColorSource
    });
  }

  @Override
  protected List<LXModulator> constructModulators() {
    colorMappingSourceLfo = new VariableLFO("Color Mapping LFO");
    colorMappingSourceLfo.running.setValue(true);
    colorMappingSourceLfo.period.setValue(3000);

    kickModulator = new BandGate("Kick beat detect", lx);
    kickModulator.running.setValue(true);
    kickModulator.gain.setValue(30); //dB

    return Arrays.asList(
        colorMappingSourceLfo,
        kickModulator
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
    FixtureColorBarsPattern fixtureColorBarsPattern = new FixtureColorBarsPattern(
        lx, model.allTriangles, colorSamplers
    );

    LXCompoundModulation fcbpAudioModulation = new LXCompoundModulation(
        lx.engine.audio.meter, fixtureColorBarsPattern.visibleRange
    );
    fixtureColorBarsPattern.visibleRange.setValue(0.25);
    fcbpAudioModulation.range.setValue(0.75);
    lx.engine.modulation.addModulation(fcbpAudioModulation);

    LXCompoundModulation fcbpColorModulation = new LXCompoundModulation(
        colorMappingSourceLfo, fixtureColorBarsPattern.colorSourceI
    );
    fixtureColorBarsPattern.colorSourceI.setValue(0.0);
    fcbpColorModulation.range.setValue(1.0);
    lx.engine.modulation.addModulation(fcbpColorModulation);

    LXTriggerModulation fcbpNewBarModulation = new LXTriggerLinkModulation(
        kickModulator, fixtureColorBarsPattern.getTriggerTarget()
    );
    lx.engine.modulation.addTrigger(fcbpNewBarModulation);


    // Normal patterns
    // --------------------
    return Arrays.asList(
        new PerlinNoisePattern(lx, p, starCatFFT.beat, colorSamplers),
        new FFTBandPattern(lx, starCatFFT),
        new RainbowZPattern(lx),
        new PalettePainterPattern(lx, lx.palette), // feed it LX default palette (controlled by Studio's palette UI)
        new BlinkyDomeFixtureSelectorPattern(lx),
        fixtureColorBarsPattern
    );
  }

  @Override
  public void onUIReady(LXStudio lx, LXStudio.UI ui) {
    // Add custom gradient selector
    UI2dScrollContext container = ui.leftPane.global;
    UIGradientPicker uiGradientPicker = new UIGradientPicker(
        ui, colorSamplers, 0, 0, container.getContentWidth());
    uiGradientPicker.addToContainer(container);


    // Enable audio support
    lx.engine.audio.enabled.setValue(true);
  }
}
