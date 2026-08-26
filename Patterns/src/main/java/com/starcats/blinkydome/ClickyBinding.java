package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.effect.LXEffect;
import heronarts.lx.mixer.LXAbstractChannel;
import heronarts.lx.mixer.LXBus;
import heronarts.lx.mixer.LXChannel;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
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
 *   Btn 1..6        ->  b1..b6    the momentary row
 *   Btn 7           ->  t7        the console's latching toggle, which is the
 *                                 master switch rather than a pattern control
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
 * <h2>What this owns beyond the pattern</h2>
 *
 * Two of the console's controls are about the installation rather than about the
 * fluid, and neither can be implemented inside the script, so both live here:
 *
 * <ul>
 * <li><b>B5</b> runs the Kaleidoscope Postprocess effect sitting on the pattern,
 * rolling a fresh symmetry on each press and switching it off on release.
 * <li><b>T7</b> is the master switch, and rides the two waterfall channel faders
 * with it. See {@link #updateFaders}.
 * </ul>
 *
 * Both are driven off the pattern's own <code>b5</code> and <code>t7</code>
 * parameters rather than off the console's buttons directly, so they work
 * identically when the controls are clicked in the Chromatik UI with no hardware
 * on the network.
 *
 * <h2>Who wins</h2>
 *
 * For the pattern's own controls, the console does while it is connected. Values
 * are pushed every frame, so turning a knob in the Chromatik UI is overwritten by
 * the next packet. When the console goes quiet the binding stops writing them and
 * the on-screen controls behave normally again, which is what makes the pattern
 * still usable with the hardware unplugged.
 *
 * The two waterfall faders are the exception: once the console has been seen at
 * all this session, they are driven every frame whether or not it is still there,
 * because a console that has gone is a system that is off. Nothing is written
 * before the first packet ever arrives, so a project opened with no hardware on
 * the network keeps every fader exactly as it was saved.
 */
public class ClickyBinding {

  private static final String[] POT_TARGETS = { "k1", "k2", "k3", "k4", "k5", "k6" };
  private static final String[] PULL_TARGETS = { "t1", "t2", "t3", "t4", "t5", "t6" };
  private static final String[] BUTTON_TARGETS = { "b1", "b2", "b3", "b4", "b5", "b6", "t7" };

  /** The pattern control B5 and T7 are read from. */
  private static final String KALEIDOSCOPE_TARGET = "b5";
  private static final String MASTER_TARGET = "t7";

  /**
   * How far a pot has to move before it counts as somebody touching the panel.
   *
   * A pot reading is an ADC value, and it dithers by a count or two while nobody
   * is anywhere near it. Without a deadband that jitter reads as continuous
   * activity and the idle timeout below never fires. Half a percent is well
   * clear of the noise floor and far under a deliberate nudge.
   */
  private static final double POT_DEADBAND = 0.005;

  /**
   * Silence on the panel that counts as nobody being there.
   *
   * The console heartbeats once a second whether or not anything has moved, so
   * this is measured against actual control changes rather than against packet
   * arrival -- a console sitting untouched on the network is idle, not active.
   */
  private static final long IDLE_NANOS = 3_000_000_000L;

  /** Fader positions the master switch drives toward, on and off. */
  private static final double CTRL_ON = 1;
  private static final double CTRL_OFF = 0;
  private static final double CLEAR_ON = 0.5;
  private static final double CLEAR_OFF = 0;

  /** Every fade covers its own full span in this long, so the two stay together. */
  private static final double FADE_SECONDS = 1;

  /** Found by label; it holds no patterns, so there is nothing else to know it by. */
  private static final String CLEAR_CHANNEL_LABEL = "WF-CLEAR";

  /** Symmetry is a DiscreteParameter over [1,8), so this is its whole range. */
  private static final int SYMMETRY_MIN = 1;
  private static final int SYMMETRY_MAX = 7;

  private final ClickyConsole console;

  private final boolean[] previousPull = new boolean[ClickyPacket.POT_COUNT];
  private final boolean[] previousButton = new boolean[ClickyPacket.BUTTON_COUNT];
  private final double[] restingPot = new double[ClickyPacket.POT_COUNT];

  /** Held only so appearing and disappearing can be logged once, not per frame. */
  private LXPattern lastTarget = null;
  private boolean warnedMissingClear = false;

  private boolean previousKaleidoscope = false;
  private long lastActivityNanos = 0;

  /**
   * Set when the link has dropped, so the first push of the next link is read as
   * a baseline rather than compared against a panel state minutes stale.
   */
  private boolean baselinePending = true;

  public ClickyBinding(ClickyConsole console) {
    this.console = console;
  }

  /**
   * One frame of work.
   *
   * The console is only read onto the pattern while the link is up, but the
   * effect and the faders are serviced either way: a fade that was halfway
   * through when the cable came out has to finish, and a console that is gone is
   * the most idle a console can be.
   *
   * @param lx The engine
   * @param deltaMs Milliseconds since the previous frame
   * @param connected Whether console packets are currently arriving
   */
  public void update(LX lx, double deltaMs, boolean connected) {
    final LXPattern target = findTarget(lx);
    if (target != this.lastTarget) {
      LX.log(target != null
        ? "Clicky console bound to " + target.getCanonicalLabel()
        : "Clicky console has nothing to bind: no pattern with the Juice Box controls is loaded");
      this.lastTarget = target;
    }
    if (target == null) {
      return;
    }
    if (connected) {
      pushConsole(target);
    }
    updateKaleidoscope(target);
    updateFaders(lx, target, deltaMs);
  }

  /** Mirror the panel onto the pattern's controls, noting anything that moved. */
  private void pushConsole(LXPattern target) {
    final boolean baselining = this.baselinePending;
    this.baselinePending = false;
    boolean touched = false;

    for (int i = 0; i < ClickyPacket.POT_COUNT; ++i) {
      final double position = this.console.pot(i).getNormalized();
      // Measured from where the pot was last agreed to be resting rather than
      // from its previous frame, so a slow deliberate turn accumulates past the
      // deadband instead of being discarded one sub-threshold step at a time.
      if (baselining) {
        // First push of a new link. Wherever the pots are is where they have
        // been all along as far as we are concerned; a knob that was turned
        // while the cable was out is not somebody turning it now.
        this.restingPot[i] = position;
      } else if (Math.abs(position - this.restingPot[i]) >= POT_DEADBAND) {
        this.restingPot[i] = position;
        touched = true;
      }
      pushNormalized(target, POT_TARGETS[i], position);

      final boolean pull = this.console.pull(i).isOn();
      touched |= pull != this.previousPull[i];
      pushBoolean(target, PULL_TARGETS[i], pull, this.previousPull[i]);
      this.previousPull[i] = pull;
    }

    for (int i = 0; i < ClickyPacket.BUTTON_COUNT; ++i) {
      final boolean pressed = this.console.button(i).isOn();
      // A button being *held* is somebody's hand on the panel just as much as a
      // press edge is: a long hold must not time out underneath them. T7 is the
      // exception and is deliberately excluded -- it is a latch, and a latch
      // left on is exactly the state the idle timeout exists to notice.
      final boolean momentary = i < ClickyPacket.BUTTON_COUNT - 1;
      touched |= (pressed != this.previousButton[i]) || (pressed && momentary);
      pushBoolean(target, BUTTON_TARGETS[i], pressed, this.previousButton[i]);
      this.previousButton[i] = pressed;
    }

    if (touched) {
      this.lastActivityNanos = System.nanoTime();
    }
  }

  /**
   * The console has just appeared on the network.
   *
   * Counted as somebody arriving at the panel, so the box comes up on the wall
   * for the idle timeout's worth of time rather than waiting to be touched
   * first. Losing the link is deliberately <em>not</em> the mirror of this: a
   * console that has gone is idle immediately, and the faders start away at once.
   */
  public void linkUp() {
    this.lastActivityNanos = System.nanoTime();
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
    this.baselinePending = true;
  }

  // ------------------------------------------------------------- B5, the folds

  /**
   * B5 runs the Kaleidoscope Postprocess effect on the pattern. A press rolls a
   * new symmetry and switches it on; the release switches it off.
   *
   * The symmetry is rolled on the press rather than held on a knob because the
   * console has no spare knob, and because a fold count that lands somewhere new
   * every press is the whole appeal of the button.
   */
  private void updateKaleidoscope(LXPattern target) {
    final boolean on = isOn(target, KALEIDOSCOPE_TARGET);
    final boolean pressed = on && !this.previousKaleidoscope;
    final boolean released = !on && this.previousKaleidoscope;
    this.previousKaleidoscope = on;
    if (!pressed && !released) {
      return;
    }
    for (LXEffect effect : target.effects) {
      if (effect instanceof KaleidoscopePostprocessEffect) {
        final KaleidoscopePostprocessEffect kaleidoscope =
          (KaleidoscopePostprocessEffect) effect;
        if (pressed) {
          kaleidoscope.symmetry.setValue(
            SYMMETRY_MIN + (int) (Math.random() * (SYMMETRY_MAX - SYMMETRY_MIN + 1)));
        }
        kaleidoscope.enabled.setValue(pressed);
      }
    }
  }

  // ------------------------------------------------------- T7, the master switch

  /**
   * Ride the two waterfall channel faders with the master switch.
   *
   * WF-CTRL carries the Juice Box itself; WF-CLEAR is a patternless multiply
   * channel sitting under it, which knocks the rest of the waterfall back so the
   * box has something dark to read against. On means both come up, off means
   * both go away and the waterfall returns to whatever else is playing on it.
   *
   * Three seconds without anybody touching the panel counts as off too, whatever
   * the switch says, so the waterfall comes back to itself between visitors and
   * a console left latched on in an empty field does not hold it all night.
   *
   * Once the console has spoken these two faders belong to it: they are stepped
   * toward the target every frame, so dragging either one in the UI is pulled
   * straight back. That is the point -- the panel is the only thing that should
   * be able to decide whether the box is on the wall -- but it does mean these
   * are not usable as ordinary faders while a console is on the network.
   */
  private void updateFaders(LX lx, LXPattern target, double deltaMs) {
    final boolean idle = System.nanoTime() - this.lastActivityNanos >= IDLE_NANOS;
    final boolean on = isOn(target, MASTER_TARGET) && !idle;
    final double seconds = deltaMs / 1000;

    // getMixerChannel() rather than getChannel(): it walks up out of a pattern
    // rack, where the direct parent is the rack rather than the channel.
    final LXChannel ctrl = target.getMixerChannel();
    if (ctrl != null) {
      fade(ctrl.fader, on ? CTRL_ON : CTRL_OFF, Math.abs(CTRL_ON - CTRL_OFF), seconds);
    }
    final LXBus clear = findClearChannel(lx);
    if (clear != null) {
      fade(clear.fader, on ? CLEAR_ON : CLEAR_OFF, Math.abs(CLEAR_ON - CLEAR_OFF), seconds);
    }
  }

  /**
   * Step one fader toward its target, covering <code>span</code> in
   * {@link #FADE_SECONDS}. Both faders therefore cross their own full range in
   * the same wall-clock time even though their ranges differ.
   */
  private void fade(CompoundParameter fader, double destination, double span, double seconds) {
    // The base value, not getValue(): on a compound parameter the latter has any
    // modulation folded in, and stepping from that would fight the modulator and
    // ratchet the underlying value along with it.
    final double current = fader.getBaseValue();
    final double remaining = destination - current;
    if (remaining == 0) {
      return;
    }
    final double step = span * seconds / FADE_SECONDS;
    fader.setValue(Math.abs(remaining) <= step
      ? destination
      : current + Math.signum(remaining) * step);
  }

  private LXBus findClearChannel(LX lx) {
    for (LXAbstractChannel bus : lx.engine.mixer.channels) {
      if (CLEAR_CHANNEL_LABEL.equalsIgnoreCase(bus.getLabel())) {
        return bus;
      }
    }
    if (!this.warnedMissingClear) {
      this.warnedMissingClear = true;
      LX.log("Clicky console found no channel named " + CLEAR_CHANNEL_LABEL
        + "; the master switch will ride WF-CTRL alone");
    }
    return null;
  }

  private boolean isOn(LXPattern target, String path) {
    final LXParameter parameter = target.getParameter(path);
    return (parameter instanceof BooleanParameter) && ((BooleanParameter) parameter).isOn();
  }

  private void pushNormalized(LXPattern target, String path, double normalized) {
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

  private void pushBoolean(LXPattern target, String path, boolean on, boolean wasOn) {
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
  private LXPattern findTarget(LX lx) {
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
      if (!(pattern.getParameter(path) instanceof BooleanParameter)) {
        return false;
      }
    }
    return true;
  }
}
