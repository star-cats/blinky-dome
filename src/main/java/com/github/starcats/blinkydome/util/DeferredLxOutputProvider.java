package com.github.starcats.blinkydome.util;

import heronarts.lx.output.LXOutput;

/**
 * Wiring/dependency hell kludge.  Provides a way to retrieve the lx outputs in a deferred manner
 * so things can be wired up in the required order.  It's needed, trust me.
 */
public interface DeferredLxOutputProvider {
  LXOutput getOutput();
}
