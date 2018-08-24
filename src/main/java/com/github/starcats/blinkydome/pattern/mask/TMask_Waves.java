package com.github.starcats.blinkydome.pattern.mask;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.DampedParameter;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.SawLFO;
import heronarts.lx.parameter.CompoundParameter;

import static heronarts.lx.LX.TWO_PI;

/**
 * Adapted from Tenere's PatternWaves, by Mark C. Slee
 */
public class TMask_Waves extends LXPattern {
  final int NUM_LAYERS = 3;
  final int INCHES = 1;
  final int FEET = INCHES * 12;

  final float AMP_DAMPING_V = 1.5f;
  final float AMP_DAMPING_A = 2.5f;

  final float LEN_DAMPING_V = 1.5f;
  final float LEN_DAMPING_A = 1.5f;

  public final CompoundParameter rate = (CompoundParameter)
          new CompoundParameter("Rate", 6000, 48000, 2000)
                  .setDescription("Rate of the of the wave motion")
                  .setExponent(.3);

  public final CompoundParameter size =
          new CompoundParameter("Size", 4*FEET, 6*INCHES, 28*FEET)
                  .setDescription("Width of the wave");

  public final CompoundParameter amp1 =
          new CompoundParameter("Amp1", .5, 2, .2)
                  .setDescription("First modulation size");

  public final CompoundParameter amp2 =
          new CompoundParameter("Amp2", 1.4, 2, .2)
                  .setDescription("Second modulation size");

  public final CompoundParameter amp3 =
          new CompoundParameter("Amp3", .5, 2, .2)
                  .setDescription("Third modulation size");

  public final CompoundParameter len1 =
          new CompoundParameter("Len1", 1, 2, .2)
                  .setDescription("First wavelength size");

  public final CompoundParameter len2 =
          new CompoundParameter("Len2", .8, 2, .2)
                  .setDescription("Second wavelength size");

  public final CompoundParameter len3 =
          new CompoundParameter("Len3", 1.5, 2, .2)
                  .setDescription("Third wavelength size");

  private final LXModulator phase =
          startModulator(new SawLFO(0, TWO_PI, rate));

  private final LXModulator amp1Damp = startModulator(new DampedParameter(this.amp1, AMP_DAMPING_V, AMP_DAMPING_A));
  private final LXModulator amp2Damp = startModulator(new DampedParameter(this.amp2, AMP_DAMPING_V, AMP_DAMPING_A));
  private final LXModulator amp3Damp = startModulator(new DampedParameter(this.amp3, AMP_DAMPING_V, AMP_DAMPING_A));

  private final LXModulator len1Damp = startModulator(new DampedParameter(this.len1, LEN_DAMPING_V, LEN_DAMPING_A));
  private final LXModulator len2Damp = startModulator(new DampedParameter(this.len2, LEN_DAMPING_V, LEN_DAMPING_A));
  private final LXModulator len3Damp = startModulator(new DampedParameter(this.len3, LEN_DAMPING_V, LEN_DAMPING_A));

  private final LXModulator sizeDamp = startModulator(new DampedParameter(this.size, 40*FEET, 80*FEET));

  private final double[] bins = new double[512];

  public TMask_Waves(LX lx) {
    super(lx);
    addParameter("rate", this.rate);
    addParameter("size", this.size);
    addParameter("amp1", this.amp1);
    addParameter("amp2", this.amp2);
    addParameter("amp3", this.amp3);
    addParameter("len1", this.len1);
    addParameter("len2", this.len2);
    addParameter("len3", this.len3);
  }

  public void run(double deltaMs) {
    double phaseValue = phase.getValue();
    float amp1 = this.amp1Damp.getValuef();
    float amp2 = this.amp2Damp.getValuef();
    float amp3 = this.amp3Damp.getValuef();
    float len1 = this.len1Damp.getValuef();
    float len2 = this.len2Damp.getValuef();
    float len3 = this.len3Damp.getValuef();
    float falloff = 100 / this.sizeDamp.getValuef();

    for (int i = 0; i < bins.length; ++i) {
      bins[i] = model.cy + model.yRange/2 * Math.sin(i * TWO_PI / bins.length + phaseValue);
    }
    for (LXPoint leaf : model.getPoints()) {
      int idx = (int) Math.round((bins.length-1) * (len1 * leaf.xn)) % bins.length;
      int idx2 = (int) Math.round((bins.length-1) * (len2 * (.2 + leaf.xn))) % bins.length;
      int idx3 = (int) Math.round((bins.length-1) * (len3 * (1.7 - leaf.xn))) % bins.length;

      float y1 = (float) bins[idx];
      float y2 = (float) bins[idx2];
      float y3 = (float) bins[idx3];

      float d1 = Math.abs(leaf.y *amp1 - y1);
      float d2 = Math.abs(leaf.y *amp2 - y2);
      float d3 = Math.abs(leaf.y *amp3 - y3);

      float b = Math.max(0, 100 - falloff * Math.min(Math.min(d1, d2), d3));
      setColor(leaf.index, b > 0 ? LXColor.gray(b) : LXColor.BLACK);
    }
  }
}
