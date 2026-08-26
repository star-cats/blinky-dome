package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXComponent;
import heronarts.lx.mixer.LXAbstractChannel;
import heronarts.lx.mixer.LXChannel;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.LXListenableNormalizedParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Wires a {@link ClickyConsole} onto the Ethereal Juice Box.
 *
 * The panel and the pattern were laid out for each other, so the mapping is
 * positional rather than arbitrary. Each pot places something and its pull ring
 * changes what that thing does:
 *
 * <pre>
 *   Pot 1 / Pull 1  ->  k1 / t1   disc X          / disc blows outward
 *   Pot 2 / Pull 2  ->  k2 / t2   disc Y          / disc becomes orbiting dots
 *   Pot 3 / Pull 3  ->  k3 / t3   sink X          / sink sucks inward
 *   Pot 4 / Pull 4  ->  k4 / t4   sink Y          / sink becomes arcs
 *   Pot 5 / Pull 5  ->  k5 / t5   sweep angle     / fire the sweep
 *   Pot 6 / Pull 6  ->  k6 / t6   bolt angle      / fire the bolt
 *
 *   Btn 1..5        ->  b1..b5    the momentary row
 *   Btn 6           ->  (spare)   the console has one button more than the
 *                                 pattern has controls for
 *   Btn 7           ->  b6        the console's latching toggle, onto the
 *                                 pattern's one latching control
 * </pre>
 *
 * <h2>Finding the pattern</h2>
 *
 * The target is found by the shape of its controls -- a pattern that has every
 * one of these parameter paths is the one we mean -- rather than by name or by
 * an index saved in the project file. That survives renaming the script,
 * reordering the channels, and porting the pattern from Javascript to Java,
 * none of which change what the thing is.
 *
 * <h2>Who wins</h2>
 *
 * While the console is connected it does. Values are pushed every frame, so
 * turning a knob in the Chromatik UI is overwritten by the next packet. When the
 * console goes quiet the binding stops writing entirely and the on-screen
 * controls behave normally again, which is what makes the pattern still usable
 * with the hardware unplugged.
 */
public class ClickyBinding {

  private static final String[] POT_TARGETS = { "k1", "k2", "k3", "k4", "k5", "k6" };
  private static final String[] PULL_TARGETS = { "t1", "t2", "t3", "t4", "t5", "t6" };

  /** Index 5 is null: the console's sixth button has nothing to drive yet. */
  private static final String[] BUTTON_TARGETS = { "b1", "b2", "b3", "b4", "b5", null, "b6" };

  private final ClickyConsole console;

  private final boolean[] previousPull = new boolean[ClickyPacket.POT_COUNT];
  private final boolean[] previousButton = new boolean[ClickyPacket.BUTTON_COUNT];

  /** Held only so appearing and disappearing can be logged once, not per frame. */
  private LXComponent lastTarget = null;

  public ClickyBinding(ClickyConsole console) {
    this.console = console;
  }

  /** Push the console's current state onto the pattern, if it is loaded. */
  public void push(LX lx) {
    final LXComponent target = findTarget(lx);
    if (target != this.lastTarget) {
      LX.log(target != null
        ? "Clicky console bound to " + target.getCanonicalLabel()
        : "Clicky console has nothing to bind: no pattern with the Juice Box controls is loaded");
      this.lastTarget = target;
    }
    if (target == null) {
      return;
    }

    for (int i = 0; i < ClickyPacket.POT_COUNT; ++i) {
      pushNormalized(target, POT_TARGETS[i], this.console.pot(i).getNormalized());

      final boolean pull = this.console.pull(i).isOn();
      pushBoolean(target, PULL_TARGETS[i], pull, this.previousPull[i]);
      this.previousPull[i] = pull;
    }

    for (int i = 0; i < ClickyPacket.BUTTON_COUNT; ++i) {
      final boolean pressed = this.console.button(i).isOn();
      pushBoolean(target, BUTTON_TARGETS[i], pressed, this.previousButton[i]);
      this.previousButton[i] = pressed;
    }
  }

  /**
   * Forget the edges we have seen, without writing anything.
   *
   * Called when the link drops, so that whatever the console is holding when it
   * comes back reads as a fresh press rather than as a continuation of a gesture
   * that ended minutes ago.
   */
  public void reset() {
    for (int i = 0; i < ClickyPacket.POT_COUNT; ++i) {
      this.previousPull[i] = this.console.pull(i).isOn();
    }
    for (int i = 0; i < ClickyPacket.BUTTON_COUNT; ++i) {
      this.previousButton[i] = this.console.button(i).isOn();
    }
  }

  private void pushNormalized(LXComponent target, String path, double normalized) {
    final LXParameter parameter = target.getParameter(path);
    if (parameter instanceof LXListenableNormalizedParameter) {
      final LXListenableNormalizedParameter normalizedParameter =
        (LXListenableNormalizedParameter) parameter;
      // Compared against the base value rather than getValue(), which on a
      // compound parameter has any modulation folded in. Comparing against that
      // would see a difference every frame and fight whatever is modulating it.
      if (normalizedParameter.getBaseNormalized() != normalized) {
        normalizedParameter.setNormalized(normalized);
      }
    }
  }

  private void pushBoolean(LXComponent target, String path, boolean on, boolean wasOn) {
    if (path == null) {
      return;
    }
    final LXParameter parameter = target.getParameter(path);
    if (!(parameter instanceof BooleanParameter)) {
      return;
    }
    final BooleanParameter bool = (BooleanParameter) parameter;
    if (bool.getMode() == BooleanParameter.Mode.MOMENTARY) {
      // A trigger control cannot be held: it fires its listeners and puts itself
      // straight back to false. Only the press edge can be passed through, and
      // holding the button down does nothing. This branch is here so the binding
      // still does something sensible against a pattern whose controls are
      // declared with trigger() rather than toggle().
      if (on && !wasOn) {
        bool.setValue(true);
      }
    } else if (bool.isOn() != on) {
      bool.setValue(on);
    }
  }

  /**
   * The first pattern in the mixer that carries the full set of Juice Box
   * controls, or null if none is loaded.
   *
   * Re-scanned every frame rather than cached. A cache would have to be
   * invalidated on pattern add, remove, channel reorder and project load, and
   * the scan it is saving is a handful of hash lookups against patterns that
   * miss on the very first path.
   */
  private LXComponent findTarget(LX lx) {
    for (LXAbstractChannel bus : lx.engine.mixer.channels) {
      if (bus instanceof LXChannel) {
        for (LXPattern pattern : ((LXChannel) bus).patterns) {
          if (hasControls(pattern)) {
            return pattern;
          }
        }
      }
    }
    return null;
  }

  private boolean hasControls(LXPattern pattern) {
    for (String path : POT_TARGETS) {
      if (!(pattern.getParameter(path) instanceof LXListenableNormalizedParameter)) {
        return false;
      }
    }
    for (String path : PULL_TARGETS) {
      if (!(pattern.getParameter(path) instanceof BooleanParameter)) {
        return false;
      }
    }
    for (String path : BUTTON_TARGETS) {
      if ((path != null) && !(pattern.getParameter(path) instanceof BooleanParameter)) {
        return false;
      }
    }
    return true;
  }
}
