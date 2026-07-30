package com.starcats.blinkydome;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.pattern.image.SlideshowPattern;

/**
 * Chromatik's slideshow pattern, with file references that survive moving the repo.
 *
 * Same trick as {@link PortableImagePattern}, applied to a list instead of a
 * single child: the slideshow serializes its images as a top-level array rather
 * than as component children, so the rewrite walks that array.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Slideshow (Portable)")
@LXComponent.Description("Slideshow pattern that stores its file references relative to the media folder")
public class PortableSlideshowPattern extends SlideshowPattern {

  /** Key the built-in serializes its image list under. */
  private static final String KEY_IMAGES = "images";

  public PortableSlideshowPattern(LX lx) {
    super(lx);
  }

  @Override
  public void load(LX lx, JsonObject obj) {
    JsonObject resolved = obj.deepCopy();
    forEachImage(resolved, image ->
      MediaPath.mapFileName(image, stored -> MediaPath.toAbsolute(lx, stored)));
    super.load(lx, resolved);
  }

  @Override
  public void save(LX lx, JsonObject obj) {
    super.save(lx, obj);
    forEachImage(obj, image ->
      MediaPath.mapFileName(image, absolute -> MediaPath.toRelative(lx, absolute)));
  }

  private static void forEachImage(JsonObject obj, java.util.function.Consumer<JsonObject> op) {
    if (!obj.has(KEY_IMAGES)) {
      return;
    }
    JsonArray images = obj.getAsJsonArray(KEY_IMAGES);
    for (JsonElement image : images) {
      if (image.isJsonObject()) {
        op.accept(image.getAsJsonObject());
      }
    }
  }
}
