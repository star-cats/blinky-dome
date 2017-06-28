package com.github.starcats.blinkydome.pattern;

import com.github.starcats.blinkydome.model.BlinkyDome;

/**
 * Visitor pattern implementation to be used by Patterns to customize against specific models.
 *
 * Add an accept() method for each new model to avoid unchecked type casting (that's really all the visitor pattern does)
 */
public interface SCPattern {
  void accept(BlinkyDome model);
}
