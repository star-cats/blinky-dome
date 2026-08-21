package com.starcats.blinkydome;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameterListener;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Matrix rain: copies of one character falling down the model.
 *
 * Recovered from Packages/UnicodeCharPattern.jar, originally by Ben Rotter. That
 * jar was committed without its source, so this is the behavior read back out of
 * the bytecode and written up — same knobs, same defaults, same arithmetic.
 *
 * The character is drawn once, by Java2D, into an alpha mask and cached. Only
 * the alpha channel is kept: the glyph is a stencil, and the color comes from
 * the Hue/Sat/Brt knobs at render, so recoloring never costs a re-raster. The
 * mask is re-cut only when the character, the font, the weight or AutoFont
 * changes — which is what makes an arbitrary Unicode code point as cheap to
 * render as a rectangle, and is the whole reason this beats shipping images.
 *
 * The raster is drawn at a fixed 200px and then cropped to the glyph's own ink,
 * not to the font's metrics box. Fonts pad wildly and inconsistently around a
 * glyph — an emoji and a Latin digit in the same face do not agree on where the
 * character sits inside its cell — so trimming to the pixels that are actually
 * marked is what makes Size mean the same thing for every character.
 *
 * Each falling copy owns an x, a y and a speed. Reaching the far edge is not a
 * wrap: the copy is re-randomized in x and re-rolled for speed, so the rain does
 * not settle into visible columns after the first pass down the model.
 *
 * Sampling is per point rather than per glyph-pixel, and takes the brightest
 * copy covering it. That keeps overlapping copies at glyph brightness instead of
 * summing into a blown-out smear where two happen to cross.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Falling Unicode")
@LXComponent.Description("Matrix rain of any Unicode character, rasterized from an installed font")
public class FallingUnicodePattern extends LXPattern {

  /** Every font family this JVM can see, sorted so binarySearch works. */
  private static final String[] FONT_NAMES = loadFontFamilies();

  /** Point size the glyph is rasterized at before being scaled to the model. */
  private static final int RASTER_HEIGHT = 200;

  /** Blank margin around the drawn glyph, in raster pixels, before cropping. */
  private static final int RASTER_PADDING = 8;

  /**
   * Alpha above which a raster pixel counts as ink when finding the crop box.
   *
   * Not zero: antialiasing leaves a haze of 1s and 2s well outside the glyph,
   * and cropping to that would pad every character by a few pixels of nothing
   * and shrink the visible mark.
   */
  private static final int INK_THRESHOLD = 4;

  public final StringParameter character =
    new StringParameter("Character", "0")
    .setDescription("Character (or short string) to display");

  public final StringParameter fontName =
    new StringParameter("Font", FONT_NAMES[defaultFontIndex()])
    .setDescription("Font family. Type the exact name, or step with < / >.");

  public final TriggerParameter prevFont =
    new TriggerParameter("<", () -> stepFont(-1))
    .setDescription("Previous installed font");

  public final TriggerParameter nextFont =
    new TriggerParameter(">", () -> stepFont(1))
    .setDescription("Next installed font");

  public final BooleanParameter bold =
    new BooleanParameter("Bold", false)
    .setDescription("Bold weight");

  public final BooleanParameter autoFont =
    new BooleanParameter("AutoFont", true)
    .setDescription("If the picked font can't render the character, silently try known cross-OS fallbacks.");

  public final BooleanParameter flipX =
    new BooleanParameter("FlipX", true)
    .setDescription("Mirror left/right (default on for Chromatik's default camera)");

  public final BooleanParameter flipY =
    new BooleanParameter("FlipY", false)
    .setDescription("Mirror top/bottom");

  public final DiscreteParameter count =
    new DiscreteParameter("Count", 12, 1, 129)
    .setDescription("Number of falling copies (1..128)");

  public final CompoundParameter size =
    new CompoundParameter("Size", .15, .01, 1)
    .setDescription("Character height as fraction of the model");

  public final CompoundParameter aspect =
    new CompoundParameter("Aspect", 1, .25, 4)
    .setDescription("Width stretch (1.0 = font's native aspect)");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .35, 0, 3)
    .setDescription("Base fall speed (model heights per second)");

  public final CompoundParameter variance =
    new CompoundParameter("Var", .5, 0, 1)
    .setDescription("Per-copy speed variance (0 = all same speed, 1 = 0..2x base)");

  public final BooleanParameter reverse =
    new BooleanParameter("Up", false)
    .setDescription("Fall up instead of down");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 120, 0, 360)
    .setDescription("Foreground hue");

  public final CompoundParameter sat =
    new CompoundParameter("Sat", 100, 0, 100)
    .setDescription("Foreground saturation");

  public final CompoundParameter brt =
    new CompoundParameter("Brt", 100, 0, 100)
    .setDescription("Foreground brightness");

  public final CompoundParameter bg =
    new CompoundParameter("BG", 0, 0, 100)
    .setDescription("Background brightness");

  public final CompoundParameter threshold =
    new CompoundParameter("Thresh", 0, 0, 1)
    .setDescription("0 = smooth alpha-blend; >0 = hard cutoff");

  public final TriggerParameter reseed =
    new TriggerParameter("Reseed", this::reseed)
    .setDescription("Regenerate per-copy X and speed");

  // The cached stencil, and the inputs it was cut for. A null mask means the
  // character is empty or the font drew nothing, and the pattern is background.
  private String cachedText = null;
  private String cachedFont = null;
  private boolean cachedBold = false;
  private byte[] cachedAlpha = null;
  private int cachedW = 0;
  private int cachedH = 0;

  private final Random rng = new Random();

  // One entry per falling copy. Resized by reseed, which the Count knob fires.
  private double[] copyX = new double[0];
  private double[] copyY = new double[0];
  private double[] copySpeed = new double[0];

  public FallingUnicodePattern(LX lx) {
    super(lx);
    addParameter("character", this.character);
    addParameter("fontName", this.fontName);
    addParameter("prevFont", this.prevFont);
    addParameter("nextFont", this.nextFont);
    addParameter("bold", this.bold);
    addParameter("autoFont", this.autoFont);
    addParameter("flipX", this.flipX);
    addParameter("flipY", this.flipY);
    addParameter("count", this.count);
    addParameter("size", this.size);
    addParameter("aspect", this.aspect);
    addParameter("speed", this.speed);
    addParameter("variance", this.variance);
    addParameter("reverse", this.reverse);
    addParameter("hue", this.hue);
    addParameter("sat", this.sat);
    addParameter("brt", this.brt);
    addParameter("bg", this.bg);
    addParameter("threshold", this.threshold);
    addParameter("reseed", this.reseed);

    // Anything that changes what the glyph looks like drops the cache; the next
    // frame re-cuts it. Doing the raster here instead would run it on the UI
    // thread, and on a font that has to be loaded from disk that is a stall.
    LXParameterListener invalidate = p -> this.cachedText = null;
    this.character.addListener(invalidate);
    this.fontName.addListener(invalidate);
    this.bold.addListener(invalidate);
    this.autoFont.addListener(invalidate);

    this.count.addListener(p -> reseed());
    reseed();
  }

  private static String[] loadFontFamilies() {
    try {
      String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .getAvailableFontFamilyNames();
      Arrays.sort(names);
      return names;
    } catch (Throwable x) {
      // Headless or a locked-down graphics environment. The three logical names
      // are guaranteed by the JVM itself, so the pattern still runs.
      return new String[] { "SansSerif", "Serif", "Monospaced" };
    }
  }

  /** First installed monospace face from a short preference list, else index 0. */
  private static int defaultFontIndex() {
    for (String preferred : new String[] { "Consolas", "Cascadia Mono", "Courier New", "Monospaced" }) {
      int index = Arrays.binarySearch(FONT_NAMES, preferred);
      if (index >= 0) {
        return index;
      }
    }
    return 0;
  }

  /** Step the font selection, wrapping at either end of the installed list. */
  private void stepFont(int delta) {
    String current = this.fontName.getString();
    int index = -1;
    for (int i = 0; i < FONT_NAMES.length; ++i) {
      if (FONT_NAMES[i].equalsIgnoreCase(current)) {
        index = i;
        break;
      }
    }
    if (index < 0) {
      // Typed a name that is not installed; step from the default rather than
      // refusing to move.
      index = defaultFontIndex();
    }
    this.fontName.setValue(FONT_NAMES[Math.floorMod(index + delta, FONT_NAMES.length)]);
  }

  private String currentFontName() {
    String name = this.fontName.getString();
    return (name == null || name.isEmpty()) ? "SansSerif" : name;
  }

  /**
   * Give every copy a new column and a new speed.
   *
   * Synchronized because Count fires this from the UI thread while the engine
   * thread is walking the same three arrays.
   */
  private synchronized void reseed() {
    int n = this.count.getValuei();
    this.copyX = new double[n];
    this.copyY = new double[n];
    this.copySpeed = new double[n];
    for (int i = 0; i < n; ++i) {
      this.copyX[i] = this.rng.nextDouble();
      this.copyY[i] = this.rng.nextDouble();
      this.copySpeed[i] = randomSpeed();
    }
  }

  /** Base speed, spread by Var: at 1 a copy runs anywhere from 0 to 2x base. */
  private double randomSpeed() {
    return this.speed.getValue()
      * (1 + this.variance.getValue() * (this.rng.nextDouble() * 2 - 1));
  }

  /** Re-cut the stencil if anything it depends on has changed. */
  private void ensureRaster() {
    String text = this.character.getString();
    if (text == null) {
      text = "";
    }
    String font = currentFontName();
    boolean isBold = this.bold.isOn();

    if (text.equals(this.cachedText) && font.equals(this.cachedFont)
        && isBold == this.cachedBold) {
      return;
    }
    this.cachedText = text;
    this.cachedFont = font;
    this.cachedBold = isBold;
    rasterize(text, font, isBold);
  }

  /**
   * Draw the string once and keep its alpha channel, cropped to the ink.
   *
   * Any failure here — a font that will not load, a headless environment, an
   * out-of-memory raster — leaves a null mask and the pattern renders as flat
   * background. A pattern that throws every frame is a wall of log spam and a
   * dead channel either way.
   */
  private void rasterize(String text, String fontFamily, boolean isBold) {
    if (text == null || text.isEmpty()) {
      clearRaster();
      return;
    }

    try {
      Font font = FontFallback.pick(fontFamily, text, isBold ? Font.BOLD : Font.PLAIN,
        RASTER_HEIGHT, this.autoFont.isOn());

      // Measure first, on a throwaway 1x1, because the image has to be big
      // enough for the string before there is a string drawn to measure.
      BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
      Graphics2D probeGfx = probe.createGraphics();
      probeGfx.setFont(font);
      FontMetrics metrics = probeGfx.getFontMetrics();
      int textWidth = metrics.stringWidth(text);
      int ascent = metrics.getAscent();
      int descent = metrics.getDescent();
      probeGfx.dispose();

      int width = Math.max(1, textWidth) + 2 * RASTER_PADDING;
      int height = ascent + descent + 2 * RASTER_PADDING;

      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
      Graphics2D gfx = image.createGraphics();
      gfx.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      gfx.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
        RenderingHints.VALUE_ANTIALIAS_ON);
      gfx.setColor(Color.WHITE);
      gfx.setFont(font);
      gfx.drawString(text, RASTER_PADDING, RASTER_PADDING + ascent);
      gfx.dispose();

      int[] argb = image.getRGB(0, 0, width, height, null, 0, width);

      // The glyph's own bounding box, in ink rather than in font metrics.
      int minX = width;
      int maxX = -1;
      int minY = height;
      int maxY = -1;
      for (int y = 0; y < height; ++y) {
        int row = y * width;
        for (int x = 0; x < width; ++x) {
          if (((argb[row + x] >>> 24) & 0xff) <= INK_THRESHOLD) {
            continue;
          }
          if (x < minX) {
            minX = x;
          }
          if (x > maxX) {
            maxX = x;
          }
          if (y < minY) {
            minY = y;
          }
          if (y > maxY) {
            maxY = y;
          }
        }
      }

      if (maxX < 0) {
        // The font drew nothing at all — a space, or a glyph it does not have
        // and declined to box.
        clearRaster();
        return;
      }

      int cropW = maxX - minX + 1;
      int cropH = maxY - minY + 1;
      byte[] alpha = new byte[cropW * cropH];
      for (int y = 0; y < cropH; ++y) {
        int src = (minY + y) * width + minX;
        int dst = y * cropW;
        for (int x = 0; x < cropW; ++x) {
          alpha[dst + x] = (byte) ((argb[src + x] >>> 24) & 0xff);
        }
      }

      this.cachedAlpha = alpha;
      this.cachedW = cropW;
      this.cachedH = cropH;
    } catch (Throwable x) {
      clearRaster();
    }
  }

  private void clearRaster() {
    this.cachedAlpha = null;
    this.cachedW = 0;
    this.cachedH = 0;
  }

  @Override
  protected void run(double deltaMs) {
    ensureRaster();

    final double hueValue = this.hue.getValue();
    final double satValue = this.sat.getValue();
    final double brtValue = this.brt.getValue();
    final double bgValue = this.bg.getValue();

    int background = LXColor.hsb(hueValue, satValue, bgValue);
    for (LXPoint p : this.model.points) {
      this.colors[p.index] = background;
    }

    if (this.cachedAlpha == null || this.cachedW == 0 || this.cachedH == 0) {
      return;
    }

    // The glyph's box on the model. Height is Size directly; width follows the
    // raster's own proportions, stretched by Aspect.
    double glyphAspect = (double) this.cachedW / this.cachedH;
    double glyphH = Math.max(.001, this.size.getValue());
    double glyphW = glyphH * glyphAspect * this.aspect.getValue();
    double halfW = glyphW / 2;
    double halfH = glyphH / 2;

    int copies = this.copyX.length;
    double dt = deltaMs / 1000.;
    double direction = this.reverse.isOn() ? -1 : 1;

    // Recycle a whole glyph-height past the edge, so a copy is fully gone before
    // it is moved rather than blinking out mid-character.
    double bottomLimit = -halfH;
    double topLimit = 1 + halfH;

    for (int i = 0; i < copies; ++i) {
      this.copyY[i] -= direction * this.copySpeed[i] * dt;
      if (direction > 0) {
        if (this.copyY[i] < bottomLimit) {
          this.copyY[i] = topLimit;
          this.copyX[i] = this.rng.nextDouble();
          this.copySpeed[i] = randomSpeed();
        }
      } else if (this.copyY[i] > topLimit) {
        this.copyY[i] = bottomLimit;
        this.copyX[i] = this.rng.nextDouble();
        this.copySpeed[i] = randomSpeed();
      }
    }

    final boolean mirrorX = this.flipX.isOn();
    final boolean mirrorY = this.flipY.isOn();
    final double cutoff = this.threshold.getValue();
    final int rasterW = this.cachedW;
    final int rasterH = this.cachedH;
    final double invGlyphW = 1 / glyphW;
    final double invGlyphH = 1 / glyphH;
    final byte[] alpha = this.cachedAlpha;

    for (LXPoint p : this.model.points) {
      double xn = p.xn;
      double yn = p.yn;

      // Brightest copy wins, so two overlapping glyphs stay glyph-bright rather
      // than summing into a blown-out patch. Negative means no copy covered it.
      double best = -1;

      for (int i = 0; i < copies; ++i) {
        double cx = this.copyX[i];
        double cy = this.copyY[i];
        if (xn < cx - halfW || xn > cx + halfW || yn < cy - halfH || yn > cy + halfH) {
          continue;
        }

        double u = (xn - (cx - halfW)) * invGlyphW;
        // Raster rows run downward and yn runs upward, so v is inverted here.
        double v = 1 - (yn - (cy - halfH)) * invGlyphH;
        if (mirrorX) {
          u = 1 - u;
        }
        if (mirrorY) {
          v = 1 - v;
        }

        int col = (int) (u * rasterW);
        int row = (int) (v * rasterH);
        if (col < 0) {
          col = 0;
        } else if (col >= rasterW) {
          col = rasterW - 1;
        }
        if (row < 0) {
          row = 0;
        } else if (row >= rasterH) {
          row = rasterH - 1;
        }

        double a = (alpha[row * rasterW + col] & 0xff) / 255.;
        if (a > best) {
          best = a;
        }
      }

      if (best < 0) {
        continue;
      }

      if (cutoff > 0) {
        // Hard cutoff: the glyph is on or off, with no antialiased fringe. On a
        // coarse fixture that reads sharper than a blend does.
        if (best > cutoff) {
          this.colors[p.index] = LXColor.hsb(hueValue, satValue, brtValue);
        }
      } else {
        this.colors[p.index] =
          LXColor.hsb(hueValue, satValue, bgValue + (brtValue - bgValue) * best);
      }
    }
  }
}
