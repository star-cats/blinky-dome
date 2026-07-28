package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.pattern.LXPattern;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

/**
 * Projects a bundled image onto the model.
 *
 * The image ships inside the jar as a classpath resource, right next to this
 * class — there is no file path to configure and nothing to install alongside
 * the package. Wherever the jar goes, the image goes.
 *
 * The mapping is the mirror image of SpatialRainbowPattern: instead of turning a
 * point's position into a hue, we turn it into a (u, v) texture coordinate and
 * read the color that's already there.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Image Test")
@LXComponent.Description("Projects a bundled image onto the model")
public class ImageTest extends LXPattern {

  /**
   * Resolved relative to this class's package, i.e.
   * com/starcats/blinkydome/eye_of_sauron.jpeg inside the jar.
   *
   * Note it deliberately does NOT live under a resources/images/ folder:
   * "images" is one of Chromatik's own media directory names, and a package
   * importing through the UI would copy that folder out into ~/Chromatik/. Kept
   * beside the class, it stays unambiguously a code resource.
   */
  private static final String IMAGE_RESOURCE = "eye_of_sauron.jpeg";

  private static final float TWO_PI = (float) (2 * Math.PI);
  private static final float PI = (float) Math.PI;

  /** Maps an LXPoint to one normalized texture axis. */
  private interface Coordinate {
    float get(LXPoint p);
  }

  public enum Mapping {
    /**
     * Flat projection onto the model's front face, as if a slide projector were
     * pointed at it. v is flipped because image row 0 is the top, while yn=1 is.
     */
    FRONT("Front", p -> p.xn, p -> 1 - p.yn),

    /** Same, viewed from the side. */
    SIDE("Side", p -> p.zn, p -> 1 - p.yn),

    /**
     * Wraps the image around the model: horizontally by azimuth, vertically by
     * elevation. On a dome this is the one that behaves like a planetarium.
     */
    SPHERICAL("Spherical", p -> p.azimuth / TWO_PI, p -> .5f - p.elevation / PI);

    private final String label;
    private final Coordinate uCoord;
    private final Coordinate vCoord;

    private Mapping(String label, Coordinate uCoord, Coordinate vCoord) {
      this.label = label;
      this.uCoord = uCoord;
      this.vCoord = vCoord;
    }

    public float u(LXPoint p) {
      return this.uCoord.get(p);
    }

    public float v(LXPoint p) {
      return this.vCoord.get(p);
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  public final EnumParameter<Mapping> mapping =
    new EnumParameter<Mapping>("Map", Mapping.SPHERICAL)
    .setDescription("How model position maps onto the image");

  public final CompoundParameter zoom =
    new CompoundParameter("Zoom", 1, .25, 4)
    .setDescription("Magnification; above 1 shows less of the image");

  public final CompoundParameter panX =
    new CompoundParameter("Pan X", 0, -1, 1)
    .setDescription("Horizontal offset, in image widths");

  public final CompoundParameter panY =
    new CompoundParameter("Pan Y", 0, -1, 1)
    .setDescription("Vertical offset, in image heights");

  public final CompoundParameter spin =
    new CompoundParameter("Spin", 0, -1, 1)
    .setDescription("Scroll the image horizontally, in widths per second");

  public final BooleanParameter wrap =
    new BooleanParameter("Wrap", true)
    .setDescription("Tile the image past its edges, rather than clamping them");

  public final CompoundParameter level =
    new CompoundParameter("Level", 100, 0, 100)
    .setUnits(LXParameter.Units.PERCENT)
    .setDescription("Brightness");

  /*
   * Decoded once at construction. We keep a flat ARGB int[] rather than the
   * BufferedImage because getRGB() per point per frame goes through the raster
   * and color model every call; an array read doesn't.
   */
  private final int[] pixels;
  private final int imageWidth;
  private final int imageHeight;

  /** Horizontal scroll offset, in image widths. */
  private double spinOffset = 0;

  public ImageTest(LX lx) {
    super(lx);
    addParameter("mapping", this.mapping);
    addParameter("zoom", this.zoom);
    addParameter("panX", this.panX);
    addParameter("panY", this.panY);
    addParameter("spin", this.spin);
    addParameter("wrap", this.wrap);
    addParameter("level", this.level);

    BufferedImage image = loadImage();
    if (image != null) {
      this.imageWidth = image.getWidth();
      this.imageHeight = image.getHeight();
      this.pixels = image.getRGB(0, 0, this.imageWidth, this.imageHeight, null, 0, this.imageWidth);
    } else {
      // Degrade to a blank pattern rather than throwing — an exception here
      // would surface in Chromatik as a broken, un-loadable device.
      this.imageWidth = 0;
      this.imageHeight = 0;
      this.pixels = null;
    }
  }

  /**
   * Reads the image out of the jar.
   *
   * getResourceAsStream() resolves against the classloader that loaded this
   * class — which, inside Chromatik, is the one it created for this package's
   * jar. That's what makes this work with no absolute path and no install step.
   */
  private BufferedImage loadImage() {
    try (InputStream stream = getClass().getResourceAsStream(IMAGE_RESOURCE)) {
      if (stream == null) {
        LX.error("ImageTest: resource not found in jar: " + IMAGE_RESOURCE);
        return null;
      }
      BufferedImage image = ImageIO.read(stream);
      if (image == null) {
        LX.error("ImageTest: no decoder for image resource: " + IMAGE_RESOURCE);
      }
      return image;
    } catch (IOException iox) {
      LX.error(iox, "ImageTest: failed reading image resource: " + IMAGE_RESOURCE);
      return null;
    }
  }

  @Override
  protected void run(double deltaMs) {
    if (this.pixels == null) {
      setColors(LXColor.BLACK);
      return;
    }

    final Mapping mapping = this.mapping.getEnum();
    final float zoom = this.zoom.getValuef();
    final float panX = this.panX.getValuef();
    final float panY = this.panY.getValuef();
    final boolean wrap = this.wrap.isOn();
    final float level = this.level.getValuef() * .01f;

    this.spinOffset += deltaMs * .001 * this.spin.getValue();
    this.spinOffset -= Math.floor(this.spinOffset);
    final float spin = (float) this.spinOffset;

    for (LXPoint p : model.points) {
      // Zoom about the center, then translate. Dividing by zoom shrinks the
      // sampled window, which magnifies what lands on the model.
      final float u = (mapping.u(p) - .5f) / zoom + .5f + panX + spin;
      final float v = (mapping.v(p) - .5f) / zoom + .5f + panY;

      colors[p.index] = LXColor.scaleBrightness(sample(u, v, wrap), level);
    }
  }

  /** Nearest-neighbor lookup. LEDs are far sparser than pixels, so filtering would only blur. */
  private int sample(float u, float v, boolean wrap) {
    int x = (int) (u * this.imageWidth);
    int y = (int) (v * this.imageHeight);
    if (wrap) {
      x = Math.floorMod(x, this.imageWidth);
      y = Math.floorMod(y, this.imageHeight);
    } else {
      if (x < 0 || x >= this.imageWidth || y < 0 || y >= this.imageHeight) {
        return LXColor.BLACK;
      }
    }
    return this.pixels[y * this.imageWidth + x];
  }
}
