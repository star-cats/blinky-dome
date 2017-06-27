package com.github.starcats.blinkydome.model;

import com.github.starcats.blinkydome.model.BlinkyDome.Fixture;
import com.github.starcats.blinkydome.pattern.PerlinNoisePattern;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.model.LXModel;
import processing.core.PApplet;

import java.util.List;

//import com.github.starcats.blinkydome.model.LED;

/**
 * Class that defines mapping-specific pattern defaults that patterns can register themselves against.
 */
public abstract class StarcatsLxModel extends LXModel{

  public final boolean hasGui;
  public final List<LED> leds;

  public StarcatsLxModel(List<LED> leds, boolean hasGui) {
    super(new Fixture(leds));
    this.leds = leds;
    this.hasGui = hasGui;
  }

  /**
   * Implementation hook for models to customize LX or LX engine
   * @param lx lx
   * @return This instance, for chaining
   */
  public StarcatsLxModel initLx(LX lx) {
    // default is no-op
    return this;
  }

  /**
   * Implementation should configure a bunch of supported patterns and return them.
   * The model runner will run the first pattern as the default pattern.
   *
   * @param lx lx instance
   * @param p pApplet instance
   * @return List of patterns supported by the model
   */
  public abstract List<LXPattern> configPatterns(LX lx, PApplet p, StarCatFFT fft);

  /**
   * Let model implementation visit a PerlinNoisePattern and configure any defaults, etc. specific to the model
   * @param perlinNoise
   */
  public abstract void applyPresets(PerlinNoisePattern perlinNoise);
}