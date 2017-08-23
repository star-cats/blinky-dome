package com.github.starcats.blinkydome.util;

import ddf.minim.AudioBuffer;
import ddf.minim.analysis.BeatDetect;

/**
 * Mock BeatDetect wrapper that doesn't do any actual processing, just sends mock signals
 */
public class MockBeatDetect extends BeatDetect {


  @Override
  public void detect(AudioBuffer buffer) {
    // replace with no-op
  }

  public boolean isKick() {
    return System.currentTimeMillis() % 1000 < 100;
  }

  public boolean isSnare() {
    return System.currentTimeMillis() % 500 < 50;
  }

  public boolean isHat() {
    return System.currentTimeMillis() < 10;
  }
}
