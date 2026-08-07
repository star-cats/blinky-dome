/**
 * Pulses a random glyph from the 6x6 Viking bind-rune atlas every beat.
 *
 * Timing is fixed at 120 BPM for now: a new pulse starts every 0.5 seconds and
 * lasts 1 second. During the pulse the rune grows with an exponential
 * ease-out while its opacity follows the complementary exponential decay.
 */

var ImageIO = Java.type("javax.imageio.ImageIO");
var File = Java.type("java.io.File");
var System = Java.type("java.lang.System");

var BPM = 120;
var BEAT_MS = 60000 / BPM;
var PULSE_EVERY_BEATS = 1;
var PULSE_INTERVAL_MS = BEAT_MS * PULSE_EVERY_BEATS;
var PULSE_DURATION_MS = 1000;
var EXP_DECAY = 3.4;
var MAX_ACTIVE_PULSES = Math.ceil(PULSE_DURATION_MS / PULSE_INTERVAL_MS) + 1;

var ATLAS_COLUMNS = 6;
var ATLAS_ROWS = 6;
var GLYPH_COUNT = ATLAS_COLUMNS * ATLAS_ROWS;
var ATLAS_PATH = "Images/Glyphs/VikingRunes.png";

var TRANSPARENT = rgba(0, 0, 0, 0);

// Size is the largest visible glyph dimension as a fraction of the screen.
knob("size", "Size", "Initial glyph size as a fraction of the screen", 0.75);
knob("growth", "Growth", "Fractional size increase over each pulse", 0.25);
knob(
  "phase",
  "Beat Phase",
  "Beat-grid shift from half a beat early to half a beat late",
  0.5
);

var atlasWidth = 0;
var atlasHeight = 0;
var atlasPixels = null;
var glyphs = [];

var pulseHistory = [];
var activePulses = [];

/** Load the atlas and find a tight visible bounding box for every glyph. */
function init() {
  var userMediaFile = new File(System.getProperty("user.home"), "Chromatik/" + ATLAS_PATH);
  var workingDirectoryFile = new File(ATLAS_PATH).getAbsoluteFile();
  var atlasFile = userMediaFile.isFile() ? userMediaFile : workingDirectoryFile;
  var atlas = ImageIO.read(atlasFile);

  if (atlas == null) {
    throw new Error("Could not load Viking rune atlas: " + atlasFile);
  }

  atlasWidth = atlas.getWidth();
  atlasHeight = atlas.getHeight();
  atlasPixels = atlas.getRGB(0, 0, atlasWidth, atlasHeight, null, 0, atlasWidth);
  glyphs = findGlyphBounds();
}

function onActive() {
  // Rebuild the short pulse history when the pattern becomes active.
  pulseHistory = [];
  activePulses = [];
}

/**
 * Compute pulse state once per frame, before renderPoint runs for every LED.
 */
function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  // The center of the knob is no shift; its full range is -1/2 to +1/2 beat.
  var phaseShiftMs = (phase - 0.5) * BEAT_MS;
  var shiftedNow = nowMillis - phaseShiftMs;
  var currentPulseIndex = Math.floor(shiftedNow / PULSE_INTERVAL_MS);
  var decayAtEnd = Math.exp(-EXP_DECAY);
  activePulses = [];

  // Keep enough beat slots to cover every envelope that can still be visible.
  // Oldest-to-newest ordering lets each new random glyph avoid the prior beat.
  for (var age = MAX_ACTIVE_PULSES - 1; age >= 0; --age) {
    var eventIndex = currentPulseIndex - age;
    var elapsedMs = shiftedNow - eventIndex * PULSE_INTERVAL_MS;

    if (elapsedMs < 0 || elapsedMs >= PULSE_DURATION_MS) {
      continue;
    }

    var progress = clamp(elapsedMs / PULSE_DURATION_MS, 0, 1);
    var alpha =
      (Math.exp(-EXP_DECAY * progress) - decayAtEnd) /
      (1 - decayAtEnd);
    var growthProgress = 1 - alpha;

    activePulses.push({
      glyphIndex: glyphForPulse(eventIndex),
      alpha: alpha,
      size: Math.max(0.01, size) * (1 + growth * growthProgress)
    });
  }

  prunePulseHistory(currentPulseIndex);
}

function renderPoint(point, deltaMs) {
  if (atlasPixels == null || activePulses.length === 0) {
    return TRANSPARENT;
  }

  var dx = point.xn - 0.5;
  var dy = point.yn - 0.5;
  var remainingTransparency = 1;

  for (var i = 0; i < activePulses.length; ++i) {
    var pulse = activePulses[i];
    var halfSize = pulse.size * 0.5;

    if (Math.abs(dx) > halfSize || Math.abs(dy) > halfSize) {
      continue;
    }

    var glyph = glyphs[pulse.glyphIndex];

    // Use a square source region around the tight glyph bounds so "Size"
    // tracks the visible rune while preserving its original aspect ratio. Y is
    // flipped because BufferedImage coordinates run down from the image top.
    var sourceSpan = glyph.span;
    var sourceX = glyph.centerX + (dx / pulse.size) * sourceSpan;
    var sourceY = glyph.centerY - (dy / pulse.size) * sourceSpan;
    var glyphAlpha = sampleAlphaBilinear(sourceX, sourceY, glyph);
    var layerAlpha = clamp(glyphAlpha * pulse.alpha, 0, 1);

    // Alpha-over compositing for same-colored white layers.
    remainingTransparency *= 1 - layerAlpha;
  }

  var combinedAlpha = 1 - remainingTransparency;
  if (combinedAlpha <= 0) {
    return TRANSPARENT;
  }

  return rgba(255, 255, 255, Math.round(255 * combinedAlpha));
}

/** Return one stable random glyph for a beat while that beat's tail is alive. */
function glyphForPulse(eventIndex) {
  for (var i = 0; i < pulseHistory.length; ++i) {
    if (pulseHistory[i].eventIndex === eventIndex) {
      return pulseHistory[i].glyphIndex;
    }
  }

  var previousGlyph = glyphForExistingPulse(eventIndex - 1);
  var nextGlyph = Math.floor(Math.random() * GLYPH_COUNT);
  if (previousGlyph != null && GLYPH_COUNT > 1) {
    while (nextGlyph === previousGlyph) {
      nextGlyph = Math.floor(Math.random() * GLYPH_COUNT);
    }
  }

  pulseHistory.push({ eventIndex: eventIndex, glyphIndex: nextGlyph });
  return nextGlyph;
}

function glyphForExistingPulse(eventIndex) {
  for (var i = 0; i < pulseHistory.length; ++i) {
    if (pulseHistory[i].eventIndex === eventIndex) {
      return pulseHistory[i].glyphIndex;
    }
  }
  return null;
}

function prunePulseHistory(currentPulseIndex) {
  var oldestNeeded = currentPulseIndex - MAX_ACTIVE_PULSES;
  var retained = [];
  for (var i = 0; i < pulseHistory.length; ++i) {
    if (pulseHistory[i].eventIndex >= oldestNeeded) {
      retained.push(pulseHistory[i]);
    }
  }
  pulseHistory = retained;
}

/** Find each cell's nontransparent pixels, then add a small sampling margin. */
function findGlyphBounds() {
  var bounds = [];

  for (var row = 0; row < ATLAS_ROWS; ++row) {
    var cellTop = Math.round((row * atlasHeight) / ATLAS_ROWS);
    var cellBottom = Math.round(((row + 1) * atlasHeight) / ATLAS_ROWS) - 1;

    for (var column = 0; column < ATLAS_COLUMNS; ++column) {
      var cellLeft = Math.round((column * atlasWidth) / ATLAS_COLUMNS);
      var cellRight = Math.round(((column + 1) * atlasWidth) / ATLAS_COLUMNS) - 1;
      var minX = cellRight;
      var minY = cellBottom;
      var maxX = cellLeft;
      var maxY = cellTop;
      var found = false;

      for (var y = cellTop; y <= cellBottom; ++y) {
        var offset = y * atlasWidth;
        for (var x = cellLeft; x <= cellRight; ++x) {
          if (((atlasPixels[offset + x] >>> 24) & 0xff) > 0) {
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

      // Two source pixels retain the antialiased fringe around the tight box.
      minX = Math.max(cellLeft, minX - 2);
      minY = Math.max(cellTop, minY - 2);
      maxX = Math.min(cellRight, maxX + 2);
      maxY = Math.min(cellBottom, maxY + 2);

      bounds.push({
        cellLeft: cellLeft,
        cellTop: cellTop,
        cellRight: cellRight,
        cellBottom: cellBottom,
        centerX: (minX + maxX) * 0.5,
        centerY: (minY + maxY) * 0.5,
        span: Math.max(maxX - minX + 1, maxY - minY + 1)
      });
    }
  }

  return bounds;
}

/** Bilinear alpha sampling preserves smooth edges as the glyph grows. */
function sampleAlphaBilinear(x, y, glyph) {
  if (
    x < glyph.cellLeft ||
    x > glyph.cellRight ||
    y < glyph.cellTop ||
    y > glyph.cellBottom
  ) {
    return 0;
  }

  var x0 = Math.floor(x);
  var y0 = Math.floor(y);
  var x1 = Math.min(glyph.cellRight, x0 + 1);
  var y1 = Math.min(glyph.cellBottom, y0 + 1);
  var tx = x - x0;
  var ty = y - y0;

  var a00 = alphaAt(x0, y0);
  var a10 = alphaAt(x1, y0);
  var a01 = alphaAt(x0, y1);
  var a11 = alphaAt(x1, y1);
  var top = lerp(a00, a10, tx);
  var bottom = lerp(a01, a11, tx);
  return lerp(top, bottom, ty);
}

function alphaAt(x, y) {
  return ((atlasPixels[y * atlasWidth + x] >>> 24) & 0xff) / 255;
}
