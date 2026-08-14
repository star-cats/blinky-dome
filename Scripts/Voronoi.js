/**
 * A Voronoi tessellation whose cells drift, and whose boundaries morph between
 * straight-edged polygons and rounded bubbles.
 *
 * Both looks come out of one field rather than two renderers. Every LED gets a
 * cell coordinate q that is 0 at its site and 1 on the cell boundary, and
 * there are two natural ways to build one:
 *
 *   polygonal   the factor by which the cell must be scaled about its site to
 *               land on this LED, so level sets are scaled copies of the cell
 *               and every edge of every one of them is straight
 *   bubbled     F1 / F2, the ratio of the distances to the nearest and second
 *               nearest sites, whose level sets are Apollonian circles
 *
 * The important part is that both are exactly 1 on the same set: the true
 * Voronoi skeleton. So Shape can crossfade between them freely. The boundary
 * never moves or blurs as it morphs, only the shape of the level sets inside
 * it changes, and cells go from hard-edged polygons to round bubbles without
 * ever passing through a stage where the tessellation looks broken. The morph
 * holds at every Edge setting, not just next to the boundary.
 *
 * The polygonal side is worth stating exactly, because the obvious version of
 * it is wrong. Normalizing the true distance-to-boundary as F1 / (F1 + dB)
 * also runs 0 to 1 and is also exactly 1 on the skeleton, but its level sets
 * are parabolas — it is the focus-and-directrix construction — so cells only
 * look straight-edged very close to the boundary and bulge everywhere else.
 * Scaling the cell's own half-planes instead is both correct and cheaper: the
 * scale factor against one rival falls out of a dot product, with no square
 * root anywhere in the loop.
 *
 * The sites live on a torus and are rebinned by where they actually are, every
 * frame, rather than being looked up in the grid cell they were born in. That
 * one decision is what lets Intensity be large. A site tied to its home cell
 * has to stay inside it for a fixed 3x3 search to find it, which caps the
 * wander at half a cell; binning by current position instead decouples how far
 * a site may travel from how much it costs to find, so sites can cross several
 * cells, clump and leave voids while the search stays local. The rebin is a
 * counting sort over a few hundred sites — trivial next to a frame of LEDs.
 *
 * It also makes the search exactly correct instead of merely reliable, which
 * the home-cell version was not. A site outside the searched block of radius R
 * is more than R cells away, by construction: the LED is somewhere inside its
 * own bin and the site is beyond the block's edge. So if the second nearest
 * site found is within R, no unsearched site can beat it and the answer is
 * provably the true one. When that test fails — a sparse patch, a wide void —
 * the radius grows and the search repeats. One pass at R=1 almost always
 * suffices, because bins average one site each.
 *
 * Resting positions scatter well past their own cell, so the arrangement reads
 * as organic even when nothing is moving. The underlying grid only guarantees
 * roughly even coverage; it is not visible as a lattice.
 *
 * Motion is Perlin-driven, not orbital. Each site reads its offset from a
 * private path through the seamless noise texture, so sites wander, hesitate
 * and double back independently instead of all circling in lockstep. Sites are
 * computed once per frame, not per LED — a couple of hundred texture samples a
 * frame — which is what keeps the noise essentially free.
 *
 * Output is pure luminosity, as specified: one 0-1 whiteness per LED.
 */

var ImageIO = Java.type("javax.imageio.ImageIO");
var JFile = Java.type("java.io.File");
var System = Java.type("java.lang.System");
var IntArray = Java.type("int[]");

var IMAGE_DIR = "Images/Noise";
var DRIFT_FILE = "Perlin_14-128x128.png";
var MEDIA_ROOT = null;

var MIN_CELLS = 2;
var MAX_CELLS = 16;

/**
 * Scatter of a site's resting position from its grid slot, in cells.
 *
 * Comfortably past half a cell, so resting sites cross into their neighbors'
 * territory and the arrangement has real clumps and voids in it rather than
 * reading as a jittered lattice. The grid is only there to keep coverage
 * roughly even across the torus.
 */
var REST_SPREAD = 0.85;

/** How far a site can wander from rest at full Intensity, in cells. */
var WANDER_MAX = 2.5;

/**
 * Ceiling on the search radius, in bins.
 *
 * The radius grows a ring at a time until the answer is provably settled; this
 * only exists so a pathological frame cannot spiral. It is generous because
 * overrunning it costs one LED a bigger scan, while hitting it too early would
 * put a wrong cell on the wall.
 */
var MAX_SEARCH = 6;

/** Rivals kept per LED. More than the densest neighborhood can produce. */
var MAX_CANDIDATES = 256;

knob("cells", "Cells", "How many cells across the pattern, 2 to 16", 0.4);

knob("shape", "Shape", "Boundary character: 0 is sharp and linear, 1 is smooth bubbles", 0.5);
knob("edge", "Edge", "Where the boundary sits within the cell", 0.72);
knob("soft", "Soft", "Softness of the boundary; low is a hard edge", 0.3);

knob("speed", "Speed", "How fast the sites wander", 0.4);
knob("intensity", "Intensity", "How far the sites wander; at full they cross several cells", 0.6);

knob("zoom", "Zoom", "Scale of the scene; center is 1x, ends are 1/4x and 4x", 0.5);
knob("rot", "Rotate", "Rotation of the scene, a full turn across the knob", 0);
knob("panx", "Pan X", "Horizontal translation; the scene wraps, so this runs forever", 0.5);
knob("pany", "Pan Y", "Vertical translation; the scene wraps, so this runs forever", 0.5);

knob("level", "Level", "Overall brightness", 1);

toggle("seams", "Seams", "Light the boundaries instead of the cell interiors", false);
toggle("autoAspect", "Aspect", "Keep the cells round on a non-square model", true);

var driftImage = null;
var resolvedRoot;

/** Site positions on the torus, in cells, wrapped into 0..gridG. */
var siteGX = [];
var siteGY = [];

// Sites sorted into bins by where they are now: binSites holds site indices
// grouped by bin, and binStart[b]..binStart[b+1] is bin b's run within it.
// Rebuilt every frame by counting sort, which needs no per-frame allocation.
var siteBin = [];
var binStart = [];
var binCursor = [];
var binSites = [];

// Per-frame values, resolved once in preRender rather than per LED.
var gridG = 4;
var drift = 0;
var cosT = 1;
var sinT = 0;
var invZoom = 1;
var panWorldX = 0;
var panWorldY = 0;
var aspectX = 1;
var edgeAt = 0.72;
var softness = 0.2;

// Scratch for the neighborhood search. The rival pass needs every candidate's
// offset vector, and allocating an array per LED would be a few hundred
// thousand short-lived arrays a second.
var candX = [];
var candY = [];
for (var c = 0; c < MAX_CANDIDATES; ++c) {
  candX[c] = 0;
  candY[c] = 0;
}

function init() {
  driftImage = loadTexture(DRIFT_FILE);
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;
  // Integrated rather than read off wall time, so changing Speed bends the
  // sites' paths from where they are instead of teleporting them.
  drift = wrap01(drift + dt * lerp(0, 0.3, speed));

  gridG = clampInt(Math.round(lerp(MIN_CELLS, MAX_CELLS, cells)), MIN_CELLS, MAX_CELLS);
  placeSites();

  var angle = rot * Math.PI * 2;
  cosT = Math.cos(angle);
  sinT = Math.sin(angle);
  invZoom = 1 / Math.pow(2, (zoom - 0.5) * 4);
  panWorldX = (panx - 0.5) * 2;
  panWorldY = (pany - 0.5) * 2;

  aspectX = 1;
  if (autoAspect && model.xRange > 0 && model.yRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  // Kept just off 0 and 1 so a cell always has an interior and a boundary,
  // rather than collapsing to all-lit or all-dark at the ends of the knob.
  edgeAt = lerp(0.06, 0.97, edge);
  softness = Math.max(lerp(0.004, 0.55, soft), 1e-4);
}

/**
 * Walks every site to where it is this frame, then bins it by where it landed.
 *
 * Resting spots come from a hash, so they are irregular and hold still at zero
 * Intensity — an unjittered grid would give a degenerate diagram of identical
 * squares. The wander is added on top, read from the noise texture along a path
 * unique to each site, and is free to carry the site clear of its grid slot.
 */
function placeSites() {
  var wander = intensity * WANDER_MAX;
  var bins = gridG * gridG;

  for (var b = 0; b <= bins; ++b) {
    binStart[b] = 0;
  }

  for (var j = 0; j < gridG; ++j) {
    for (var i = 0; i < gridG; ++i) {
      var s = j * gridG + i;
      var restX = i + 0.5 + (hash01(i, j, 0) - 0.5) * 2 * REST_SPREAD;
      var restY = j + 0.5 + (hash01(i, j, 1) - 0.5) * 2 * REST_SPREAD;

      // Each site reads a different region of the texture and travels through
      // it on its own line, which decorrelates neighbors that would otherwise
      // sweep together — the noise is smooth, so nearby samples move alike.
      var u = hash01(i, j, 2);
      var v = hash01(i, j, 3);
      var gxs = wrapTorus(restX + signedNoise(u + drift, v) * wander);
      var gys = wrapTorus(restY + signedNoise(u + drift + 0.37, v + 0.5) * wander);

      siteGX[s] = gxs;
      siteGY[s] = gys;

      var bin = clampInt(Math.floor(gys), 0, gridG - 1) * gridG +
        clampInt(Math.floor(gxs), 0, gridG - 1);
      siteBin[s] = bin;
      // Counted one slot high, so the prefix sum below lands on bin starts.
      binStart[bin + 1] += 1;
    }
  }

  for (var k = 1; k <= bins; ++k) {
    binStart[k] += binStart[k - 1];
  }
  for (var c = 0; c < bins; ++c) {
    binCursor[c] = binStart[c];
  }
  for (var t = 0; t < bins; ++t) {
    binSites[binCursor[siteBin[t]]++] = t;
  }
}

/** Wraps a coordinate in cells onto the torus. */
function wrapTorus(value) {
  return value - Math.floor(value / gridG) * gridG;
}

function renderPoint(point, deltaMs) {
  if (driftImage == null) {
    return hsb(0, 0, 0);
  }

  var sx = (point.xn - 0.5) * aspectX;
  var sy = (point.yn - 0.5);

  // Screen back to the torus: unrotate, unzoom, translate, then wrap.
  var wx = wrap01((cosT * sx + sinT * sy) * invZoom + panWorldX + 0.5);
  var wy = wrap01((-sinT * sx + cosT * sy) * invZoom + panWorldY + 0.5);

  // Everything below is in cells, so distances stay comparable as Cells moves.
  var gx = wx * gridG;
  var gy = wy * gridG;
  var ci = clampInt(Math.floor(gx), 0, gridG - 1);
  var cj = clampInt(Math.floor(gy), 0, gridG - 1);

  // The search state is deliberately local. It is read and written a few dozen
  // times per LED, and hoisting it out to module scope so the ring scan could
  // live in its own function measured about three times slower — the scan is
  // inlined below for the same reason.
  var count = 0;
  var f1sq = 1e18;
  var f2sq = 1e18;
  var r1x = 0;
  var r1y = 0;
  var nearest = -1;
  var rivalMax = 0;
  var rivalDone = 0;

  // Where the LED sits inside its own bin, which is what makes the block's
  // reach asymmetric and worth measuring rather than assuming.
  var lx = gx - ci;
  var ly = gy - cj;

  // Grow the block a ring at a time until the answer is settled. Two things
  // have to be out of reach of the sites nobody looked at:
  //
  //   the runner-up  nothing unseen within f2 of the LED, or the two nearest
  //                  sites are not the two nearest
  //   the rivals     a rival binds the cell only if it takes the boundary in
  //                  from where the seen rivals put it. Along the ray from the
  //                  site through the LED, that boundary is at y, and a site
  //                  pulls it closer exactly when it is nearer to y than the
  //                  site is. So the cell shape is settled once no unseen site
  //                  can be within f1/q of y.
  //
  // Both reduce to asking whether a disc is inside the searched block, and the
  // block is a square: testing against its four sides rather than its inscribed
  // circle is what keeps the radius at 2 instead of 3 or 4. The rival test is
  // the binding one, and it is not implied by the runner-up test — a distant
  // site can bound a cell long after the two nearest are known. Skipping it
  // leaves the far side of the block deciding cell shape, which shows up as a
  // discontinuity at the wrap.
  var polygonal = 0;
  var f1 = 0;
  for (var radius = 0; radius <= MAX_SEARCH; ++radius) {
    // One ring of bins, never a whole block: an expansion only pays for what
    // it has not already seen. Bins along a row are consecutive, so the wrap
    // is a compare-and-subtract carried along rather than a modulo per bin.
    var rawJ = cj - radius;
    var binJ = wrapIndex(rawJ, gridG);

    for (var dj = -radius; dj <= radius; ++dj) {
      var offY = rawJ - binJ;
      var rowBase = binJ * gridG;
      var edgeRow = (dj === -radius || dj === radius);
      var rawI = ci - radius;
      var binI = wrapIndex(rawI, gridG);
      // Interior rows contribute only the ring's two ends; edge rows are solid.
      var span = edgeRow ? radius + radius : 1;

      for (var di = 0; di <= span; ++di) {
        var bin = rowBase + binI;
        // Whole tiles of the torus, so a site read from a wrapped bin sits at
        // the unwrapped coordinate the LED actually sees it at.
        var offX = rawI - binI;

        for (var k = binStart[bin]; k < binStart[bin + 1]; ++k) {
          var s = binSites[k];
          var rx = siteGX[s] + offX - gx;
          var ry = siteGY[s] + offY - gy;
          if (count < MAX_CANDIDATES) {
            candX[count] = rx;
            candY[count] = ry;
          }

          var dsq = rx * rx + ry * ry;
          if (dsq < f1sq) {
            f2sq = f1sq;
            f1sq = dsq;
            r1x = rx;
            r1y = ry;
            nearest = count < MAX_CANDIDATES ? count : -1;
            // Every scale was measured against the old winner, so they all go.
            rivalMax = 0;
            rivalDone = 0;
          } else if (dsq < f2sq) {
            f2sq = dsq;
          }
          ++count;
        }

        var stride = edgeRow ? 1 : radius + radius;
        rawI += stride;
        binI += stride;
        while (binI >= gridG) {
          binI -= gridG;
        }
      }

      ++rawJ;
      if (++binJ >= gridG) {
        binJ -= gridG;
      }
    }

    if (count < 2) {
      continue;
    }
    f1 = Math.sqrt(f1sq);
    var rivals = count < MAX_CANDIDATES ? count : MAX_CANDIDATES;
    polygonal = rivalScale(r1x, r1y, nearest, rivalDone, rivals, rivalMax);
    rivalMax = polygonal;
    rivalDone = rivals;

    if (!discInsideBlock(0, 0, Math.sqrt(f2sq), radius, lx, ly)) {
      continue;
    }
    if (f1 <= 0) {
      break;
    }
    // A scale of zero is not a settled answer, it is no answer: every rival
    // seen so far lies behind the site, so nothing bounds the cell yet.
    if (polygonal <= 0) {
      continue;
    }
    var reach = 1 / polygonal;
    if (discInsideBlock(
      r1x * (1 - reach), r1y * (1 - reach), f1 * reach, radius, lx, ly
    )) {
      break;
    }
  }

  if (count === 0) {
    return hsb(0, 0, 0);
  }

  // Two coordinates for the same cell, both 0 at the site and 1 on the
  // boundary: straight level sets, and circular ones. Shape crossfades them.
  var f2 = Math.sqrt(f2sq);
  var bubbled = f2 > 1e-9 ? f1 / f2 : 0;
  var q = polygonal + (bubbled - polygonal) * shape;

  var value = clamp((edgeAt - q) / softness, 0, 1);
  if (seams) {
    value = 1 - value;
  }

  return hsb(0, 0, value * level * 100);
}

/**
 * Noise sample rescaled to a symmetric -1..1.
 *
 * The texture does not use the full 0-1 range — Perlin_14 spans about 0.19 to
 * 0.59 — so reading it as if it did would spend under half the wander budget
 * and, because that span sits below 0.5, would bias every site's drift toward
 * one corner. Centering on the texture's own midpoint fixes both.
 */
function signedNoise(u, v) {
  var g = (sampleGray(driftImage, u, v) - driftImage.center) / driftImage.halfSpan;
  return clamp(g, -1, 1);
}

/**
 * Is a disc, given relative to the LED, wholly inside the searched block?
 *
 * The block of radius R around bin (ci,cj) reaches R bins out plus whatever is
 * left of the LED's own bin, which is why the LED's position within it matters.
 */
function discInsideBlock(cx, cy, r, radius, lx, ly) {
  return cx - r >= -radius - lx &&
    cx + r <= radius + 1 - lx &&
    cy - r >= -radius - ly &&
    cy + r <= radius + 1 - ly;
}

/**
 * How far the cell has to be scaled about its site to reach this LED.
 *
 * Each rival contributes one half-plane, and the scale that puts the LED on
 * that half-plane is a ratio of dot products; the binding one is the largest.
 * The nearest site is skipped — it is the winner, not a rival.
 *
 * Candidates already folded in are not revisited, so a search that expands
 * three times still reads each candidate once. Finding a nearer site resets
 * the range, since every scale is measured against the current winner.
 */
function rivalScale(r1x, r1y, nearest, from, to, best) {
  for (var r = from; r < to; ++r) {
    if (r === nearest) {
      continue;
    }
    var ex = candX[r] - r1x;
    var ey = candY[r] - r1y;
    var spanSq = ex * ex + ey * ey;
    if (spanSq < 1e-12) {
      continue;
    }
    var scale = -2 * (r1x * ex + r1y * ey) / spanSq;
    if (scale > best) {
      best = scale;
    }
  }
  return best;
}

/** Deterministic, decorrelated 0-1 value for an integer cell and a salt. */
function hash01(i, j, salt) {
  var s = Math.sin(i * 127.1 + j * 311.7 + salt * 74.7) * 43758.5453;
  return s - Math.floor(s);
}

function wrapIndex(value, n) {
  return ((value % n) + n) % n;
}

function wrap01(value) {
  return value - Math.floor(value);
}

function clampInt(value, low, high) {
  return value < low ? low : (value > high ? high : value);
}

/** Bilinear grayscale sampling with seamless wrapping on both axes. */
function sampleGray(image, u, v) {
  u = wrap01(u);
  v = wrap01(v);
  var x = u * image.w;
  var y = v * image.h;
  var x0 = Math.floor(x) % image.w;
  var y0 = Math.floor(y) % image.h;
  var x1 = (x0 + 1) % image.w;
  var y1 = (y0 + 1) % image.h;
  var tx = x - Math.floor(x);
  var ty = y - Math.floor(y);
  var a = grayOf(image.px[y0 * image.w + x0]);
  var b = grayOf(image.px[y0 * image.w + x1]);
  var c = grayOf(image.px[y1 * image.w + x0]);
  var d = grayOf(image.px[y1 * image.w + x1]);
  return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
}

function grayOf(argb) {
  var r = (argb >> 16) & 0xff;
  var g = (argb >> 8) & 0xff;
  var b = argb & 0xff;
  return (r * 0.2126 + g * 0.7152 + b * 0.0722) / 255;
}

function mediaRoot() {
  if (resolvedRoot !== undefined) {
    return resolvedRoot;
  }
  var candidates = [];
  if (MEDIA_ROOT) {
    candidates.push(MEDIA_ROOT);
  }
  var dir = new JFile(System.getProperty("user.dir"));
  for (var i = 0; i < 6 && dir != null; ++i) {
    candidates.push(dir.getPath());
    dir = dir.getParentFile();
  }
  candidates.push(System.getProperty("user.home") + "/Chromatik");

  resolvedRoot = null;
  for (var j = 0; j < candidates.length; ++j) {
    if (new JFile(candidates[j], IMAGE_DIR).isDirectory()) {
      resolvedRoot = candidates[j];
      break;
    }
  }
  return resolvedRoot;
}

function loadTexture(name) {
  var root = mediaRoot();
  if (root == null) {
    throw new Error("Voronoi.js: could not find " + IMAGE_DIR + " — set MEDIA_ROOT");
  }
  var file = new JFile(new JFile(root, IMAGE_DIR), name);
  var buffered = ImageIO.read(file);
  if (buffered == null) {
    throw new Error("Voronoi.js: could not read " + file);
  }
  var w = buffered.getWidth();
  var h = buffered.getHeight();
  var px = buffered.getRGB(0, 0, w, h, new IntArray(w * h), 0, w);

  // The texture's own extremes, so signedNoise can center and scale on them
  // instead of assuming the file happens to span the full 0-1 range.
  var low = 1;
  var high = 0;
  for (var i = 0; i < w * h; ++i) {
    var g = grayOf(px[i]);
    if (g < low) {
      low = g;
    }
    if (g > high) {
      high = g;
    }
  }

  return {
    w: w,
    h: h,
    px: px,
    center: (low + high) * 0.5,
    halfSpan: Math.max((high - low) * 0.5, 1e-6)
  };
}
