package com.github.starcats.blinkydome.model.danna_letters;


import com.github.starcats.blinkydome.model.blinky_dome.BlinkyLED;
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

  public final List<AbstractLetter> letters;


  public static LettersModel makeDANNA() {
    List<AbstractLetter> allLetters = new ArrayList<>(
        Arrays.asList(
            new LetterD(new LXVector(0, 0, 0), 0, 1, 0)
        )
    );

    return makeModel(allLetters);
  }

  public static LettersModel makeModel(List<AbstractLetter> allLetters) {
    // Separate LED's into a single flat list (needed for LXModel constructor)
    List<BlinkyLED> allLeds = allLetters.stream()
        .map(AbstractLetter::getSegments)
        .flatMap(Collection::stream)
        .map(VectorStripModel::getPointsTyped)
        .flatMap(Collection::stream)
        .collect(Collectors.toList());

    return new LettersModel(allLeds, allLetters);
  }


  /** Use a static factory to create letters because java superconstructors need ordering :/ */
  public LettersModel(List<BlinkyLED> allLEDs, List<AbstractLetter> allLetters) {
    super(new ArrayList<>(allLEDs)); // dupe LXPoint-typed ArrayList needed for java generics type inference ಠ_ಠ

    this.leds = Collections.unmodifiableList(allLEDs);
    this.letters = Collections.unmodifiableList(allLetters);
  }

  @Override
  public List<? extends PixelPushableLED> getPpLeds() {
    return leds;
  }
}
