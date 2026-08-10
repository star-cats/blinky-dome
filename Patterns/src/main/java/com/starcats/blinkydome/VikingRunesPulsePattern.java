package com.starcats.blinkydome;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.imageio.ImageIO;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Pulses a random glyph from the 6x6 Viking bind-rune atlas on every beat,
 * ported from Scripts/VikingRunesPulse120.js.
 *
 * Each pulse grows with an exponential ease-out while its opacity follows the
 * complementary decay, and pulses overlap: a rune is still fading when the next
 * one starts, so the beats visibly stack rather than replacing one another.
 *
 * The script fixed the tempo at 120 BPM and indexed pulses off wall time. Here
 * the beat grid comes from the {@link PrimaryController} through a
 * {@link PrimaryController.Follower}, so a pulse starts on the show's beat and
 * the pattern free-runs at Free BPM when nothing is driving it. Pulses are still
 * indexed — by beat number now instead of by millisecond bucket — which is what
 * lets a given beat keep the same glyph for as long as its tail is alive.
 *
 * Duration is expressed in beats rather than milliseconds for the same reason:
 * at 174 BPM a fixed one-second tail would stack six deep and turn to mush.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Viking Runes")
@LXComponent.Description("A bind-rune pulses and fades on every beat")
public class VikingRunesPulsePattern extends LXPattern {

  private static final String ATLAS_PATH = "Images/Glyphs/VikingRunes.png";

  private static final int ATLAS_COLUMNS = 6;
  private static final int ATLAS_ROWS = 6;
  private static final int GLYPH_COUNT = ATLAS_COLUMNS * ATLAS_ROWS;

  /** Shape of the opacity decay; larger is a sharper initial fall. */
  private static final double EXP_DECAY = 3.4;

  /** Sampling margin, in atlas pixels, that keeps the antialiased fringe. */
  private static final int GLYPH_MARGIN = 2;

  public final CompoundParameter size =
    new CompoundParameter("Size", .75, .05, 2)
    .setDescription("Initial glyph size as a fraction of the screen");

  public final CompoundParameter growth =
    new CompoundParameter("Growth", .25, 0, 2)
    .setDescription("Fractional size increase over each pulse");

  public final CompoundParameter duration =
    new CompoundParameter("Length", 2, .25, 8)
    .setDescription("How long one pulse lasts, in beats; over 1 they overlap");

  public final CompoundParameter phase =
    new CompoundParameter("Phase", 0, -.5, .5)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Slide the pulse earlier or later against the beat grid, in beats");

  public final BoundedParameter fallbackBpm =
    new BoundedParameter("Free BPM", 120, 40, 200)
    .setDescription("Tempo to pulse at when there is no controller, or before it has found one");

  public final CompoundParameter sync =
    new CompoundParameter("Sync", 1, .1, 10)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("How long the pulses take to drift back onto the controller's beat grid; they never snap");

  /** One atlas cell: its bounds, and the tight box around the ink inside it. */
  private static final class Glyph {
    int cellLeft, cellTop, cellRight, cellBottom;
    double centerX, centerY, span;
  }

  /** One pulse being drawn this frame. */
  private static final class Pulse {
    Glyph glyph;
    double alpha;
    double size;
  }

  private int atlasWidth = 0;
  private int atlasHeight = 0;
  private int[] atlasPixels = null;
  private Glyph[] glyphs = new Glyph[0];

  private final PrimaryController.Follower clock = new PrimaryController.Follower();
  private final Random random = new Random();

  /** Which glyph each beat drew, kept only while that beat's tail can be seen. */
  private final Map<Long, Integer> glyphForBeat = new HashMap<Long, Integer>();

  private final List<Pulse> active = new ArrayList<Pulse>();

  public VikingRunesPulsePattern(LX lx) {
    super(lx);
    addParameter("size", this.size);
    addParameter("growth", this.growth);
    addParameter("duration", this.duration);
    addParameter("phase", this.phase);
    addParameter("fallbackBpm", this.fallbackBpm);
    addParameter("sync", this.sync);
    loadAtlas(lx);
  }

  private void loadAtlas(LX lx) {
    File file = lx.getMediaFile(ATLAS_PATH);
    BufferedImage atlas = null;
    try {
      atlas = ImageIO.read(file);
    } catch (IOException iox) {
      LX.error(iox, "VikingRunesPulsePattern: could not read " + file);
      return;
    }
    if (atlas == null) {
      LX.error("VikingRunesPulsePattern: could not read " + file);
      return;
    }
    this.atlasWidth = atlas.getWidth();
    this.atlasHeight = atlas.getHeight();
    this.atlasPixels = atlas.getRGB(0, 0, this.atlasWidth, this.atlasHeight,
      new int[this.atlasWidth * this.atlasHeight], 0, this.atlasWidth);
    this.glyphs = findGlyphBounds();
  }

  @Override
  protected void onActive() {
    // Nothing carried over from the last time this was up: a rune left half
    // faded would pop back in at whatever opacity it had when the pattern went
    // away, possibly minutes ago.
    this.glyphForBeat.clear();
    this.active.clear();
  }

  @Override
  protected void run(double deltaMs) {
    this.clock.loop(deltaMs, this.fallbackBpm.getValue(), this.sync.getValue());
    layout();
    draw();
  }

  private void layout() {
    this.active.clear();
    if (this.atlasPixels == null) {
      return;
    }

    double beats = this.clock.getBeats() + this.phase.getValue();
    long currentBeat = (long) Math.floor(beats);
    double lengthBeats = this.duration.getValue();
    double decayAtEnd = Math.exp(-EXP_DECAY);
    double baseSize = Math.max(.01, this.size.getValue());
    double grow = this.growth.getValue();

    // Every beat whose tail can still be on screen, oldest first so that each
    // new glyph is picked knowing what the beat before it chose.
    int span = (int) Math.ceil(lengthBeats) + 1;
    for (int age = span; age >= 0; --age) {
      long beat = currentBeat - age;
      double elapsed = beats - beat;
      if (elapsed < 0 || elapsed >= lengthBeats) {
        continue;
      }

      double progress = clamp(elapsed / lengthBeats);
      // Normalized so the tail reaches exactly zero at the end of the pulse
      // rather than being cut off part way down.
      double alpha = (Math.exp(-EXP_DECAY * progress) - decayAtEnd) / (1 - decayAtEnd);

      Pulse pulse = new Pulse();
      pulse.glyph = this.glyphs[glyphForBeat(beat)];
      pulse.alpha = alpha;
      pulse.size = baseSize * (1 + grow * (1 - alpha));
      this.active.add(pulse);
    }

    pruneHistory(currentBeat - span - 1);
  }

  private void draw() {
    if (this.active.isEmpty()) {
      setColors(LXColor.BLACK);
      return;
    }

    for (LXPoint p : this.model.points) {
      double dx = p.xn - .5;
      double dy = p.yn - .5;
      double remaining = 1;

      for (int i = 0; i < this.active.size(); ++i) {
        Pulse pulse = this.active.get(i);
        double halfSize = pulse.size * .5;
        if (Math.abs(dx) > halfSize || Math.abs(dy) > halfSize) {
          continue;
        }

        // A square source region around the tight glyph bounds, so Size tracks
        // the visible rune while preserving its original aspect ratio. Y is
        // flipped because image rows run down from the top.
        double sourceX = pulse.glyph.centerX + (dx / pulse.size) * pulse.glyph.span;
        double sourceY = pulse.glyph.centerY - (dy / pulse.size) * pulse.glyph.span;
        double layerAlpha = clamp(sampleAlpha(sourceX, sourceY, pulse.glyph) * pulse.alpha);

        // Alpha-over for same-coloured white layers, accumulated as the
        // transparency that survives all of them.
        remaining *= 1 - layerAlpha;
      }

      double alpha = 1 - remaining;
      this.colors[p.index] = (alpha <= 0)
        ? LXColor.BLACK
        : LXColor.rgba(255, 255, 255, (int) Math.round(255 * alpha));
    }
  }

  /**
   * One stable glyph per beat, remembered for as long as that beat is visible.
   *
   * Redrawing it every frame would make the rune flicker through the whole
   * atlas; picking it once and keeping it is what makes a pulse one rune.
   */
  private int glyphForBeat(long beat) {
    Integer existing = this.glyphForBeat.get(beat);
    if (existing != null) {
      return existing.intValue();
    }

    // Never the same rune twice running: two identical pulses overlapping read
    // as one rune getting brighter, which loses the beat entirely.
    Integer previous = this.glyphForBeat.get(beat - 1);
    int next = this.random.nextInt(GLYPH_COUNT);
    if (previous != null && GLYPH_COUNT > 1) {
      while (next == previous.intValue()) {
        next = this.random.nextInt(GLYPH_COUNT);
      }
    }
    this.glyphForBeat.put(beat, next);
    return next;
  }

  private void pruneHistory(long oldestNeeded) {
    this.glyphForBeat.keySet().removeIf(beat -> beat < oldestNeeded);
  }

  /** Find each cell's nontransparent pixels, then add a small sampling margin. */
  private Glyph[] findGlyphBounds() {
    Glyph[] bounds = new Glyph[GLYPH_COUNT];

    for (int row = 0; row < ATLAS_ROWS; ++row) {
      int cellTop = Math.round((row * this.atlasHeight) / (float) ATLAS_ROWS);
      int cellBottom = Math.round(((row + 1) * this.atlasHeight) / (float) ATLAS_ROWS) - 1;

      for (int column = 0; column < ATLAS_COLUMNS; ++column) {
        int cellLeft = Math.round((column * this.atlasWidth) / (float) ATLAS_COLUMNS);
        int cellRight = Math.round(((column + 1) * this.atlasWidth) / (float) ATLAS_COLUMNS) - 1;

        int minX = cellRight, minY = cellBottom, maxX = cellLeft, maxY = cellTop;
        boolean found = false;

        for (int y = cellTop; y <= cellBottom; ++y) {
          int offset = y * this.atlasWidth;
          for (int x = cellLeft; x <= cellRight; ++x) {
            if (((this.atlasPixels[offset + x] >>> 24) & 0xff) > 0) {
              minX = Math.min(minX, x);
              minY = Math.min(minY, y);
              maxX = Math.max(maxX, x);
              maxY = Math.max(maxY, y);
              found = true;
            }
          }
        }

        if (!found) {
          minX = cellLeft;
          minY = cellTop;
          maxX = cellRight;
          maxY = cellBottom;
        }

        minX = Math.max(cellLeft, minX - GLYPH_MARGIN);
        minY = Math.max(cellTop, minY - GLYPH_MARGIN);
        maxX = Math.min(cellRight, maxX + GLYPH_MARGIN);
        maxY = Math.min(cellBottom, maxY + GLYPH_MARGIN);

        Glyph glyph = new Glyph();
        glyph.cellLeft = cellLeft;
        glyph.cellTop = cellTop;
        glyph.cellRight = cellRight;
        glyph.cellBottom = cellBottom;
        glyph.centerX = (minX + maxX) * .5;
        glyph.centerY = (minY + maxY) * .5;
        glyph.span = Math.max(maxX - minX + 1, maxY - minY + 1);
        bounds[row * ATLAS_COLUMNS + column] = glyph;
      }
    }

    return bounds;
  }

  /** Bilinear alpha sampling, which is what keeps the edges smooth as it grows. */
  private double sampleAlpha(double x, double y, Glyph glyph) {
    if (x < glyph.cellLeft || x > glyph.cellRight || y < glyph.cellTop || y > glyph.cellBottom) {
      return 0;
    }
    int x0 = (int) Math.floor(x);
    int y0 = (int) Math.floor(y);
    int x1 = Math.min(glyph.cellRight, x0 + 1);
    int y1 = Math.min(glyph.cellBottom, y0 + 1);
    double tx = x - x0;
    double ty = y - y0;

    double a00 = alphaAt(x0, y0);
    double a10 = alphaAt(x1, y0);
    double a01 = alphaAt(x0, y1);
    double a11 = alphaAt(x1, y1);
    return lerp(lerp(a00, a10, tx), lerp(a01, a11, tx), ty);
  }

  private double alphaAt(int x, int y) {
    return ((this.atlasPixels[y * this.atlasWidth + x] >>> 24) & 0xff) / 255.;
  }

  private static double clamp(double v) {
    return (v < 0) ? 0 : (v > 1) ? 1 : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
