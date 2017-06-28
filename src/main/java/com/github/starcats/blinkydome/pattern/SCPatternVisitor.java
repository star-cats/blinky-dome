package com.github.starcats.blinkydome.pattern;

/**
 * Visitor pattern to be implemented by {@link com.github.starcats.blinkydome.model.StarcatsLxModel} to add
 * model-specific support of certain patterns.
 */
public interface SCPatternVisitor {
  void visit(SCPattern pattern);
}
