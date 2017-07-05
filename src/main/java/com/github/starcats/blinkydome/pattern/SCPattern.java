package com.github.starcats.blinkydome.pattern;

import com.github.starcats.blinkydome.model.BlinkyDome;

/**
 * Visitor pattern implementation to be used by Patterns to customize against specific models.
 *
 * Add an configureAgainst() (visitor-pattern "accept") method for each new model to avoid unchecked type casting.
 */
public interface SCPattern {
  /**
   * Visitor-pattern "accept()" to allow patterns to configure against BlinkyDome models
   * @param model BlinkyDome model
   */
  void configureAgainst(BlinkyDome model);
}
