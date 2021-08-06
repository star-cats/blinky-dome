package com.github.starcats.blinkydome.model.danna_letters;

import heronarts.lx.transform.LXVector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * D in DANNA
 */
public class LetterD extends AbstractLetter {
  public LetterD(LXVector origin, int ppGroup, int ppPort, int firstPpIndex) {
    super(origin, ppGroup, ppPort, firstPpIndex);
  }

  @Override
  List<SegmentSpecification> getSegmentSpecs() {
    return (new SegmentBuilder())
        .addSegment(0, 8, 17)
        .addSegment(4, -1.5f, 7)
        .addSegment(1.5f, -2, 4)
        .addSegment(-1, -4.5f, 9)
        .addSegment(-2.5f, -0.5f, 3)
        .segmentSpecifications;
  }
}
