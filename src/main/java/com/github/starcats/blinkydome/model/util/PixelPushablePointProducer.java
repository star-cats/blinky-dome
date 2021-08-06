package com.github.starcats.blinkydome.model.util;

import com.github.starcats.blinkydome.model.blinky_dome.BlinkyLED;

/**
 * VectorStripModel.PointProducer that creates BlinkyLEDs (LXPixels that are PixelPushableLED's)
 *
 * This is a stateful producer of BlinkyLEDs (LXPoints) that increments the LED's pixel pusher position for each
 * new LED produced.
 */
public class PixelPushablePointProducer implements VectorStripModel.PointProducer<BlinkyLED> {

  private final int ppGroup;
  private final int ppPort;
  private int ppIndex;

  public PixelPushablePointProducer(int ppGroup, int ppPort, int firstPpIndex) {
    this.ppGroup = ppGroup;
    this.ppPort = ppPort;
    this.ppIndex = firstPpIndex;
  }

  @Override
  public BlinkyLED constructPoint(float x, float y, float z) {
    // Increment ppIndex on every new factory constructor -- assumes subsequent calls
    // are down the strip.
    return new BlinkyLED(x, y, z, ppGroup, ppPort, ppIndex++);
  }
}
