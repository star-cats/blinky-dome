package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.color.ImageColorSamplerClan;
import com.github.starcats.blinkydome.model.FibonocciPetalsModel;
import com.github.starcats.blinkydome.pattern.PerlinNoisePattern;
import com.github.starcats.blinkydome.pattern.RainbowZPattern;
import com.github.starcats.blinkydome.pattern.fibonocci_petals.PerlinPetalsPattern;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LX;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.output.FadecandyOutput;
import heronarts.lx.output.LXOutput;
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
    return Collections.emptyList();
  }

  @Override
  protected void configChannel(int channelNum, LXChannel channel) {
    List<LXPattern> patterns = new ArrayList<>(Arrays.asList(
        new PerlinPetalsPattern(lx, p, starCatFFT, colorSampler),
        new PerlinNoisePattern(lx, p, starCatFFT.beat, colorSampler),
        new RainbowZPattern(lx)
    ));
    patterns.addAll(getGuiPatterns());

    channel.setPatterns(patterns.toArray(new LXPattern[patterns.size()]));
  }

  protected List<LXPattern> getGuiPatterns() {
    return Collections.emptyList();
  }
}
