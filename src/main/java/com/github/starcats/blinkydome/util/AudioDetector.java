package com.github.starcats.blinkydome.util;

import ddf.minim.AudioBuffer;

/**
 * Detects if audio is running or not. (The PS3 Eye camera/mic is jenky as all hell on linux)
 */
public class AudioDetector {
  private static final double MS_ACCUMULATOR_ROLLOVER = 3000; // check for 3 sec

  public static AudioDetector LINE_IN;
  public static boolean mute = false;

  private AudioBuffer audioBuffer;
  private float maxAudioLevel = 0;
  private double msAccumulator;

  public static void init(AudioBuffer audioBuffer) {
    LINE_IN = new AudioDetector(audioBuffer);
  }

  private AudioDetector(AudioBuffer audioBuffer) {
    this.audioBuffer = audioBuffer;
  }

  public void tick(double deltaMs) {
    this.tick(deltaMs, false);
  }

  public void tick(double deltaMs, boolean verbose) {
    msAccumulator += deltaMs;

    if (msAccumulator > MS_ACCUMULATOR_ROLLOVER) {
      if (verbose) {
        System.out.println(isRunning() ?
            "max audio level: " + getMaxLevel() :
            "No Audio  :(" + (mute ? "  but in-java muted :?" : ""));
      }
      msAccumulator = 0;
      maxAudioLevel = 0;
    }

    if (audioBuffer != null) {
      maxAudioLevel = Math.max(maxAudioLevel, audioBuffer.level());
    }
  }

  public float getMaxLevel() {
    if (audioBuffer != null) {
      return maxAudioLevel;
    } else {
      return -1;
    }
  }

  public boolean isRunning() {
    return !mute && getMaxLevel() > 0;
  }
}
