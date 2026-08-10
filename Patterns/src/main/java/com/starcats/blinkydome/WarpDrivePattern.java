package com.starcats.blinkydome;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Curved, texture-mapped warp tunnel, ported from Scripts/WarpDrive.js.
 *
 * Each point casts a ray into a cylindrical tunnel. The tunnel centerline bends
 * with depth, so high X/Y Curvature moves the black far aperture off-center and
 * lets the near wall naturally occlude it. Manifold and Perlin are seamless:
 * they wrap around the wall and travel in opposite directions along its axis.
 *
 * No beat here. The tunnel is a continuous rush rather than a pulse, and the two
 * texture phases are integrated so that changing a speed knob changes the rate
 * without jumping the texture.
 *
 * The one real difference from the script: the textures resolve through
 * {@link LX#getMediaFile}, so there is no walking up from the working directory
 * hoping to find Images/. Java has the media root; the script did not.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Warp Drive")
@LXComponent.Description("Texture-mapped tunnel with a bending centerline")
public class WarpDrivePattern extends LXPattern {

  private static final String IMAGE_DIR = "Images/Noise";
  private static final String MANIFOLD_FILE = "Manifold_14-128x128.png";
  private static final String PERLIN_FILE = "Perlin_14-128x128.png";

  private static final double TAU = Math.PI * 2;
  private static final int MARCH_STEPS = 30;
  private static final int REFINE_STEPS = 5;

  public final CompoundParameter speedIn =
    new CompoundParameter("Speed In", .62, 0, 1)
    .setDescription("Manifold speed traveling into the tunnel");

  public final CompoundParameter speedOut =
    new CompoundParameter("Speed Out", .38, 0, 1)
    .setDescription("Perlin speed traveling out of the tunnel");

  public final CompoundParameter curveX =
    new CompoundParameter("X Curve", .5, 0, 1)
    .setDescription("Horizontal bend; center is straight, ends are -1 and +1");

  public final CompoundParameter curveY =
    new CompoundParameter("Y Curve", .5, 0, 1)
    .setDescription("Vertical bend; center is straight, ends are -1 and +1");

  public final CompoundParameter bend =
    new CompoundParameter("Bend", .72, 0, 1)
    .setDescription("Strength of the X/Y curvature and aperture occlusion");

  public final CompoundParameter iris =
    new CompoundParameter("Iris", .42, 0, 1)
    .setDescription("Apparent size of the perfectly black far aperture");

  public final CompoundParameter fov =
    new CompoundParameter("FOV", .45, 0, 1)
    .setDescription("Perspective spread from telephoto to wide angle");

  public final CompoundParameter length =
    new CompoundParameter("Length", .55, 0, 1)
    .setDescription("Texture repeats along the tunnel");

  public final CompoundParameter around =
    new CompoundParameter("Around", .32, 0, 1)
    .setDescription("Texture repeats around the tunnel wall");

  public final CompoundParameter detail =
    new CompoundParameter("Mix", .34, 0, 1)
    .setDescription("Perlin contribution over the Manifold texture");

  public final CompoundParameter manifoldContrast =
    new CompoundParameter("Man Con", .5, 0, 1)
    .setDescription("Manifold contrast multiplier; center is 1x");

  public final CompoundParameter manifoldLevel =
    new CompoundParameter("Man Lvl", .5, 0, 1)
    .setDescription("Manifold level multiplier: center is 1x, maximum is 4x");

  public final CompoundParameter perlinContrast =
    new CompoundParameter("Per Con", .5, 0, 1)
    .setDescription("Perlin contrast multiplier; center is 1x");

  public final CompoundParameter perlinLevel =
    new CompoundParameter("Per Lvl", .5, 0, 1)
    .setDescription("Perlin level multiplier: center is 1x, maximum is 4x");

  public final CompoundParameter twist =
    new CompoundParameter("Twist", .5, 0, 1)
    .setDescription("Spiral twist accumulated with tunnel depth");

  public final CompoundParameter rim =
    new CompoundParameter("Rim", .46, 0, 1)
    .setDescription("Thickness of the solid white band around the aperture");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", .59, 0, 1)
    .setDescription("Tunnel tint hue");

  public final CompoundParameter sat =
    new CompoundParameter("Sat", .24, 0, 1)
    .setDescription("Tunnel tint saturation; zero preserves grayscale");

  public final CompoundParameter level =
    new CompoundParameter("Level", .86, 0, 1)
    .setDescription("Overall tunnel brightness");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Correct the projection for a non-square model");

  /** A loaded texture: dimensions and a flat ARGB array. */
  private static final class Texture {
    final int w;
    final int h;
    final int[] px;

    Texture(BufferedImage image) {
      this.w = image.getWidth();
      this.h = image.getHeight();
      this.px = image.getRGB(0, 0, this.w, this.h, new int[this.w * this.h], 0, this.w);
    }
  }

  private Texture manifold;
  private Texture perlin;

  // Per-frame values. Keeping these here avoids recalculating knob mappings for
  // every LED, and integrating phases prevents speed changes from causing jumps.
  private double phaseIn = 0;
  private double phaseOut = 0;
  private double aspectX = 1;
  private double tunnelDepth = 3;
  private double raySpread = 1;
  private double bendX = 0;
  private double bendY = 0;
  private double axialRepeats = 3;
  private double circularRepeats = 1;
  private double twistRate = 0;
  private double manifoldContrastAmount = 1;
  private double manifoldLevelAmount = 1;
  private double perlinContrastAmount = 1;
  private double perlinLevelAmount = 1;

  public WarpDrivePattern(LX lx) {
    super(lx);
    addParameter("speedIn", this.speedIn);
    addParameter("speedOut", this.speedOut);
    addParameter("curveX", this.curveX);
    addParameter("curveY", this.curveY);
    addParameter("bend", this.bend);
    addParameter("iris", this.iris);
    addParameter("fov", this.fov);
    addParameter("length", this.length);
    addParameter("around", this.around);
    addParameter("detail", this.detail);
    addParameter("manifoldContrast", this.manifoldContrast);
    addParameter("manifoldLevel", this.manifoldLevel);
    addParameter("perlinContrast", this.perlinContrast);
    addParameter("perlinLevel", this.perlinLevel);
    addParameter("twist", this.twist);
    addParameter("rim", this.rim);
    addParameter("hue", this.hue);
    addParameter("sat", this.sat);
    addParameter("level", this.level);
    addParameter("autoAspect", this.autoAspect);

    this.manifold = loadTexture(lx, MANIFOLD_FILE);
    this.perlin = loadTexture(lx, PERLIN_FILE);
  }

  private static Texture loadTexture(LX lx, String name) {
    File file = lx.getMediaFile(IMAGE_DIR + "/" + name);
    try {
      BufferedImage image = ImageIO.read(file);
      if (image == null) {
        LX.error("WarpDrivePattern: could not read " + file);
        return null;
      }
      return new Texture(image);
    } catch (IOException iox) {
      LX.error(iox, "WarpDrivePattern: could not read " + file);
      return null;
    }
  }

  @Override
  protected void run(double deltaMs) {
    if (this.manifold == null || this.perlin == null) {
      setColors(LXColor.BLACK);
      return;
    }
    layout(deltaMs);
    draw();
  }

  private void layout(double deltaMs) {
    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, .25) : 0;
    this.phaseIn = wrap01(this.phaseIn + dt * lerp(.02, 1.8, this.speedIn.getValue()));
    this.phaseOut = wrap01(this.phaseOut + dt * lerp(.02, 1.8, this.speedOut.getValue()));

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    // A near end plane makes a large iris; a distant plane shrinks it.
    this.tunnelDepth = lerp(7.5, 1.35, this.iris.getValue());
    this.raySpread = lerp(.7, 1.65, this.fov.getValue());
    double bendStrength = lerp(0, 7.25, this.bend.getValue());
    this.bendX = (this.curveX.getValue() - .5) * 2 * bendStrength;
    this.bendY = (this.curveY.getValue() - .5) * 2 * bendStrength;
    // The old Length range was 0.7..8 repeats. In practice its useful ceiling
    // was about 18% (2.014 repeats), so that point is the top of the knob.
    this.axialRepeats = lerp(.175, 2.014, this.length.getValue());
    this.circularRepeats = lerp(.5, 4, this.around.getValue());
    this.twistRate = (this.twist.getValue() - .5) * 2.5;
    this.manifoldContrastAmount = Math.pow(4, (this.manifoldContrast.getValue() - .5) * 2);
    this.manifoldLevelAmount = levelMultiplier(this.manifoldLevel.getValue());
    this.perlinContrastAmount = Math.pow(4, (this.perlinContrast.getValue() - .5) * 2);
    this.perlinLevelAmount = levelMultiplier(this.perlinLevel.getValue());
  }

  private void draw() {
    final double rimWidth = lerp(.06, .38, this.rim.getValue());
    final double mix = this.detail.getValue();
    final double h = this.hue.getValue() * 360;
    final double s = this.sat.getValue() * 100;
    final double lvl = this.level.getValue();

    for (LXPoint p : this.model.points) {
      // Ray position at depth z is (screenX * z, screenY * z). Tunnel radius is
      // one world unit, and its centerline follows a quadratic bend.
      double screenX = (p.xn - .5) * 2 * this.aspectX * this.raySpread;
      double screenY = (p.yn - .5) * 2 * this.raySpread;
      double previousZ = 0;
      double hitZ = -1;

      for (int i = 1; i <= MARCH_STEPS; ++i) {
        double z = this.tunnelDepth * i / MARCH_STEPS;
        if (wallFunction(screenX, screenY, z) >= 0) {
          double low = previousZ;
          double high = z;
          for (int j = 0; j < REFINE_STEPS; ++j) {
            double middle = (low + high) * .5;
            if (wallFunction(screenX, screenY, middle) >= 0) {
              high = middle;
            } else {
              low = middle;
            }
          }
          hitZ = (low + high) * .5;
          break;
        }
        previousZ = z;
      }

      // A ray that reaches the far end is inside the aperture. It is deliberately
      // absolute black, independent of Level, tint, texture, or rim controls.
      if (hitZ < 0) {
        this.colors[p.index] = LXColor.BLACK;
        continue;
      }

      double progress = hitZ / this.tunnelDepth;

      // A hard, unshaded white collar separates the textured wall from the black
      // aperture. It deliberately bypasses every color and brightness control.
      if (progress >= 1 - rimWidth) {
        this.colors[p.index] = LXColor.WHITE;
        continue;
      }

      double centerX = this.bendX * progress * progress;
      double centerY = this.bendY * progress * progress;
      double localX = screenX * hitZ - centerX;
      double localY = screenY * hitZ - centerY;
      double angle = Math.atan2(localY, localX) / TAU;

      // Increasing z points into the page. These signs make Manifold move deeper
      // while Perlin moves toward the viewer, without changing their spatial map.
      double u = angle * this.circularRepeats + this.twistRate * progress;
      double axial = hitZ * this.axialRepeats;
      double manifoldValue = sampleGray(this.manifold, u, axial - this.phaseIn);
      double perlinValue = sampleGray(this.perlin, u, axial + this.phaseOut);

      manifoldValue = clamp((manifoldValue - .5) * this.manifoldContrastAmount + .5, 0, 1)
        * this.manifoldLevelAmount;
      perlinValue = clamp((perlinValue - .5) * this.perlinContrastAmount + .5, 0, 1)
        * this.perlinLevelAmount;

      double value = clamp(lerp(manifoldValue, perlinValue, mix), 0, 1);
      double depthShade = lerp(.58, 1, progress);
      double brightness = clamp(value * depthShade, 0, 1);
      this.colors[p.index] = LXColor.hsb(h, s, brightness * lvl * 100);
    }
  }

  /** Signed squared distance from a ray point to the bent unit-radius wall. */
  private double wallFunction(double screenX, double screenY, double z) {
    double progress = z / this.tunnelDepth;
    double centerX = this.bendX * progress * progress;
    double centerY = this.bendY * progress * progress;
    double dx = screenX * z - centerX;
    double dy = screenY * z - centerY;
    return dx * dx + dy * dy - 1;
  }

  /** Bilinear grayscale sampling with seamless wrapping on both axes. */
  private static double sampleGray(Texture texture, double u, double v) {
    u = wrap01(u);
    v = wrap01(v);
    double x = u * texture.w;
    double y = v * texture.h;
    int x0 = (int) Math.floor(x) % texture.w;
    int y0 = (int) Math.floor(y) % texture.h;
    int x1 = (x0 + 1) % texture.w;
    int y1 = (y0 + 1) % texture.h;
    double tx = x - Math.floor(x);
    double ty = y - Math.floor(y);
    double a = grayOf(texture.px[y0 * texture.w + x0]);
    double b = grayOf(texture.px[y0 * texture.w + x1]);
    double c = grayOf(texture.px[y1 * texture.w + x0]);
    double d = grayOf(texture.px[y1 * texture.w + x1]);
    return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
  }

  private static double grayOf(int argb) {
    int r = (argb >> 16) & 0xff;
    int g = (argb >> 8) & 0xff;
    int b = argb & 0xff;
    return (r * .2126 + g * .7152 + b * .0722) / 255;
  }

  private static double wrap01(double value) {
    return value - Math.floor(value);
  }

  /** 0 at the bottom, 1x at center, and an expanded 4x ceiling. */
  private static double levelMultiplier(double value) {
    return (value <= .5) ? value * 2 : 1 + (value - .5) * 6;
  }

  private static double clamp(double v, double min, double max) {
    return (v < min) ? min : (v > max) ? max : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
