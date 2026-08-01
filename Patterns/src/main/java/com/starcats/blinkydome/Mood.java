package com.starcats.blinkydome;

/**
 * What the music is doing right now, as far as {@link PrimaryController} can
 * tell.
 *
 * Two states, and only two, both decided by whether bass is landing. There was
 * once a BUILDING state that tried to recognise a riser from the shape of the
 * intensity curve; it was dropped because that judgement is too fragile to hang a
 * show on. Distinguishing a build from a track simply getting louder, or from a
 * long pad, needs thresholds that hold for one song and not the next, and when it
 * guessed wrong it did so at exactly the moment everyone was watching.
 *
 * Bass or no bass is a fact. These two are worth trusting because there is
 * nothing to get wrong.
 */
public enum Mood {

  /** No bass has arrived for several beats. The floor is empty. */
  AMBIENT("Ambient"),

  /** Bass is landing where it is supposed to. */
  DRIVING("Driving");

  private final String label;

  private Mood(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return this.label;
  }
}
