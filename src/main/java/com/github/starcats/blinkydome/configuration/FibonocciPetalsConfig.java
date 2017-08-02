package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.color.ImageColorSamplerGroup;
import com.github.starcats.blinkydome.model.FibonocciPetalsModel;
import com.github.starcats.blinkydome.pattern.PerlinBreathing;
import com.github.starcats.blinkydome.pattern.PerlinNoisePattern;
import com.github.starcats.blinkydome.pattern.RainbowZPattern;
import com.github.starcats.blinkydome.pattern.fibonocci_petals.PerlinPetalsPattern;
import com.github.starcats.blinkydome.pattern.mask.Mask_AngleSweep;
import com.github.starcats.blinkydome.pattern.mask.Mask_BrightnessBeatBoost;
import com.github.starcats.blinkydome.pattern.mask.Mask_FixtureDottedLine;
import com.github.starcats.blinkydome.pattern.mask.Mask_Perlin;
import com.github.starcats.blinkydome.pattern.mask.Mask_RandomFixtureSelector;
import com.github.starcats.blinkydome.pattern.mask.Mask_RollingBouncingDisc;
import com.github.starcats.blinkydome.pattern.mask.Mask_WipePattern;
import com.github.starcats.blinkydome.pattern.mask.Mask_XyzFilter;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LX;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
import heronarts.lx.audio.BandGate;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.output.FadecandyOutput;
import heronarts.lx.output.LXOutput;
import heronarts.lx.parameter.LXCompoundModulation;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;
import processing.core.PVector;

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
  protected ImageColorSamplerGroup colorSampler;
  protected CommonScLxConfigUtils.MinimBeatTriggers minimBeatTriggers;


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
    colorSampler = CommonScLxConfigUtils.Components.makeColorSampler(p, lx);

    minimBeatTriggers = new CommonScLxConfigUtils.MinimBeatTriggers(lx, starCatFFT);
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
    return 3;
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

    // Channel 2: initial masks
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

    channel.setPatterns(patterns.toArray(new LXPattern[patterns.size()]));

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


    Mask_RandomFixtureSelector randomFixtureMask = new Mask_RandomFixtureSelector(lx, model.allPetals);
    minimBeatTriggers.triggerWithKick(randomFixtureMask);


    Mask_WipePattern wipeMask = new Mask_WipePattern(lx);
    minimBeatTriggers.triggerWithKick(wipeMask);
    wipeMask.widthPx.setValue(20);


    List<LXPattern> patterns = new ArrayList<>();
    patterns.add(mask_disc);
    patterns.add(mask_perlin);
    patterns.add(mask_bbb);
    patterns.add(new Mask_FixtureDottedLine(lx, model.allPetals));
    patterns.add(new Mask_AngleSweep(lx, new PVector(1, 0, 0), model.allPetals, lx.tempo));
    patterns.add(randomFixtureMask);
    patterns.add(wipeMask);
    patterns.add(new Mask_XyzFilter(lx));

    // patterns.addAll(makeStandardPatterns());

    return patterns;

  }

  protected List<LXPattern> getGuiPatterns() {
    return Collections.emptyList();
  }
}
