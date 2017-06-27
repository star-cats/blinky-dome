package com.github.starcats.blinkydome.pattern.effects;


import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.SawLFO;

public class Sparklers {
  private final LXPattern host;

  private SawLFO[] sparklers;
  private boolean isRunning = false;
  private double timeSinceLastShuffle = 0;

  public Sparklers(LXPattern host) {
    this.host = host;
    sparklers = new SawLFO[host.getModel().points.length];
  }

  public void stopSparklers() {
    sparklers = new SawLFO[this.host.getModel().points.length];
    isRunning = false;
    timeSinceLastShuffle = 0;
  }

  public void resetSparklers() {
    this.stopSparklers();
    isRunning = true;

    for (int i=0; i<sparklers.length; i++) {
      if (Math.random() < 0.7) {
        sparklers[i] = null;
      } else {
        sparklers[i] = new SawLFO(0, 100, 100);
        sparklers[i].start();
      }
    }
  }

  public void run(double deltaMs) {
    if (!isRunning) {
      return;
    }

    // Run all the sparkler LFO's
    for (int i=0; i<sparklers.length; i++) {
      if (sparklers[i] != null) {
        sparklers[i].loop(deltaMs);
      }
    }

    for (LXPoint p : host.getModel().points) {
      if (sparklers[p.index] != null) {
        int color = host.getColors()[p.index];
        host.getColors()[p.index] = LX.hsb(
            LXColor.h(color),
            LXColor.s(color) * (100 - sparklers[p.index].getValuef()) / 100,
            sparklers[p.index].getValuef()
        );
      }
    }

    timeSinceLastShuffle += deltaMs;
    if (timeSinceLastShuffle > 400) {
      this.resetSparklers();
    }
  }
}
