package com.github.starcats.blinkydome.util;

import ddf.minim.AudioInput;
import ddf.minim.Minim;
import ddf.minim.analysis.BeatDetect;
import ddf.minim.analysis.FFT;
import heronarts.lx.LX;

import static processing.core.PApplet.*;

/**
 * Wrapper class around a minim FFT configuration.
 *
 * Hooks into LX engine to do the sound sampling on every LX loop
 */
public class StarCatFFT {

  private static float DECAY = 0.97f;

  private Minim minim;
  private float[] fftFilter;

  private FFT fft;

  public final AudioInput in;
  public final BeatDetect beat;


  public StarCatFFT(LX lx) {

    this.minim = new Minim(this);
    //minim.debugOn();

    // 44hz is 2x 22hz (nyquist on human hearing).
    // However, for music-reactive stuff, most stuff happens lower down.  Do only 3/4 of 44hz to get finer resolution
    // on the frequencies we care about
    int ttl = 5;
    AudioInput in = null;
    while (in == null && ttl >= 0) {
      in = minim.getLineIn(Minim.MONO, 1024, 44100);

      if (in == null) {
        System.out.println("No audio input found, sleeping for 500ms to try again (tries left: " + ttl);
        ttl--;
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          throw new RuntimeException("InterruptedException while sleeping! ", e);
        }
      }
    }
    this.in = in;

    if (in == null) {
      System.out.println("No input found, doing mock beatDetect");
      this.beat = new MockBeatDetect();
      return;
    }

    // TODO: Turned off to get some extra processing?  No effect  :(
    //this.fft = new FFT(in.bufferSize(), in.sampleRate());
    //this.fftFilter = new float[fft.specSize()];

    this.beat = new BeatDetect(in.bufferSize(), in.sampleRate());
    this.beat.setSensitivity(200);


    // Register with LX engine to do the audio sampling
    lx.engine.addLoopTask(deltaMs -> {
      this.forward();
      AudioDetector.LINE_IN.tick(deltaMs, false);
    });
  }

  // Move the FFT forward one cycle
  public void forward() {
    this.beat.detect(in.mix);

    if (this.fft != null) {
      this.fft.forward(in.mix);

      for (int i = 0; i < this.fftFilter.length; i++) {
        this.fftFilter[i] = max(this.fftFilter[i] * DECAY, log(1 + this.fft.getBand(i)));
      }
    }
  }

  public float[] getFilter() {
    if (this.fft == null) {
      throw new UnsupportedOperationException("Manual FFT turned off");
    }
    return this.fftFilter.clone();
  }

  public float[] getFilter(int numBuckets) {
    float[] filter = new float[numBuckets];
    for (int i=0; i<numBuckets; i++) {

      // Sample true FFT buckets into the numBuckets specified
      filter[i] = this.fftFilter[ (int)(map(i, 0, numBuckets, 0, this.fftFilter.length)) ];
    }

    return filter;
  }

}
