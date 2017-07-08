package com.github.starcats.blinkydome.pixelpusher;

import java.util.List;

/**
 * Put this onto an {@link heronarts.lx.model.LXModel} that can be pixelpusher'd
 */
public interface PixelPushableModel {
  List<PixelPushableLed> getPpLeds();
}
