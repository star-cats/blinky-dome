package com.github.starcats.blinkydome.pattern.mask;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.LXUtils;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.CompoundParameter;
import processing.core.PApplet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adapted from Tenere's 'Starlight' pattern by Mark Slee
 * https://github.com/treeoftenere/Tenere/blob/master/Tenere/Patterns.pde
 */
public class TMask_Starlight extends LXPattern {

  public final CompoundParameter speed =
      new CompoundParameter("Speed", 3000, 9000, 300)
          .setDescription("Speed of the twinkling");

  public final CompoundParameter variance =
      new CompoundParameter("Variance", .5, 0, .9)
          .setDescription("Variance of the twinkling");

  public final CompoundParameter numStars;

  private static final LXUtils.LookupTable flicker = new LXUtils.LookupTable(360, new LXUtils.LookupTable.Function() {
    public float compute(int i, int tableSize) {
      return (float) (.5 - .5 * Math.cos(i * Math.PI * 2. / tableSize));
    }
  });

  private final PApplet p;
  private final List<Star> stars;
  private final int ptsPerStar;

  public TMask_Starlight(PApplet p, LX lx, int ptsPerStar) {
    super(lx);
    this.p = p;
    this.ptsPerStar = ptsPerStar;

    int numStars = (int) Math.ceil((double) model.getPoints().size() / ptsPerStar);
    ArrayList<Star> stars = new ArrayList<>(numStars);

    for (int i = 0; i < numStars; ++i) {
      stars.add(new Star(i));
    }

    Collections.shuffle(stars);
    this.stars = stars;

    this.numStars = new CompoundParameter("Num", numStars / 2, 0, numStars)
        .setDescription("Number of stars");


    addParameter("speed", this.speed);
    addParameter("variance", this.variance);
    addParameter("numStars", this.numStars);
  }

  public void run(double deltaMs) {
    setColors(LXColor.BLACK);
    float numStars = this.numStars.getValuef();
    float speed = this.speed.getValuef();
    float variance = this.variance.getValuef();
    int i = 0;
    for (Star star : this.stars) {
      if (star.active) {
        star.run(deltaMs);
      } else if (i < numStars) {
        star.activate(speed, variance);
      }
      i++;
    }
  }

  private class Star {

    final int num;

    double period;
    float amplitude = 50;
    double accum = 0;
    boolean active = false;


    Star(int num) {
      this.num = num;
    }

    void activate(float speed, float variance) {
      this.period = Math.max(400, speed * (1 + p.random(-variance, variance)));
      this.accum = 0;
      this.amplitude = p.random(20, 100);
      this.active = true;
    }

    void run(double deltaMs) {
      int c = LXColor.gray(this.amplitude * flicker.get(this.accum / this.period));
      for (int i = 0; i < ptsPerStar; ++i) {
        TMask_Starlight.this.setColor(TMask_Starlight.this.model.getPoints().get(num * ptsPerStar + i).index, c);
      }
      this.accum += deltaMs;
      if (this.accum > this.period) {
        this.active = false;
      }
    }
  }
}
