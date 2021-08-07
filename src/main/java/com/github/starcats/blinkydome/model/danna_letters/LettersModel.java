package com.github.starcats.blinkydome.model.danna_letters;


import com.github.starcats.blinkydome.model.blinky_dome.BlinkyLED;
import com.github.starcats.blinkydome.model.util.PixelPushablePointProducer;
import com.github.starcats.blinkydome.model.util.VectorStripModel;
import com.github.starcats.blinkydome.pixelpusher.PixelPushableLED;
import com.github.starcats.blinkydome.pixelpusher.PixelPushableModel;
import heronarts.lx.model.LXModel;
import heronarts.lx.transform.LXVector;

import java.util.*;
import java.util.stream.Collectors;

public class LettersModel extends LXModel implements PixelPushableModel {
  /** Like LXModel.points, but up-cast to our LED class */
  public final List<BlinkyLED> leds;

  public final List<Letter> letters;


  public static LettersModel makeDANNA() {
    // Define the letters using PixelPusher points
    VectorStripModel.PointProducer<BlinkyLED> ledFactory = new PixelPushablePointProducer(0, 2, 0);

    Letter d = new Letter(
        ledFactory,
        new LXVector(0, 0, 0),
        (new Letter.SegmentBuilder())
            .addSegment(0, 8, 17)
            .addSegment(4, -1.5f, 7)
            .addSegment(1.5f, -2, 4)
            .addSegment(-1, -4.5f, 9)
            .addSegment(-2.5f, -0.5f, 3)
            .segmentSpecifications
    );

    Letter a1 = new Letter(
        ledFactory,
        new LXVector(9, 0, 0),
        (new Letter.SegmentBuilder())
            .addSegment(3, 8, 15)
            .addSegment(2, -8, 17)
            .addSegment(0.75f, 1, 3)
            .segmentSpecifications
    );

    Letter n1 = new Letter(
        ledFactory,
        new LXVector(18, 0, 0),
        (new Letter.SegmentBuilder())
            .addSegment(0,8, 13)
            .addSegment(4.25f, -5.75f, 12)
            .addSegment(0, 5.5f, 10)
            .segmentSpecifications
    );

    Letter n2 = new Letter(
        ledFactory,
        new LXVector(27, 8, 0),
        (new Letter.SegmentBuilder())
            .addSegment(1, 0, 2)
            .addSegment(-0.5f, -8, 13)
            .addSegment(0.75f, 6.5f, 12)
            .addSegment(2.75f, -3, 7)
            .addSegment(0, 4.25f, 6)
            .segmentSpecifications
    );

    Letter a2 = new Letter(
        ledFactory,
        new LXVector(38, 8, 0),
        (new Letter.SegmentBuilder())
            .addSegment(-2, -8, 14)
            .addSegment(1.5f, 3, 5)
            .addSegment(2, 0, 3)
            .addSegment(1.5f, -2.5f, 5)
            .addSegment(-1, 5, 8)
            .segmentSpecifications
    );

    List<Letter> allLetters = new ArrayList<>(
        Arrays.asList(d, a1, n1, n2, a2)
    );

    return makeModel(allLetters);
  }

  public static LettersModel makeModel(List<Letter> allLetters) {
    // Separate LED's into a single flat list (needed for LXModel constructor)
    List<BlinkyLED> allLeds = allLetters.stream()
        .map(Letter::getSegments)
        .flatMap(Collection::stream)
        .map(VectorStripModel::getPointsTyped)
        .flatMap(Collection::stream)
        .collect(Collectors.toList());

    return new LettersModel(allLeds, allLetters);
  }


  /** Use a static factory to create letters because java superconstructors need ordering :/ */
  public LettersModel(List<BlinkyLED> allLEDs, List<Letter> allLetters) {
    super(new ArrayList<>(allLEDs)); // dupe LXPoint-typed ArrayList needed for java generics type inference ಠ_ಠ

    this.leds = Collections.unmodifiableList(allLEDs);
    this.letters = Collections.unmodifiableList(allLetters);
  }

  @Override
  public List<? extends PixelPushableLED> getPpLeds() {
    return leds;
  }
}
