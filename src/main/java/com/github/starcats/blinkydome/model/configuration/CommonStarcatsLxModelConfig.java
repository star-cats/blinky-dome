package com.github.starcats.blinkydome.model.configuration;

import com.github.starcats.blinkydome.color.ImageColorSampler;
import com.github.starcats.blinkydome.color.ImageColorSamplerClan;
import com.github.starcats.blinkydome.pattern.FixtureColorBarsPattern;
import com.github.starcats.blinkydome.ui.UIGradientPicker;
import com.github.starcats.blinkydome.util.AudioDetector;
import com.github.starcats.blinkydome.util.LXTriggerLinkModulation;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LX;
import heronarts.lx.audio.BandGate;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXModel;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.parameter.LXCompoundModulation;
import heronarts.lx.parameter.LXTriggerModulation;
import heronarts.p3lx.LXStudio;
import heronarts.p3lx.ui.UI2dScrollContext;
import processing.core.PApplet;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * An AbstractStarcatsLxModelConfig that contains common components used across most starcats configurations.
 */
public abstract class CommonStarcatsLxModelConfig <M extends LXModel> extends AbstractStarcatsLxModelConfig<M> {

  /** minim-based FFT.  Offers better beat-detection algorithms than LX built-in stuff */
  protected StarCatFFT starCatFFT;


  // Color sources
  // ----------------
  /** Grouping of color sources */
  protected ImageColorSamplerClan colorSamplers;


  // Modulators
  // ----------------
  /** Beat-detect for kick drums */
  protected BandGate kickModulator;

  /** Default time-varying modulator for {@link com.github.starcats.blinkydome.color.ColorMappingSource}'s */
  protected VariableLFO colorMappingSourceLfo;


  protected CommonStarcatsLxModelConfig(PApplet p) {
    super(p);
  }

  @Override
  protected void initComponents(PApplet p, LX lx, M model) {
    // FFT
    this.starCatFFT = new StarCatFFT(lx);
    AudioDetector.init(starCatFFT.in.mix);


    // Color Samplers
    ImageColorSampler gradientColorSource = new ImageColorSampler(p, "gradients.png");
    ImageColorSampler patternColorSource = new ImageColorSampler(p, "patterns.png");
    this.colorSamplers = new ImageColorSamplerClan(new ImageColorSampler[] {
        gradientColorSource,
        patternColorSource
    });


    // and any other components the config wants
    initComponentsImpl(p, lx, model);
  }

  /**
   * Continuation of {@link AbstractStarcatsLxModelConfig#initComponents} for config impl
   */
  abstract protected void initComponentsImpl(PApplet p, LX lx, M model);

  @Override
  protected List<LXModulator> constructModulators(PApplet p, LX lx, M model) {
    colorMappingSourceLfo = new VariableLFO("Color Mapping LFO");
    colorMappingSourceLfo.running.setValue(true);
    colorMappingSourceLfo.period.setValue(3000);

    kickModulator = new BandGate("Kick beat detect", lx);
    kickModulator.running.setValue(true);
    kickModulator.gain.setValue(30); //dB

    List<LXModulator> modulators = Arrays.asList(
        colorMappingSourceLfo,
        kickModulator
    );

    modulators.addAll(constructModulatorsImpl(p, lx, model));

    return modulators;
  }

  /**
   * Continuation of {@link AbstractStarcatsLxModelConfig#constructModulators} for config impl.
   *
   * If no additional modulators, return {@link java.util.Collections#emptyList()}
   */
  abstract protected List<LXModulator> constructModulatorsImpl(PApplet p, LX lx, M model);


  @Override
  public void onUIReady(LXStudio lx, LXStudio.UI ui) {
    // Add custom gradient selector
    UI2dScrollContext container = ui.leftPane.global;
    UIGradientPicker uiGradientPicker = new UIGradientPicker(
        ui, colorSamplers, 0, 0, container.getContentWidth());
    uiGradientPicker.addToContainer(container);


    // Enable audio support
    lx.engine.audio.enabled.setValue(true);


    onUIReadyImpl(lx, ui);
  }

  /**
   * Continuation of {@link AbstractStarcatsLxModelConfig#onUIReady} for config impl
   */
  abstract protected void onUIReadyImpl(LXStudio lx, LXStudio.UI ui);


  /**
   * Wires up a {@link FixtureColorBarsPattern} with common modulations for if the config wants to use it.
   *
   * @param fixtures List of fixtures the FixtureColorBarsPattern should hit
   * @return A FixtureColorBarsPattern instance with common modulations
   */
  protected FixtureColorBarsPattern wireUpFixtureColorBarsPattern(Collection<? extends LXFixture> fixtures) {
    FixtureColorBarsPattern fixtureColorBarsPattern = new FixtureColorBarsPattern(
        lx, fixtures, colorSamplers
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

    return fixtureColorBarsPattern;
  }
}
