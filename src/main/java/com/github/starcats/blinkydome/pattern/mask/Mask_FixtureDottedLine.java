package com.github.starcats.blinkydome.pattern.mask;

import com.github.starcats.blinkydome.util.EasingWaveshapes;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.LXWaveshape;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXCompoundModulation;

import java.util.ArrayList;
import java.util.List;

/**
 * Masking pattern that draws alternating on-off dotted lines down the length of a fixture.
 * 
 * Effect really relies on LEDs being evenly spaced down the length of a fixture and all fixtures having about
 * the same number of LEDs
 */
public class Mask_FixtureDottedLine extends LXPattern {
  
  /** Duration a segment takes to travel the length of a fixture*/
  public final CompoundParameter periodMs;
  
  /** How many dotted lines (segments) a fixture consists of */
  public final CompoundParameter numSegments;
  
  /** What percentage of a segment is considered on */
  public final CompoundParameter onLength;

  public final CompoundParameter position;


  private List<LXFixture> fixtures;
  private VariableLFO positionLFO;

  public Mask_FixtureDottedLine(LX lx, List<? extends LXFixture> fixtures) {
    super(lx);

    this.fixtures = new ArrayList<>(fixtures);

    position = new CompoundParameter("pos", 0, 0, 1)
        .setDescription("'Start' segment position");
    addParameter(position);

    periodMs = (CompoundParameter) new CompoundParameter("period", 1000, 100, 10000)
        .setDescription("Duration a segment takes to travel the length of a fixture")
        .setExponent(2)
        .setValue(5000);
//    addParameter(periodMs);

    numSegments = (CompoundParameter) new CompoundParameter("num", 3, 1, 40)
        .setDescription("How many segments to draw in each fixture")
        .setExponent(2);
    addParameter(numSegments);

    onLength = new CompoundParameter("on pct", 0.5)
        .setDescription("What percentage of a segment is 'on'");
    addParameter(onLength);

    positionLFO = (VariableLFO) new VariableLFO(
        "positionLFO",
        new LXWaveshape[] {
            LXWaveshape.UP,
            LXWaveshape.SIN,
            LXWaveshape.TRI,
            EasingWaveshapes.BOUNCE_OUT,
        },
        periodMs
    ).setDescription("Position modulator of segments");
    positionLFO.waveshape.setValue(LXWaveshape.UP);
    LXCompoundModulation posModulation = new LXCompoundModulation(positionLFO, position);
    posModulation.range.setValue(1);
    this.modulation.addModulation(posModulation);
    positionLFO.start();
    this.modulation.addModulator(positionLFO);
  }

  @Override
  protected void run(double deltaMs) {
    double lenPerSegment = 1. / numSegments.getValue();

    for (LXFixture fixture : fixtures) {
      List<LXPoint> pts = fixture.getPoints();

      if (pts.size() <= 1) continue; // skip degenerate fixtures

      double ptSpacing = 1. / (pts.size() - 1);

      // Current segment: init to seed positionLFO
      double segmentStart = position.getValue();
      double initialPos = position.getValue();

      // Init segment loop by finding and starting with the first point inside the seed segment
      int ptI = 0;
      double ptPos = 0;
      while (ptPos < segmentStart) {
        ptI = incrButWrapAt(pts.size(), ptI);
        ptPos += ptSpacing;
      }

      // Loop across all the segments
      while (segmentStart < 1. + initialPos) {
        double segmentOffStart = segmentStart + lenPerSegment * onLength.getValue(); // note: can go >1
        double segmentEnd = segmentStart + lenPerSegment; // note: can go >1

        // First point is next to the trailing edge of the on-segment.  Fade it out.
        setColor(pts.get(ptI).index, LXColor.hsb(0, 0, (ptPos - segmentStart)/ptSpacing * 100. ));

        ptI = incrButWrapAt(pts.size(), ptI);
        ptPos += ptSpacing;

        // Light up remaining points in the on-segment
        while (ptPos < segmentOffStart) {
          setColor(pts.get(ptI).index, LXColor.WHITE);

          ptI = incrButWrapAt(pts.size(), ptI);
          ptPos += ptSpacing;
        }

        // Now the current point is first in the off-segment.
        // If it's still inside the segment, it's leading the on-segment, so fade it in.
        if (ptPos < segmentEnd) {
          setColor(pts.get(ptI).index, LXColor.hsb(0, 0, (1. - (ptPos - segmentOffStart)/ptSpacing) * 100.));

          ptI = incrButWrapAt(pts.size(), ptI);
          ptPos += ptSpacing;
        }

        // And turn remaining ones off
        while (ptPos < segmentEnd) {
          setColor(pts.get(ptI).index, LXColor.BLACK);

          ptI = incrButWrapAt(pts.size(), ptI);
          ptPos += ptSpacing;
        }


        // Done with the segment. Increment the segment loop to the next segment
        segmentStart = segmentEnd;
      }


    }
  }

  private static int incrButWrapAt(int wrapAt, int i) {
    int result = i + 1;
    return result == wrapAt ? 0 : result;
  }
}
