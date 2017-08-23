package com.github.starcats.blinkydome.util;

import heronarts.lx.LX;

/**
 * A Mock FFT wrapper that does no FFT processing.
 * Used to measure processing impact of doing FFT's using minim.
 */
public class MockStarcatFFT extends StarCatFFT {

  private float[] fftFilter;

  public MockStarcatFFT(LX lx) {
    super(lx);
    this.fftFilter = new float[1024];
  }

  @Override
  public void forward() {
    int len = this.fftFilter.length;
    long millis = System.currentTimeMillis();
    for (int i=0; i < len; i++) {
      this.fftFilter[i] = (float)(millis % len > i - 10 && millis % len < i + 10 ? 1.0 : 0.0);
    }
  }

  @Override
  public float[] getFilter() {
    return this.fftFilter.clone();
  }
}
