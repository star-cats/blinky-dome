/**
 * Red Canna — petal folds after Georgia O'Keeffe's flower close-ups.
 *
 * The scene is a flower filling the whole frame, seen so close that only the
 * folds are left. It is built the way the painting reads: from the top down.
 * The top of the frame is the back of the picture, and every fold that has
 * travelled further down is in front of everything above it. Nothing is ever
 * erased and nothing is blended between layers — a fold is opaque inside its
 * own outline, so the picture is a painter's algorithm run in reverse, and the
 * first fold covering an LED is the one you see.
 *
 * Running down the middle is the contour: two detuned sinusoids in the scroll
 * coordinate, which is the axis the whole flower is organized around. Every
 * fold anchors on it, at the depth where it was born, and veers off to one side
 * or the other from there. Because the anchors are placed in world coordinates
 * rather than on screen, the contour is a single continuous curve threaded
 * through the entire procession rather than something each fold has to be
 * animated along.
 *
 * A fold is born at the top of the frame spanning 2-6% of the width and opens
 * as it descends, easing out to its own maturity somewhere between 15% and 55%.
 * That is the only animation any fold has: it does not move relative to the
 * world, the camera pans up past it. Scroll is therefore one knob and it drives
 * everything — spawn rate, growth, procession — with zero meaning frozen.
 *
 * Its own shape is three things over one coordinate, the fraction of the span
 * already travelled: a center line that leaves the contour at an angle and then
 * arcs, a profile that carries the fold's full height out through the middle
 * before turning it over at the tip, and a ripple along the edge. The angle is
 * what keeps a flower rather than a layer cake — with every fold departing
 * horizontally the lobes stack into flat shelves, and it is only when they leave
 * at their own pitch that they read as radiating. Each fold also reaches a
 * little back across the contour, so the two sides interlock over the middle
 * instead of every base stopping on the same vertical line.
 *
 * Shading is two gradients over the fold's own body, and they are the whole
 * reason a stack of overlapping lobes reads as folded rather than as flat
 * cutouts:
 *
 *   - the top-side envelope carries +0.3 brightness, clamped at 1, so the upper
 *     edge of every fold catches light against the fold behind it;
 *   - brightness falls off by up to 0.2, clamped at 0, running outward along
 *     the fold and downward across it, so each lobe turns away as it follows
 *     the contour down.
 *
 * Color is one number per fold, drawn once at birth from 0.3 to 1 and read
 * through a ramp that walks gold to orange to red to magenta — the canna's
 * range. A fold keeps that number for life, so what moves through the picture
 * is the procession, never the color of any one petal. The draw is not quite
 * independent: Color Drift mixes it with a slow reflecting walk, so consecutive
 * folds land in the same family and the palette wanders across the flower
 * rather than speckling. At 0 every fold draws on its own.
 *
 * Whatever the folds leave uncovered is not background but the flower's own
 * shadowed depth, a dark plum that brightens along the contour, so the corners
 * read as the inside of the flower rather than as an empty frame.
 *
 * Folds are looked up through a table of horizontal slabs rather than scanned.
 * Depth tracks birth order, so the folds touching any one slab are a contiguous
 * run of the birth sequence, and walking that run from oldest to newest is
 * already front to back. Coverage accumulates until it is opaque and then stops,
 * which is why sixty overlapping lobes cost about six.
 */

var TAU = Math.PI * 2;

/** Ring capacity. Generous against the densest, slowest-retiring settings. */
var MAX_FOLDS = 128;

/** A fold is retired once it has descended this far past the bottom. */
var RETIRE_DEPTH = 1.45;

/** Depth slabs used to find the folds covering an LED. */
var SLAB_COUNT = 96;
var SLAB_LO = -0.25;
var SLAB_HI = 1.5;

/** Span at birth, as a fraction of frame width. */
var SPAN_BIRTH_MIN = 0.02;
var SPAN_BIRTH_MAX = 0.06;

/** Span at maturity, before the Span knob scales it. */
var SPAN_MATURE_MIN = 0.15;
var SPAN_MATURE_MAX = 0.55;

/** Depth by which a fold has finished opening. Varies per fold. */
var MATURE_DEPTH_MIN = 0.28;
var MATURE_DEPTH_MAX = 0.62;

/** Color coordinate drawn per fold, read through the palette ramp. */
var COLOR_MIN = 0.3;
var COLOR_MAX = 1;

/** How far the drifting color walk can move between one fold and the next. */
var COLOR_STEP = 0.24;
var COLOR_JITTER = 0.05;

/** The palette ramp: gold at 0, walking down through red into magenta. */
var HUE_ORIGIN = 55;

/** How much of the fold, measured from its top edge, the light envelope covers. */
var RIM_INNER = 0.68;
var RIM_GAIN = 0.3;

/** Falloff outward along the fold and downward across it. */
var SHADE_GAIN = 0.2;
var SHADE_OUTWARD = 0.35;
var SHADE_DOWNWARD = 0.65;

/** Where the light envelope starts bleaching toward white. */
var WHITEN_KNEE = 0.72;
var WHITEN = 0.6;

/**
 * How far a fold reaches back across the contour, as a fraction of its span,
 * and how fat it still is where it crosses. Folds have to interlock over the
 * center line rather than all stopping on it, or their bases stack into a hard
 * vertical seam down the middle of the flower.
 */
var BASE_BLEED = 0.3;

/**
 * Fullness through the middle of the fold. At 0 the lobe is a plain ellipse;
 * raising it carries the fold's height further out before it turns over, which
 * is the difference between a petal and a wedge.
 */
var PETAL_BELLY = 0.45;

/** Ruffle along a fold's edge: cycles across the span, and its depth. */
var RUFFLE_CYCLES = 2.8;
var RUFFLE_MAX = 0.3;

/** Lateral tightness of the glow the ground keeps along the contour. */
var GROUND_TIGHT = 5;
var GROUND_HUE = 340;

/** How the contour's two sinusoids drift, in radians per second. */
var DRIFT_1 = 0.1;
var DRIFT_2 = -0.147;

/**
 * How steeply a fold may leave the contour, up or down, before Fan scales it.
 * Without this every fold departs horizontally and the flower stacks into a
 * layer cake; this is what makes the folds radiate.
 */
var TILT_MAX = 0.8;

/** Chance a new fold goes to the opposite side of the contour from the last. */
var ALTERNATE_CHANCE = 0.66;

/**
 * How far a fold's anchor may slip off the even spacing, as a fraction of it.
 * Kept under a half so birth order still tracks depth and the slab runs stay
 * front to back; two folds that did swap would be a hair apart anyway.
 */
var SPACING_JITTER = 0.32;

knob("scroll", "Scroll", "Pans the camera up the flower; 0 holds the scene still", 0.3);
knob("density", "Density", "Folds on screen at once; center is about 35", 0.5);
knob("span", "Span", "Scales how wide a fold opens; center is 15% to 55%", 0.5);

knob("contour", "Contour", "Cycles the center line runs through down the frame", 0.4);
knob("sway", "Sway", "How far the center line swings off center", 0.6);
knob("detune", "Detune", "How far the second sinusoid sits from the first", 0.4);

knob("fan", "Fan", "How steeply folds leave the contour, up and down", 0.55);
knob("curl", "Curl", "How far a fold arcs downward as it reaches out", 0.5);
knob("depth", "Fold Depth", "Height of a fold against its own width", 0.5);
knob("ruffle", "Ruffle", "Waviness of a fold's edge", 0.36);
knob("soft", "Softness", "Edge softness of a fold", 0.28);

knob("lum", "Luminance", "Brightness of a fold's body, before the envelope", 0.5);
knob("hue", "Hue", "Rotates the whole palette", 0);
knob("range", "Hue Range", "How far the palette walks from gold toward magenta", 0.6);
knob("drift", "Color Drift", "Ties a fold's color to its neighbors'; 0 draws each independently", 0.4);
knob("sat", "Saturation", "Saturation of the palette", 0.82);
knob("ground", "Ground", "Level of the dark field the folds sit in", 0.4);

toggle("autoAspect", "Aspect", "Keep folds square on a non-square model", true);

// Per fold, fixed at birth.
var fWorld = [];
var fSide = [];
var fSpanBirth = [];
var fSpanMature = [];
var fMatureDepth = [];
var fColor = [];
var fLevelJitter = [];
var fCurl = [];
var fTilt = [];
var fDepth = [];
var fRuffle = [];
var fSatJitter = [];

// Per fold, resolved once per frame.
var fP = [];
var fX = [];
var fSpan = [];
var fHalfH = [];
var fCurlAmt = [];
var fTiltAmt = [];
var fArcLo = [];
var fArcHi = [];
var fHue = [];
var fSat = [];
var fBase = [];

// Live folds are the counters [foldFirst, foldNext), oldest first. Counters are
// absolute and only ever increase; the arrays above are indexed modulo capacity.
var foldFirst = 0;
var foldNext = 0;
var lastSide = 1;

// A slow reflecting walk over the palette, so consecutive folds land in the
// same family and the color drifts across the procession rather than
// shimmering fold to fold. Color Drift decides how much of it a fold takes.
var colorWalk = 0.65;

// Contiguous run of fold counters touching each depth slab.
var slabLo = [];
var slabHi = [];
var slabScale = SLAB_COUNT / (SLAB_HI - SLAB_LO);

// Camera position down the flower, in frame heights. Never wrapped.
var camera = 0;
var spawnedTo = 0;

// Per-frame scene values.
var spineA1 = 0;
var spineA2 = 0;
var spineK1 = TAU;
var spineK2 = TAU;
var spinePhase1 = 0;
var spinePhase2 = 0;
var ruffleAmt = 0;
var edgeSoft = 0.1;
var rimScale = 1 / (1 - RIM_INNER);
var groundLevel = 0;
var groundSat = 0.8;
var seeded = false;

// Scratch for the one color conversion per fold per LED.
var outR = 0;
var outG = 0;
var outB = 0;

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;

  spinePhase1 += DRIFT_1 * dt;
  spinePhase2 += DRIFT_2 * dt;

  var swayAmp = lerp(0, 0.32, sway);
  spineA1 = swayAmp * 0.62;
  spineA2 = swayAmp * 0.38;
  spineK1 = TAU * lerp(0.3, 2.2, contour);
  spineK2 = spineK1 * lerp(1.05, 2.2, detune);

  ruffleAmt = RUFFLE_MAX * ruffle;
  edgeSoft = Math.max(lerp(0.02, 0.35, soft), 1e-3);
  groundLevel = lerp(0, 0.5, ground);
  groundSat = lerp(0.3, 0.95, sat);

  // One fold every `spacing` of travel puts `1 / spacing` of them on screen.
  var spacing = 1 / lerp(20, 50, density);

  camera += lerp(0, 0.9, scroll) * dt;

  // Backdating the first frame's spawn point by a whole procession is what
  // makes the flower load already full instead of growing in from nothing. It
  // is also the stall guard: an engine that froze for a second owes the same
  // debt, and paying more than a screenful of it would be a burst, not a catch
  // up.
  if (!seeded) {
    seeded = true;
    spawnedTo = camera - RETIRE_DEPTH;
  }
  if (camera - spawnedTo > RETIRE_DEPTH) {
    spawnedTo = camera - RETIRE_DEPTH;
  }
  while (spawnedTo + spacing <= camera) {
    spawnedTo += spacing;
    spawn(spawnedTo, spacing);
  }

  while (foldFirst < foldNext && camera - fWorld[foldFirst % MAX_FOLDS] > RETIRE_DEPTH) {
    ++foldFirst;
  }

  resolveFolds(model);
  buildSlabs();
}

/** The contour: two detuned sinusoids in the scroll coordinate. */
function spineX(w) {
  return 0.5 +
    spineA1 * Math.sin(spineK1 * w + spinePhase1) +
    spineA2 * Math.sin(spineK2 * w + spinePhase2);
}

function spawn(world, spacing) {
  // Overflow can only happen if the ring is undersized for the settings; drop
  // the oldest rather than overwrite a fold that is still on screen.
  while (foldNext - foldFirst >= MAX_FOLDS) {
    ++foldFirst;
  }

  var i = foldNext % MAX_FOLDS;
  // Even spacing sets the density; the slip off it keeps the procession from
  // marching in step.
  fWorld[i] = world + randomRange(-SPACING_JITTER, SPACING_JITTER) * spacing;

  // Mostly alternating, so both sides of the contour keep filling, but not so
  // regularly that the procession reads as a zipper.
  lastSide = Math.random() < ALTERNATE_CHANCE ? -lastSide : lastSide;
  fSide[i] = lastSide;

  fSpanBirth[i] = randomRange(SPAN_BIRTH_MIN, SPAN_BIRTH_MAX);
  fSpanMature[i] = randomRange(SPAN_MATURE_MIN, SPAN_MATURE_MAX);
  fMatureDepth[i] = randomRange(MATURE_DEPTH_MIN, MATURE_DEPTH_MAX);

  colorWalk = reflect(colorWalk + randomRange(-COLOR_STEP, COLOR_STEP));
  var draw = lerp(Math.random(), colorWalk, drift) +
    randomRange(-COLOR_JITTER, COLOR_JITTER);
  fColor[i] = COLOR_MIN + (COLOR_MAX - COLOR_MIN) * clamp(draw, 0, 1);

  fLevelJitter[i] = randomRange(0.86, 1);
  fSatJitter[i] = randomRange(0.88, 1);
  // Mostly sweeping down, occasionally lifting, which is what keeps the stack
  // from reading as a single fan.
  fCurl[i] = randomRange(-0.12, 0.55);
  fTilt[i] = randomRange(-TILT_MAX, TILT_MAX);
  fDepth[i] = randomRange(0.22, 0.4);
  fRuffle[i] = Math.random() * TAU;

  ++foldNext;
}

function resolveFolds(model) {
  // Span is a fraction of width and depth is a fraction of height, so a fold
  // is only square on a square model unless the ratio is put back in.
  var aspect = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspect = model.xRange / model.yRange;
  }

  var spanScale = lerp(0.4, 1.6, span);
  var curlScale = lerp(0, 2, curl);
  var tiltScale = fan;
  var depthScale = lerp(0.45, 1.8, depth);
  var baseLevel = lerp(0.25, 0.85, lum);
  var hueSpan = lerp(20, 150, range);
  var hueOffset = hue * 360;
  var satBase = lerp(0.35, 1, sat);

  for (var k = foldFirst; k < foldNext; ++k) {
    var i = k % MAX_FOLDS;
    var p = camera - fWorld[i];
    fP[i] = p;

    // The only thing a fold animates: it eases open as the camera passes it.
    var t = clamp(p / fMatureDepth[i], 0, 1);
    var grown = t * t * (3 - 2 * t);
    var birth = fSpanBirth[i];
    var s = birth + (fSpanMature[i] * spanScale - birth) * grown;
    if (s < birth) {
      s = birth;
    }
    fSpan[i] = s;

    fX[i] = spineX(fWorld[i]);
    fHalfH[i] = s * fDepth[i] * depthScale * aspect;

    // The fold's center line, tilt*a + curl*a*a across a running 0 to 1. Its
    // extremes are the two ends and, if the parabola turns inside the fold, the
    // vertex — the slab table needs all three or a fold gets clipped.
    var tiltAmt = s * fTilt[i] * tiltScale * aspect;
    var curlAmt = s * fCurl[i] * curlScale * aspect;
    fTiltAmt[i] = tiltAmt;
    fCurlAmt[i] = curlAmt;

    var end = tiltAmt + curlAmt;
    var arcLo = end < 0 ? end : 0;
    var arcHi = end > 0 ? end : 0;
    if (curlAmt !== 0) {
      var vertex = -tiltAmt / (2 * curlAmt);
      if (vertex > 0 && vertex < 1) {
        var v = tiltAmt * vertex + curlAmt * vertex * vertex;
        if (v < arcLo) {
          arcLo = v;
        } else if (v > arcHi) {
          arcHi = v;
        }
      }
    }
    fArcLo[i] = arcLo;
    fArcHi[i] = arcHi;

    var h = (HUE_ORIGIN + hueOffset - fColor[i] * hueSpan) % 360;
    fHue[i] = h < 0 ? h + 360 : h;
    fSat[i] = satBase * fSatJitter[i];
    fBase[i] = baseLevel * fLevelJitter[i];
  }
}

/**
 * The run of fold counters touching each slab. Marking slabs while walking the
 * folds in birth order leaves every run contiguous by construction, and because
 * depth tracks birth order the run is already sorted front to back.
 */
function buildSlabs() {
  for (var s = 0; s < SLAB_COUNT; ++s) {
    slabLo[s] = 0;
    slabHi[s] = -1;
  }

  for (var k = foldFirst; k < foldNext; ++k) {
    var i = k % MAX_FOLDS;
    // The profile peaks at exactly the nominal half height; only the ruffle
    // reaches past it, and the slabs have to reserve room for that.
    var half = fHalfH[i] * (1 + ruffleAmt);
    var pMin = fP[i] - half + fArcLo[i];
    var pMax = fP[i] + half + fArcHi[i];

    var lo = Math.floor((pMin - SLAB_LO) * slabScale);
    var hi = Math.floor((pMax - SLAB_LO) * slabScale);
    if (hi < 0 || lo >= SLAB_COUNT) {
      continue;
    }
    if (lo < 0) {
      lo = 0;
    }
    if (hi >= SLAB_COUNT) {
      hi = SLAB_COUNT - 1;
    }
    for (var b = lo; b <= hi; ++b) {
      if (slabHi[b] < slabLo[b]) {
        slabLo[b] = k;
      }
      slabHi[b] = k;
    }
  }
}

function renderPoint(point, deltaMs) {
  var xn = point.xn;
  // Depth into the picture: 0 at the top of the frame, which is the back.
  var p = 1 - point.yn;

  var accR = 0;
  var accG = 0;
  var accB = 0;
  var trans = 1;

  var slab = Math.floor((p - SLAB_LO) * slabScale);
  if (slab >= 0 && slab < SLAB_COUNT) {
    var lo = slabLo[slab];
    var hi = slabHi[slab];

    for (var k = lo; k <= hi; ++k) {
      var i = k % MAX_FOLDS;

      // Lateral distance from the contour, signed into the fold's own side, as
      // a fraction of the span. Negative is the part reaching back across.
      var s = fSpan[i];
      var a = (xn - fX[i]) * fSide[i] / s;
      if (a >= 1 || a <= -BASE_BLEED) {
        continue;
      }

      // Full where it crosses the contour, carrying that height out through the
      // middle before turning over at the tip, rounded off where it reaches
      // back across, with the edge rippled hardest where the fold is widest.
      var profile;
      if (a > 0) {
        var e = 1 - a * a;
        profile = Math.sqrt(e) * (1 - PETAL_BELLY + PETAL_BELLY * e);
      } else {
        var r = a / BASE_BLEED;
        profile = Math.sqrt(1 - r * r);
      }
      var ripple = a > 0 ? 4 * a * (1 - a) : 0;
      var hh = fHalfH[i] * profile *
        (1 + ruffleAmt * ripple * Math.sin(RUFFLE_CYCLES * TAU * a + fRuffle[i]));
      if (hh <= 0) {
        continue;
      }

      // Height across the fold, measured from its own arcing center line. The
      // arc and the falloff both run from the contour outward, so the part
      // reaching back across the middle rides at the base value.
      var out = a > 0 ? a : 0;
      var n = (p - fP[i] - fTiltAmt[i] * out - fCurlAmt[i] * out * out) / hh;
      var away = n < 0 ? -n : n;
      if (away >= 1) {
        continue;
      }

      var alpha = (1 - away) / edgeSoft;
      if (alpha > 1) {
        alpha = 1;
      }

      // The top-side envelope, brightest exactly at the upper edge.
      var rimT = (-n - RIM_INNER) * rimScale;
      var rim = rimT <= 0 ? 0 : (rimT >= 1 ? 1 : rimT * rimT * (3 - 2 * rimT));

      // Falling off outward along the fold and downward across it.
      var shade = SHADE_OUTWARD * out + SHADE_DOWNWARD * (n + 1) * 0.5;

      var b = fBase[i] + RIM_GAIN * rim - SHADE_GAIN * shade;
      if (b > 1) {
        b = 1;
      } else if (b < 0) {
        b = 0;
      }

      // Light bleaches toward white rather than staying a saturated hue.
      var sat0 = fSat[i];
      if (b > WHITEN_KNEE) {
        sat0 *= 1 - WHITEN * (b - WHITEN_KNEE) / (1 - WHITEN_KNEE);
      }

      hsbToRgb(fHue[i], sat0, b);

      var weight = alpha * trans;
      accR += outR * weight;
      accG += outG * weight;
      accB += outB * weight;
      trans -= weight;
      if (trans < 0.004) {
        trans = 0;
        break;
      }
    }
  }

  if (trans > 0) {
    // Whatever the folds left uncovered is the flower's own shadowed depth,
    // which keeps a little glow along the contour.
    var d = xn - spineX(camera - p);
    var glow = 1 / (1 + d * d * GROUND_TIGHT);
    hsbToRgb(GROUND_HUE, groundSat, groundLevel * (0.35 + 0.65 * glow));
    accR += outR * trans;
    accG += outG * trans;
    accB += outB * trans;
  }

  return rgb(
    accR > 255 ? 255 : accR | 0,
    accG > 255 ? 255 : accG | 0,
    accB > 255 ? 255 : accB | 0
  );
}

/** Hue in degrees, saturation and value in 0..1, into outR/outG/outB in 0..255. */
function hsbToRgb(h, s, v) {
  var value = v * 255;
  if (s <= 0) {
    outR = value;
    outG = value;
    outB = value;
    return;
  }

  var sector = h / 60;
  var index = Math.floor(sector);
  var f = sector - index;
  var q = value * (1 - s);
  var w = value * (1 - s * f);
  var t = value * (1 - s * (1 - f));

  switch (index % 6) {
    case 0: outR = value; outG = t; outB = q; break;
    case 1: outR = w; outG = value; outB = q; break;
    case 2: outR = q; outG = value; outB = t; break;
    case 3: outR = q; outG = w; outB = value; break;
    case 4: outR = t; outG = q; outB = value; break;
    default: outR = value; outG = q; outB = w; break;
  }
}

function randomRange(lo, hi) {
  return lo + Math.random() * (hi - lo);
}

/** Folds a value back into 0..1 rather than clamping, so a walk keeps moving. */
function reflect(v) {
  while (v < 0 || v > 1) {
    if (v < 0) {
      v = -v;
    }
    if (v > 1) {
      v = 2 - v;
    }
  }
  return v;
}
