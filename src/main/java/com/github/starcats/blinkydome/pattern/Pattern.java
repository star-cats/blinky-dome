package com.github.starcats.blinkydome.pattern;
import com.github.starcats.blinkydome.model.StarcatsLxModel;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import com.github.starcats.blinkydome.model.LED;

import java.util.List;

/**
 * Created by akesich on 6/20/17.
 */
public abstract class Pattern extends LXPattern {
  protected final StarcatsLxModel model;

  protected final List<LED> leds;

  public Pattern(LX lx) {
    super(lx);
    this.model = (StarcatsLxModel)super.model;
    this.leds = model.leds;
  }

  public void run(double deltaMx) {
    // no-op -- layers run automatically
  }

  public void setLEDColor(LED led, int c) {
    setColor(led.index, c);
  }
}
