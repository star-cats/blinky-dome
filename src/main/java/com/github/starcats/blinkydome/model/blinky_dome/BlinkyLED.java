package com.github.starcats.blinkydome.model.blinky_dome;

import com.github.starcats.blinkydome.pixelpusher.PixelPushableLED;
import heronarts.lx.model.LXPoint;
import processing.core.PVector;

/**
 * BlinkyDome's LED definition
 */
public class BlinkyLED extends LXPoint implements PixelPushableLED {
  final public int ppGroup, ppPort, ppIndex;

  /** Angular position of triangle in XZ (floor) plane */
  public final float thetaRad;

  /** Angular position of triangle in XY (up/down) plane */
  public final float phiRad;

  /**
   * @param x global x-position
   * @param y global y-position
   * @param z global z-position
   * @param ppGroup Which pixelpusher group (0-indexed)
   * @param ppPort Which pixelpusher strip/port (1-indexed)
   * @param ppIndex The LED index on the pixelpusher strip/port
   */
  public BlinkyLED(float x, float y, float z, int ppGroup, int ppPort, int ppIndex) {
    super(x, y, z);

    this.ppGroup = ppGroup;
    this.ppPort = ppPort;
    this.ppIndex = ppIndex;

    this.thetaRad = PVector.angleBetween(
        new PVector(1f, 0f, 1f),
        new PVector(x, y, z)
    );
    this.phiRad = PVector.angleBetween(
        new PVector(1f, 1f, 0f),
        new PVector(x, y, z)
    );
  }

  @Override
  public int getPpGroup() {
    return ppGroup;
  }

  @Override
  public int getPpPortIndex() {
    return ppPort;
  }

  @Override
  public int getPpLedIndex() {
    return ppIndex;
  }

  @Override
  public LXPoint getPoint() {
    return this;
  }
}
