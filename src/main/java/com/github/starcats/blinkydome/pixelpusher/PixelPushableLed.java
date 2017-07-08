package com.github.starcats.blinkydome.pixelpusher;

import heronarts.lx.model.LXPoint;

/**
 * An LXPoint that contains pixelpusher LED metadata.
 */
public interface PixelPushableLed {

  /** Which PixelPusher controller group the LED is associated with */
  int getPpGroup();

  /** The strip index in the PixelPusher controller group on which the LED is located */
  int getPpStripIndex();

  /** The index of the LED on the PixelPusher strip */
  int getPpLedIndex();

  /** Get the underlying {@link LXPoint} (usually just a downcast) */
  LXPoint getPoint();

}
