package com.github.starcats.blinkydome.model;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXModel;
import processing.core.PApplet;

import java.util.List;

/**
 * Class that defines mapping-specific pattern defaults that patterns can register themselves against.
 */
public abstract class StarcatsLxModel extends LXModel{

  public final boolean hasGui;

  public StarcatsLxModel(LXFixture[] fixtures, boolean hasGui) {
    super(fixtures);
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
  public abstract List<LXPattern> configPatterns(LX lx, PApplet p);
}