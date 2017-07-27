package com.github.starcats.blinkydome.util;

import heronarts.lx.model.LXFixture;
import processing.core.PVector;

/**
 * StarCats abstract fixture that adds some additional bells and whistles
 */
public interface SCFixture extends LXFixture {

  /** Gets fixture centroid */
  PVector getCentroid();

  /** Gets fixture centroid x */
  float getCx();

  /** Gets fixture centroid y */
  float getCy();

  /** Gets fixture centroid z */
  float getCz();
}
