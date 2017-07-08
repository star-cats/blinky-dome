package com.github.starcats.blinkydome.pattern;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXPalette;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXParameter;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Pattern inspired by Ctenophores
 */
public class CombJellyPattern extends LXPattern {

  public final CompoundParameter brightnessParam;

  public final BooleanParameter triggerPulse;

  public final CompoundParameter fixturePeriodMs;

  public final CompoundParameter wigglePeriodMs;

  public final CompoundParameter hueWiggleWidth;


  private final LXPalette baseColor;
  private final List<? extends LXFixture> fixtures;

  private final List<Pulse> pulses = new LinkedList<>();

  public CombJellyPattern(LX lx, LXPalette baseColorPalette, List<? extends LXFixture> fixtures) {
    super(lx);

    this.baseColor = baseColorPalette;
    this.fixtures = fixtures;

    brightnessParam = new CompoundParameter("brightness", 100, 0, 100);
    brightnessParam.setDescription("Sets the brightness (black) of the base hue");
    addParameter(brightnessParam);

    triggerPulse = new BooleanParameter("pulse", false)
        .setMode(BooleanParameter.Mode.MOMENTARY)
        .setDescription("Tap to trigger a new pulse");
    addParameter(triggerPulse);


    fixturePeriodMs = new CompoundParameter("duration", 1000, 100, 10000);
    fixturePeriodMs
        .setDescription("How long a pulse takes to travel length of fixtures")
        .setUnits(LXParameter.Units.MILLISECONDS);
    addParameter(fixturePeriodMs);

    wigglePeriodMs = new CompoundParameter("wiggle", 500, 100, 10000);
    wigglePeriodMs
        .setDescription("How long a point wiggles in the pulse")
        .setUnits(LXParameter.Units.MILLISECONDS);
    addParameter(wigglePeriodMs);

    hueWiggleWidth = new CompoundParameter("c width", 50, 0, 180);
    hueWiggleWidth.setDescription("Color Wiggle Width: how wide in the color palette one wiggle is");
    addParameter(hueWiggleWidth);


    triggerPulse.addListener(parameter -> {
      if (parameter.getValue() == 1) {
        this.pulses.add(new Pulse(
            fixturePeriodMs.getValue(),
            wigglePeriodMs.getValue()
        ));
      }
    });
  }

  public void run(double deltaMs) {
    // TODO TEMP??  Initialize each point to base hue
    for (LXFixture fixture : fixtures) {
      for (LXPoint pt : fixture.getPoints()) {
        setColor(pt.index, baseColor.getColor(brightnessParam.getValue()));
      }
    }


    for (LXFixture fixture : fixtures) {
      for (Pulse pulse : pulses) {
        for (int i=0; i < fixture.getPoints().size(); i++) {
          double normalizedI = (double) i / fixture.getPoints().size();
          LXPoint pt = fixture.getPoints().get(i);
          double ptModulation = pulse.getModulationForPt(normalizedI);

          if (ptModulation == 0) {
            continue;
          }
          
          blendColor(
              pt.index,
              LXColor.hsb(
                  palette.getHue() + hueWiggleWidth.getValue() * ptModulation,
                  palette.getSaturation(),
                  brightnessParam.getValue()
              ),
              LXColor.Blend.LERP
          );
        }

      }
    }


    Iterator<Pulse> pulsesIter = pulses.iterator();
    while (pulsesIter.hasNext()) {
      Pulse pulse = pulsesIter.next();

      // For each pulse: advance
      pulse.step(deltaMs);

      // Filter out dead pulses
      if (!pulse.isStillActive()) {
        pulsesIter.remove();
      }
    }
  }

  private static class Pulse {
    /** How long this pulse has been active */
    double lifespanMs;

    /** How long it will take this pulse to travel the normalized length of the fixture (0-1)*/
    final double fixturePeriodMs;

    /** How long it will take a point to do a complete wiggle of the waveform */
    final double wigglePeriodMs;

    Pulse(double fixturePeriodMs, double wigglePeriodMs) {
      this.fixturePeriodMs = fixturePeriodMs;
      this.wigglePeriodMs = wigglePeriodMs;
      this.lifespanMs = 0D;
    }

    void step(double deltaMs) {
      lifespanMs += deltaMs;
    }

    boolean isStillActive() {
      return lifespanMs < fixturePeriodMs + wigglePeriodMs;
    }

    double getWaveformModulation(double tMs) {
      return Math.sin(2 * Math.PI * tMs / wigglePeriodMs);
    }

    /**
     *
     * @param normalizedI 0-1.  The point's normalized position down the length of the fixture (first is 0)
     * @return Value from -1 to 1, indicating the amount of wiggle the point should have
     */
    double getModulationForPt(double normalizedI) {
      double ptStartT = fixturePeriodMs * normalizedI; // At what time the point should start its wiggle

      if (ptStartT > lifespanMs) {
        return 0;
      }

      double tMs = lifespanMs - ptStartT;

      if (tMs > wigglePeriodMs) {
        return 0; // force the point to be done wiggling
      }

      return getWaveformModulation(tMs);
    }
  }
}
