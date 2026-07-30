package com.starcats.blinkydome;

import com.google.gson.JsonObject;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.pattern.image.ImagePattern;

/**
 * Chromatik's image pattern, with a file reference that survives moving the repo.
 *
 * Everything the built-in does, this does — it *is* the built-in, subclassed.
 * The projection matrix, the GIF playback, even the device UI come along
 * unchanged: Chromatik picks a pattern's UI by walking its registry with
 * {@code Class.isInstance()}, so a subclass inherits UIImagePattern's file
 * picker and preview rather than falling back to a wall of knobs.
 *
 * The only thing overridden is serialization. See {@link MediaPath} for why the
 * stored path has to differ from the live one.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Image (Portable)")
@LXComponent.Description("Image pattern that stores its file reference relative to the media folder")
public class PortableImagePattern extends ImagePattern {

  /** Key the built-in registers its Image child under. */
  private static final String KEY_IMAGE = "image";

  public PortableImagePattern(LX lx) {
    super(lx);
  }

  @Override
  public void load(LX lx, JsonObject obj) {
    // Copy before rewriting: the object we're handed belongs to the caller —
    // the parsed project, an undo snapshot — and it should still read as it did
    // on disk after we're done with it.
    JsonObject resolved = obj.deepCopy();
    MediaPath.mapFileName(imageObject(resolved), stored -> MediaPath.toAbsolute(lx, stored));
    super.load(lx, resolved);
  }

  @Override
  public void save(LX lx, JsonObject obj) {
    super.save(lx, obj);
    MediaPath.mapFileName(imageObject(obj), absolute -> MediaPath.toRelative(lx, absolute));
  }

  /** The serialized form of the Image child, or null if this JSON has none. */
  private static JsonObject imageObject(JsonObject obj) {
    if (!obj.has(KEY_CHILDREN)) {
      return null;
    }
    JsonObject children = obj.getAsJsonObject(KEY_CHILDREN);
    return children.has(KEY_IMAGE) ? children.getAsJsonObject(KEY_IMAGE) : null;
  }
}
