package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.color.GenericColorMappingSourceClan;
import com.github.starcats.blinkydome.model.Icosastar;
import com.github.starcats.blinkydome.model.util.ConnectedVectorStripModel;
import com.github.starcats.blinkydome.pattern.FixtureColorBarsPattern;
import com.github.starcats.blinkydome.pattern.PalettePainterPattern;
import com.github.starcats.blinkydome.pattern.PerlinBreathing;
import com.github.starcats.blinkydome.pattern.PerlinNoisePattern;
import com.github.starcats.blinkydome.pattern.RainbowZPattern;
import com.github.starcats.blinkydome.pattern.mask.Mask_WipePattern;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Standard config for {@link com.github.starcats.blinkydome.model.Icosastar} model
 */
public class IcosastarConfig extends AbstractStarcatsLxConfig<Icosastar> {

  // Components
  private StarCatFFT starCatFFT;
  protected GenericColorMappingSourceClan colorSampler;

  // Modulators
  private BandGate kickModulator;

  public IcosastarConfig(PApplet p) {
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
  protected void initComponents(PApplet p, LX lx, Icosastar model) {
    starCatFFT = CommonScLxConfigUtils.Components.makeStarcatFft(lx);
    colorSampler = CommonScLxConfigUtils.Components.makeColorSampler(p, lx, starCatFFT);
  }

  @Override
  protected List<LXModulator> constructModulators(PApplet p, LX lx, Icosastar model) {
    kickModulator = CommonScLxConfigUtils.Modulators.makeKickModulator(lx);

    return Arrays.asList(
        kickModulator
    );
  }

  @Override
  protected void configChannel(int channelNum, LXChannel channel) {

    // FixtureColorBarsPattern: Wire it up to engine-wide modulation sources
    // --------------------
    List<ConnectedVectorStripModel> allSpokes = new ArrayList<>();
    allSpokes.addAll(model.innerSpokeLeds);
    allSpokes.addAll(model.outerSpokeLeds);
    allSpokes.addAll(model.ring1Leds);
    FixtureColorBarsPattern fixtureColorBarsPattern = new FixtureColorBarsPattern(lx, allSpokes, colorSampler)
        .initModulations(kickModulator);

    // PerlinNoisePattern: apply defaults appropriate for Icosastar mapping size
    // --------------------
    PerlinNoisePattern perlinNoisePattern = new PerlinNoisePattern(lx, p, starCatFFT.beat, colorSampler);


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
        perlinBreathing,
        perlinNoisePattern,
        // TODO: bring in FixtureTracerPattern
        new RainbowZPattern(lx),
        new PalettePainterPattern(lx, lx.palette), // feed it LX default palette (controlled by Studio's palette UI)
        new Mask_WipePattern(lx),
        fixtureColorBarsPattern
    });

    // TODO: config against headless raspi
    // RaspiPerlinNoiseDefaults.applyPresetsIfRaspiGpio(perlinNoise);
  }
}
