package com.starcats.blinkydome;

import java.awt.Font;

/**
 * Picks a font that can actually draw the characters it is handed.
 *
 * Recovered from Packages/UnicodeCharPattern.jar, originally by Ben Rotter.
 *
 * A font that cannot render a code point does not fail — it silently draws the
 * missing-glyph box, which on a fixture is indistinguishable from the pattern
 * simply not working. This walks a list of fonts that between them cover emoji,
 * symbols and CJK on Windows, macOS and Linux, and returns the first that can
 * display every code point in the string.
 *
 * The requested font always wins if it can do the job, and is also what comes
 * back when nothing in the list can — a box drawn in the font you asked for is
 * at least an honest answer about what you asked for.
 */
final class FontFallback {

  /**
   * Ordered by how likely each is to be installed and to cover the character.
   * Emoji fonts first, then symbol fonts, then the broad Unicode faces, then CJK
   * by platform, and finally the two logical names every JVM guarantees.
   */
  private static final String[] CANDIDATES = {
    "Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji",
    "Segoe UI Symbol", "Apple Symbols", "Symbola",
    "Arial Unicode MS", "Lucida Sans Unicode", "Lucida Grande",
    "Microsoft YaHei", "SimSun", "Microsoft JhengHei",
    "PingFang SC", "Heiti SC",
    "Yu Gothic", "MS Gothic", "Hiragino Sans",
    "Malgun Gothic", "AppleGothic",
    "Ebrima", "Nirmala Text",
    "Dialog", "SansSerif"
  };

  private FontFallback() {}

  /**
   * The requested font, or the first fallback that can draw the whole string.
   *
   * @param family requested font family; empty falls back to SansSerif
   * @param text the characters that have to be drawable
   * @param style java.awt.Font style bits
   * @param size point size
   * @param allowFallback when false, the requested font is returned unexamined
   */
  static Font pick(String family, String text, int style, int size, boolean allowFallback) {
    if (family == null || family.isEmpty()) {
      family = "SansSerif";
    }
    Font requested = new Font(family, style, size);
    if (text == null || text.isEmpty() || !allowFallback || canDisplayAll(requested, text)) {
      return requested;
    }

    for (String candidate : CANDIDATES) {
      if (candidate.equalsIgnoreCase(family)) {
        continue;
      }
      Font font = new Font(candidate, style, size);
      if (canDisplayAll(font, text)) {
        return font;
      }
    }
    return requested;
  }

  /**
   * Whether the font covers every code point in the string.
   *
   * Iterated by code point rather than by char, so an astral character — every
   * emoji, among other things — is tested as the one thing it is instead of as
   * two surrogate halves that no font claims to display.
   */
  private static boolean canDisplayAll(Font font, String text) {
    int length = text.length();
    for (int i = 0; i < length; ) {
      int codePoint = text.codePointAt(i);
      if (!font.canDisplay(codePoint)) {
        return false;
      }
      i += Character.charCount(codePoint);
    }
    return true;
  }
}
