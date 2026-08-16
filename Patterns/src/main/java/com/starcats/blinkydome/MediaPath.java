package com.starcats.blinkydome;

import java.io.File;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import heronarts.lx.LX;
import heronarts.lx.LXComponent;

/**
 * Translation between the absolute image paths Chromatik works in and the
 * media-relative ones we want in the project files.
 *
 * Chromatik's image patterns load through {@code new File(fileName)}, so the
 * value they hold has to be a real path on this machine, resolved against the
 * JVM's working directory. Saved verbatim, that pins a project to one clone
 * location: the refs in our .lxp files were absolute under ~/Chromatik/, which
 * is exactly as portable as it sounds.
 *
 * The fix is to keep the in-memory value absolute — the patterns need that —
 * and rewrite only what crosses the serialization boundary. A path under the
 * media root goes to disk as "Images/foo.png" and comes back resolved against
 * whatever the media root is on the machine doing the loading. Anything outside
 * the media root is left alone: it was never portable to begin with, and
 * silently rewriting it would only hide that.
 */
final class MediaPath {

  private static final String KEY_FILE_NAME = "fileName";

  private MediaPath() {}

  /**
   * Media-relative to absolute, for a value read out of a project file.
   *
   * Already-absolute values pass through, which is what keeps old projects
   * loading: the pre-existing ~/Chromatik/Images/... refs still work on the
   * machine they were written on, and only turn relative on the next save.
   */
  static String toAbsolute(LX lx, String stored) {
    if (stored == null || stored.isEmpty()) {
      return stored;
    }
    if (new File(stored).isAbsolute()) {
      return stored;
    }
    // Normalized, because the media path can itself be relative ("." when LX is
    // started bare), and "/Users/you/Chromatik/./Images/x.png" is a value the
    // file picker would then show back to you.
    return lx.getMediaFile(stored).toPath().toAbsolutePath().normalize().toString();
  }

  /** Absolute to media-relative, for a value about to be written to a project file. */
  static String toRelative(LX lx, String absolute) {
    if (absolute == null || absolute.isEmpty()) {
      return absolute;
    }
    File file = new File(absolute);
    if (!file.isAbsolute()) {
      // Already relative — nothing to do, and resolving it here against the
      // working directory would be wrong.
      return absolute;
    }
    Path root = new File(lx.getMediaPath()).toPath().toAbsolutePath().normalize();
    Path target = file.toPath().toAbsolutePath().normalize();
    if (!target.startsWith(root)) {
      return absolute;
    }
    // Forward slashes even on Windows: the value has to read the same on every
    // machine, and File/Path accept '/' as a separator everywhere.
    return root.relativize(target).toString().replace(File.separatorChar, '/');
  }

  /**
   * Rewrites the fileName parameter of one serialized image component in place.
   *
   * Tolerates nulls and absent keys — these objects come from project files that
   * may predate the parameter, or be a preset holding only a subset of it.
   */
  static void mapFileName(JsonObject imageObj, UnaryOperator<String> op) {
    if (imageObj == null || !imageObj.has(LXComponent.KEY_PARAMETERS)) {
      return;
    }
    JsonObject parameters = imageObj.getAsJsonObject(LXComponent.KEY_PARAMETERS);
    if (!parameters.has(KEY_FILE_NAME)) {
      return;
    }
    JsonElement fileName = parameters.get(KEY_FILE_NAME);
    if (fileName == null || fileName.isJsonNull()) {
      return;
    }
    parameters.addProperty(KEY_FILE_NAME, op.apply(fileName.getAsString()));
  }
}
