package com.github.starcats.blinkydome.starpusher;

import heronarts.lx.model.LXPoint;

/**
 * Indicates an LXPoint that contains PixelPusher LED metadata to be used in a {@link StarPusherOutput}
 */
public interface StarPushableLED {

  /** Which PixelPusher controller group the LED is associated with (0-indexed) */
  String getSpAddress();

  /** The port ("strip") in the PixelPusher controller group on which the LED is located (1-indexed) */
  int getSpPort();

  /** The index of the LED on the PixelPusher port */
  int getSpLedIndex();

  /** Get the underlying {@link LXPoint} (usually just a downcast) */
  LXPoint getPoint();

}
