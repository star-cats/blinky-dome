package com.github.starcats.blinkydome.pixelpusher;

import heronarts.lx.model.LXPoint;

/**
 * Indicates an LXPoint that contains PixelPusher LED metadata to be used in a {@link PixelPusherOutput}
 */
public interface PixelPushableLED {

  /** Which PixelPusher controller group the LED is associated with (0-indexed) */
  int getPpGroup();

  /** The port ("strip") in the PixelPusher controller group on which the LED is located (1-indexed) */
  int getPpPortIndex();

  /** The index of the LED on the PixelPusher port */
  int getPpLedIndex();

  /** Get the underlying {@link LXPoint} (usually just a downcast) */
  LXPoint getPoint();

}
