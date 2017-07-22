package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.color.ImageColorSamplerClan;
import com.github.starcats.blinkydome.model.FibonocciPetalsModel;
import com.github.starcats.blinkydome.pattern.PerlinBreathing;
import com.github.starcats.blinkydome.pattern.PerlinNoisePattern;
import com.github.starcats.blinkydome.pattern.RainbowZPattern;
import com.github.starcats.blinkydome.pattern.fibonocci_petals.Mask_RandomPetalsSelector;
import com.github.starcats.blinkydome.pattern.fibonocci_petals.PerlinPetalsPattern;
import com.github.starcats.blinkydome.pattern.mask.Mask_RandomFixtureSelector;
import com.github.starcats.blinkydome.pattern.mask.Mask_WipePattern;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LX;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
import heronarts.lx.audio.BandGate;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.output.FadecandyOutput;
import heronarts.lx.output.LXOutput;
import heronarts.lx.parameter.LXTriggerModulation;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Standard config for {@link com.github.starcats.blinkydome.model.FibonocciPetalsModel} model
 */
public class FibonocciPetalsConfig extends AbstractStarcatsLxConfig<FibonocciPetalsModel> {

  // Components
  private StarCatFFT starCatFFT;
  protected ImageColorSamplerClan colorSampler;


  // Modulators
  private BandGate kickModulator;


  public FibonocciPetalsConfig(PApplet p) {
    super(p);
  }

  @Override
  protected FibonocciPetalsModel makeModel() {
    return FibonocciPetalsModel.makeModel();
  }

  @Override
  protected List<LXOutput> constructOutputs(LX lx) {
    return Arrays.asList(
        new FadecandyOutput(lx, "localhost", 7890)
    );
  }

  @Override
  protected void initComponents(PApplet p, LX lx, FibonocciPetalsModel model) {
    starCatFFT = CommonScLxConfigUtils.Components.makeStarcatFft(lx);
    colorSampler = CommonScLxConfigUtils.Components.makeColorSampler(p);
  }

  @Override
  protected List<LXModulator> constructModulators(PApplet p, LX lx, FibonocciPetalsModel model) {
    kickModulator = CommonScLxConfigUtils.Modulators.makeKickModulator(lx);
    return Collections.singletonList(
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
      patterns = new ArrayList<>(Arrays.asList(
          new PerlinNoisePattern(lx, p, starCatFFT.beat, colorSampler),
          new PerlinPetalsPattern(lx, p, starCatFFT, colorSampler),
          new PerlinBreathing(lx, p, model.getPoints(), colorSampler,
              new LXVector(0, -1, 0),
              new LXVector(0, 1, 0)
          ),
          new RainbowZPattern(lx)
      ));
      patterns.addAll(getGuiPatterns());

    // Channel 2
    } else {
      channel.fader.setValue(1.0);
      channel.blendMode.setValue(1); // Multiply


      Mask_RandomFixtureSelector randomFixtureMask = new Mask_RandomPetalsSelector(lx, model);
      LXTriggerModulation rfmBeatTrigger = new LXTriggerModulation(
          kickModulator.getTriggerSource(), randomFixtureMask.selectRandomFixturesTrigger
      );
      lx.engine.modulation.addTrigger(rfmBeatTrigger);

      Mask_WipePattern wipeMask = new Mask_WipePattern(lx);
      LXTriggerModulation wipeBeatTrigger = new LXTriggerModulation(
          kickModulator.getTriggerSource(), wipeMask.wipeTrigger
      );
      lx.engine.modulation.addTrigger(wipeBeatTrigger);

      patterns = Arrays.asList(
          randomFixtureMask,
          wipeMask
      );
    }

    channel.setPatterns(patterns.toArray(new LXPattern[patterns.size()]));
  }

  protected List<LXPattern> getGuiPatterns() {
    return Collections.emptyList();
  }
}
