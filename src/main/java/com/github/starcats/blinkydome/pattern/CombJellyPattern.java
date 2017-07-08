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
import heronarts.lx.parameter.LXParameterListener;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Pattern inspired by Ctenophores
 */
public class CombJellyPattern extends LXPattern {

  public final CompoundParameter baseBrightnessParam;
  public final CompoundParameter pulseBrightnessParam;

  public final BooleanParameter triggerPulse;

  public final CompoundParameter fixtureDurationMs;

  public final CompoundParameter wigglePeriodMs;

  public final CompoundParameter wiggleDurationMs;

  public final CompoundParameter hueWiggleWidth;


  private final LXPalette baseColor;
  private final List<? extends LXFixture> fixtures;

  private final List<Pulse> pulses = new LinkedList<>();

  public CombJellyPattern(LX lx, LXPalette baseColorPalette, List<? extends LXFixture> fixtures) {
    super(lx);

    this.baseColor = baseColorPalette;
    this.fixtures = fixtures;

    baseBrightnessParam = new CompoundParameter("base b", 0, 0, 100);
    baseBrightnessParam.setDescription("Base Brightness: Sets the brightness (black) of the base hue");
    addParameter(baseBrightnessParam);

    pulseBrightnessParam = new CompoundParameter("puls b", 100, 0, 100);
    pulseBrightnessParam.setDescription("Pulse Brightness: Sets the brightness (black) of the pulse hue");
    addParameter(pulseBrightnessParam);

    triggerPulse = new BooleanParameter("pulse", false)
        .setMode(BooleanParameter.Mode.MOMENTARY)
        .setDescription("Tap to trigger a new pulse");
    addParameter(triggerPulse);


    fixtureDurationMs = new CompoundParameter("f duration", 500, 100, 5000);
    fixtureDurationMs
        .setDescription("Fixture Duration: How long a pulse takes to travel length of fixtures")
        .setUnits(LXParameter.Units.MILLISECONDS);
    addParameter(fixtureDurationMs);

    wiggleDurationMs = new CompoundParameter("w dur", 800, 100, 2000);
    wiggleDurationMs
        .setDescription("Wiggle Duration: How long a point wiggles in the pulse")
        .setUnits(LXParameter.Units.MILLISECONDS);
    addParameter(wiggleDurationMs);

    wigglePeriodMs = new CompoundParameter("w period", 400, 100, 2000);
    wigglePeriodMs
        .setDescription("The period of a full wiggle cycle (ms)")
        .setUnits(LXParameter.Units.MILLISECONDS);
    addParameter(wigglePeriodMs);


    CompoundParameter wiggleDurationToPeriodRatio = new CompoundParameter(
        "w d/p",
        0,
        // log10 scale, ie -1, 0, and 1 mean 0.1, 1, and 10
        Math.log10( wiggleDurationMs.range.min / wigglePeriodMs.range.max ),
        Math.log10( wiggleDurationMs.range.max / wigglePeriodMs.range.min )
    );
    wiggleDurationToPeriodRatio.setDescription("Ratio of wiggle duration to wiggle period");
    addParameter(wiggleDurationToPeriodRatio);
    wiggleDurationToPeriodRatio.setUnits(LXParameter.Units.LOG10);


    hueWiggleWidth = new CompoundParameter("c width", 50, 0, 180);
    hueWiggleWidth.setDescription("Color Wiggle Width: how wide in the color palette one wiggle is");
    addParameter(hueWiggleWidth);


    triggerPulse.addListener(parameter -> {
      if (parameter.getValue() == 1) {
        this.pulses.add(new Pulse(
            fixtureDurationMs.getValue(),
            wigglePeriodMs.getValue(),
            wiggleDurationMs.getValue()
        ));
      }
    });


    // Going to do something weird: going to join wiggle duration, period, and duration-to-period.  Changing the
    // first two will change the third; changing the third will change the first two
    boolean[] changingLock = new boolean[]{ false };
    LXParameterListener onRatioChanged = parameter -> {
      if (changingLock[0]) {
        return;
      }

      changingLock[0] = true;

      wiggleDurationToPeriodRatio.setValue( Math.log10(wiggleDurationMs.getValue() / wigglePeriodMs.getValue()) );

      changingLock[0] = false;
    };
    wiggleDurationMs.addListener(onRatioChanged);
    wigglePeriodMs.addListener(onRatioChanged);

    wiggleDurationToPeriodRatio.addListener(parameter -> {
      if (changingLock[0]) {
        return;
      }

      changingLock[0] = true;

      double ratio = Math.pow(10, wiggleDurationToPeriodRatio.getValue());

      double duration = wiggleDurationMs.getValue();
      double period = duration / ratio;

      if (period < wigglePeriodMs.range.min) {
        period = wigglePeriodMs.range.min;
        duration = ratio * period;
      } else if (period > wigglePeriodMs.range.max) {
        period = wigglePeriodMs.range.max;
        duration = ratio * period;
      }

      wiggleDurationMs.setValue( duration );
      wigglePeriodMs.setValue( period );

      changingLock[0] = false;
    });

    onRatioChanged.onParameterChanged(null); // init the ratio param
  }

  public void run(double deltaMs) {
    for (LXFixture fixture : fixtures) {
      for (LXPoint pt : fixture.getPoints()) {
        setColor(pt.index, baseColor.getColor(baseBrightnessParam.getValue()));
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
                  pulseBrightnessParam.getValue()
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

    /** How long a point wiggle */
    final double wiggleDurationMs;

    Pulse(double fixturePeriodMs, double wigglePeriodMs, double wiggleDurationMs) {
      this.fixturePeriodMs = fixturePeriodMs;
      this.wigglePeriodMs = wigglePeriodMs;
      this.wiggleDurationMs = wiggleDurationMs;
      this.lifespanMs = 0D;
    }

    void step(double deltaMs) {
      lifespanMs += deltaMs;
    }

    boolean isStillActive() {
      return lifespanMs < fixturePeriodMs + wiggleDurationMs;
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

      if (tMs > wiggleDurationMs) {
        return 0; // force the point to be done wiggling
      }

      return getWaveformModulation(tMs);
    }
  }
}
