package com.starcats.blinkydome;

import com.google.gson.JsonObject;

import heronarts.glx.GLXUtils;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.pattern.image.ImagePattern;
import heronarts.lx.pattern.image.ImagePattern.ImageMode;
import heronarts.lx.transform.LXMatrix;
import heronarts.lx.utils.LXUtils;

/**
 * Chromatik's image pattern, with a file reference that survives moving the repo.
 *
 * Everything the built-in does, this does — it *is* the built-in, subclassed.
 * The projection matrix, the GIF playback, even the device UI come along
 * unchanged: Chromatik picks a pattern's UI by walking its registry with
 * {@code Class.isInstance()}, so a subclass inherits UIImagePattern's file
 * picker and preview rather than falling back to a wall of knobs.
 *
 * Serialization is overridden for portable paths, and Mirror rendering is
 * corrected so transformed and scrolled overflow reflects without seams. See
 * {@link MediaPath} for why the stored path has to differ from the live one.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Image (Portable)")
@LXComponent.Description("Image pattern that stores its file reference relative to the media folder")
public class PortableImagePattern extends ImagePattern {

  /** Key the built-in registers its Image child under. */
  private static final String KEY_IMAGE = "image";

  private final LXMatrix mirrorMatrix = new LXMatrix();

  public PortableImagePattern(LX lx) {
    super(lx);
  }

  @Override
  protected void run(double deltaMs) {
    if (this.image.imageMode.getEnum() != ImageMode.MIRROR) {
      super.run(deltaMs);
      return;
    }

    this.image.animateGif(deltaMs);
    renderMirror(this.model, this.colors);
  }

  /**
   * Correct mirror wrapping for both transformed coordinates and scrolling.
   *
   * Chromatik 1.2.1 folds the transformed coordinate first, then adds Scroll
   * and applies {@code % 1}. That last operation turns every scrolling overflow
   * into a tile and maps the exact far edge (1) onto the opposite edge (0).
   * Here scrolling participates in the mirror fold itself. Since Scroll is a
   * wrappable 0..1 parameter and a mirrored repeat is two image widths long, a
   * full turn travels two widths and returns continuously to its starting edge.
   */
  private void renderMirror(LXModel model, int[] colors) {
    if (!this.image.hasImage()) {
      int background = this.image.backgroundMode.getEnum().color;
      for (LXPoint point : model.points) {
        colors[point.index] = background;
      }
      return;
    }

    computeMirrorMatrix(model);
    GLXUtils.Image source = this.image.getImage();
    float scrollX = 2 * this.image.scrollX.getValuef();
    float scrollY = 2 * this.image.scrollY.getValuef();

    for (LXPoint point : model.points) {
      float modelY = 1 - point.yn;
      float u =
        point.xn * this.mirrorMatrix.m11 +
        modelY * this.mirrorMatrix.m12 +
        point.zn * this.mirrorMatrix.m13 +
        this.mirrorMatrix.m14 - scrollX;
      float v =
        point.xn * this.mirrorMatrix.m21 +
        modelY * this.mirrorMatrix.m22 +
        point.zn * this.mirrorMatrix.m23 +
        this.mirrorMatrix.m24 - scrollY;

      colors[point.index] = source.getNormalized(mirrorCoordinate(u), mirrorCoordinate(v));
    }
  }

  /** Triangle-wave fold into [0, 1], correct at positive and negative edges. */
  static float mirrorCoordinate(float value) {
    float cell = (float) Math.floor(value);
    float fraction = value - cell;
    return ((((long) cell) & 1L) == 0L) ? fraction : 1 - fraction;
  }

  /**
   * Reproduce the built-in image projection matrix so every existing transform
   * behaves identically; only coordinate wrapping differs in Mirror mode.
   */
  private void computeMirrorMatrix(LXModel model) {
    float translateX = this.image.translateX.getValuef();
    float translateY = this.image.translateY.getValuef();
    float translateZ = this.image.translateZ.getValuef();
    float imageAspect = this.image.getImage().getAspectRatio();
    float modelAspect = model.xRange / model.yRange;
    float stretchAspect = this.image.stretchAspect.getValuef();

    float aspectScaleX = (imageAspect > modelAspect) ? 1 :
      LXUtils.lerpf(1, modelAspect / imageAspect, stretchAspect);
    float aspectScaleY = (imageAspect < modelAspect) ? 1 :
      LXUtils.lerpf(1, imageAspect / modelAspect, stretchAspect);
    float scale = this.image.scale.getValuef() * this.image.scaleRange.getValuef();

    this.mirrorMatrix
      .identity()
      .translate(.5f, .5f, .5f)
      .scale(
        aspectScaleX * this.image.stretchX.getValuef() /
          LXUtils.maxf(1e-4f, scale * this.image.scaleX.getValuef()),
        aspectScaleY * this.image.stretchY.getValuef() /
          LXUtils.maxf(1e-4f, scale * this.image.scaleY.getValuef()),
        1)
      .rotateZ(this.image.roll.getValuef() * (float) Math.PI / 180)
      .rotateX(this.image.pitch.getValuef() * (float) Math.PI / 180)
      .rotateY(this.image.yaw.getValuef() * (float) Math.PI / 180)
      .translate(-.5f - translateX, -.5f + translateY, -.5f - translateZ);
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
