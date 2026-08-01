package com.starcats.blinkydome;

/**
 * What the music is doing right now, as far as {@link PrimaryController} can
 * tell.
 *
 * Three states, monitored continuously against the audio. The supporting
 * modulators gate themselves off these rather than each re-deriving the same
 * thing from the raw levels.
 */
public enum Mood {

  /** No bass has arrived for several beats. The floor is empty. */
  AMBIENT("Ambient"),

  /**
   * Intensity climbing steadily with no bass under it -- a riser. Only reachable
   * from AMBIENT, and only once AMBIENT has held long enough to be real.
   */
  BUILDING("Building"),

  /** Bass is landing where it is supposed to. Entered from anywhere. */
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
