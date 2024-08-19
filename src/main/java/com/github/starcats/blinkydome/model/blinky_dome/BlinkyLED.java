package com.github.starcats.blinkydome.model.blinky_dome;

import com.github.starcats.blinkydome.starpusher.StarPushableLED;
import heronarts.lx.model.LXPoint;
import processing.core.PVector;

/**
 * BlinkyModel's LED definition
 */
public class BlinkyLED extends LXPoint implements StarPushableLED {
  private int spPort, spLedIndex;

  private String spAddress;

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
  public BlinkyLED(float x, float y, float z, String spAddress, int spPort, int spLedIndex) {
    super(x, y, z);

    this.spAddress = spAddress;
    this.spPort = spPort;
    this.spLedIndex = spLedIndex;

    this.thetaRad = PVector.angleBetween(
        new PVector(1f, 0f, 1f),
        new PVector(x, y, z)
    );
    this.phiRad = PVector.angleBetween(
        new PVector(1f, 1f, 0f),
        new PVector(x, y, z)
    );
  }

  //
  // PixelPushableLED implementations:
  @Override
  public String getSpAddress() {
    return spAddress;
  }

  @Override
  public int getSpPort() {
    return spPort;
  }

  @Override
  public int getSpLedIndex() {
    return spLedIndex;
  }

  public void setSpAddress(String spAddress) {
    this.spAddress = spAddress;
  }

  public void setSpPort(int spPort) {
    this.spPort = spPort;
  }

  public void setSpLedIndex(int spIndex) {
    this.spLedIndex = spIndex;
  }

  @Override
  public LXPoint getPoint() {
    return this;
  }
}
