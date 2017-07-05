package com.github.starcats.blinkydome.model;

import com.github.starcats.blinkydome.pattern.SCPatternVisitor;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;

import java.util.List;

/**
 * Class that defines mapping-specific pattern defaults that patterns can register themselves against.
 */
public abstract class StarcatsLxModel extends LXModel implements SCPatternVisitor {

  public StarcatsLxModel(List<LXPoint> allLeds) {
    super(allLeds);
  }
}