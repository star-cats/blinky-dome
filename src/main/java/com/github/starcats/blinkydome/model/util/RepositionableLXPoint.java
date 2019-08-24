package com.github.starcats.blinkydome.model.util;

import heronarts.lx.LX;
import heronarts.lx.model.LXPoint;

/**
 * (Mostly copy-paste) extension of LXPoint where the positioning variables are NOT final.
 *
 * Allows us to change the position of these points. Useful in calibration patterns, eg where fixtures need to be
 * rotated to map virtual coordinates to real world.
 */
public class RepositionableLXPoint extends LXPoint {

  public float x;
  public float y;
  public float z;
  public float r;
  public float rxy;
  public float rxz;
  public float theta;
  public float azimuth;
  public float elevation;

  public RepositionableLXPoint(float x, float y, float z) {
    super(x, y, z);
    this.reposition(x, y, z);
  }

  public void reposition(LXPoint newPosition) {
    this.reposition(newPosition.x, newPosition.y, newPosition.z);
  }

  public void reposition(float newX, float newY, float newZ) {
    float x = newX;
    float y = newY;
    float z = newZ;

    // Copy/paste from LXPoint constructor, but references this class's non-final member variables
    this.x = x;
    this.y = y;
    this.z = z;
    this.r = (float) Math.sqrt(x * x + y * y + z * z);
    this.rxy = (float) Math.sqrt(x * x + y * y);
    this.rxz = (float) Math.sqrt(x * x + z * z);
    this.theta = (float) ((LX.TWO_PI + Math.atan2(y, x)) % (LX.TWO_PI));
    this.azimuth = (float) ((LX.TWO_PI + Math.atan2(z, x)) % (LX.TWO_PI));
    this.elevation = (float) ((LX.TWO_PI + Math.atan2(y, rxz)) % (LX.TWO_PI));
  }
}
