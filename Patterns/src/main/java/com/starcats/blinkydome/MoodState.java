package com.starcats.blinkydome;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * How the supporting modulators find the {@link PrimaryController}.
 *
 * There is no way to wire one modulator to another in Chromatik's UI -- you can
 * map a *parameter*, but the supporting trackers need the whole picture: tempo,
 * phase, intensity, mood. So the controller publishes itself here and the
 * children look it up.
 *
 * A list rather than a single slot, because two controllers briefly coexist
 * every time a project is loaded over a running one, and because a stale
 * reference to a disposed controller is a far worse failure than picking the
 * wrong one of two. {@link #get()} returns the first live instance, or null when
 * there is none -- children treat that as "output nothing" rather than throwing.
 *
 * CopyOnWriteArrayList because the engine thread reads this every frame while
 * registration happens on whichever thread built the component.
 */
public final class MoodState {

  private static final List<PrimaryController> ACTIVE = new CopyOnWriteArrayList<>();

  private MoodState() {}

  static void register(PrimaryController controller) {
    ACTIVE.add(controller);
  }

  /**
   * Called from the controller's dispose. Without this a reloaded project would
   * leave the old controller published forever, and every child would follow a
   * corpse.
   */
  static void unregister(PrimaryController controller) {
    ACTIVE.remove(controller);
  }

  /** The controller the supporting modulators should follow, or null if none. */
  public static PrimaryController get() {
    return ACTIVE.isEmpty() ? null : ACTIVE.get(0);
  }

  public static int count() {
    return ACTIVE.size();
  }
}
