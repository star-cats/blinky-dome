package com.github.starcats.blinkydome.model.util;

import heronarts.lx.transform.LXVector;

import java.util.List;

/**
 * Extension of VectorStripModel that models a VectorStripModel being connected to other VectorStripModels at the
 * start and end (ie a graph of interconnected VectorStripModel's).
 *
 * Useful for finding strips next to each other.
 */
public class ConnectedVectorStripModel extends VectorStripModel {
  private List<ConnectedVectorStripModel> startNode;
  private List<ConnectedVectorStripModel> endNode;

  public ConnectedVectorStripModel(LXVector start, List<ConnectedVectorStripModel> startNode,
                                   LXVector end, List<ConnectedVectorStripModel> endNode, int numPoints) {
    super(start, end, VectorStripModel.GENERIC_POINT_FACTORY, numPoints);
    this.startNode = startNode;
    this.endNode = endNode;
  }
}
