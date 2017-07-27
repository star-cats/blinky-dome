package com.github.starcats.blinkydome.util;

import heronarts.lx.modulator.LXWaveshape;

/**
 * Misc {@link LXWaveshape} inspired by easing functions
 */
public class EasingWaveshapes {

  // D3 Bounce:
  // https://github.com/d3/d3-ease/blob/master/src/bounce.js
  static private double b1 = 4. / 11.;
  static private double b2 = 6. / 11.;
  static private double b3 = 8. / 11.;
  static private double b4 = 3. / 4.;
  static private double b5 = 9. / 11.;
  static private double b6 = 10. / 11.;
  static private double b7 = 15. / 16.;
  static private double b8 = 21. / 22.;
  static private double b9 = 63. / 64.;
  static private double b0 = 1. / b1 / b1;

  private static double bounceOut(double t) {
    // https://github.com/d3/d3-ease/blob/master/src/bounce.js   sigh.
    return (t = +t) < b1 ? b0 * t * t : t < b3 ? b0 * (t -= b2) * t + b4 : t < b6 ? b0 * (t -= b5) * t + b7 : b0 * (t -= b8) * t + b9;
  }


  public static LXWaveshape BOUNCE_OUT = new LXWaveshape() {
    @Override
    public double compute(double basis) {
      return bounceOut(basis);
    }

    @Override
    public double invert(double value, double basisHint) {
      throw new UnsupportedOperationException("TODO");
    }

    public String toString() {
      return "BOUNCE OUT";
    }
  };

  public static LXWaveshape BOUNCE_IN = new LXWaveshape() {
    @Override
    public double compute(double basis) {
      return 1 - bounceOut(1 - basis);
    }

    @Override
    public double invert(double value, double basisHint) {
      throw new UnsupportedOperationException("TODO");
    }

    public String toString() {
      return "BOUNCE IN";
    }
  };

  public static LXWaveshape BOUNCE_IN_OUT = new LXWaveshape() {
    @Override
    public double compute(double basis) {
      // https://github.com/d3/d3-ease/blob/master/src/bounce.js   sigh.
      return ((basis *= 2.) <= 1. ? 1. - bounceOut(1. - basis) : bounceOut(basis - 1.) + 1.) / 2.;
    }

    @Override
    public double invert(double value, double basisHint) {
      throw new UnsupportedOperationException("TODO");
    }

    public String toString() {
      return "BOUNCE INOUT";
    }
  };
}
