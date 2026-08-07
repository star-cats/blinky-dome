/**
 * A single playing card, rotating in 3D about its vertical axis.
 *
 * The card is a flat rectangle in space. Rendering is done backwards, per LED:
 * take the point's normalized position as a spot on the screen, cast a ray from
 * the eye through it, intersect that ray with the rotated card plane, and shade
 * whatever the card has at the intersection. Nothing is rasterized, so the
 * result is exact at any LED density and the perspective is real rather than
 * faked with a horizontal squash.
 *
 * ANGLE is the whole animation interface: 0 is the face square to the viewer,
 * 180 is the back square to the viewer, and everything between is a turn about
 * the vertical axis. An external controller should drive it through
 * setCardAngle() rather than by writing the knob.
 *
 *   setCardAngle(90)    // edge on
 *   setCardAngle(180)   // showing its back
 *   clearCardAngle()    // back to the Angle and Spin knobs
 *
 * Art comes from Images/Cards/ as PNGs with alpha — see
 * Generators/generate_card_images.py, which writes a placeholder set. The face
 * is assembled here rather than loaded whole: a suit pip with its rank glyph
 * underneath in the top-left, the same pair rotated 180 degrees in the
 * bottom-right, over white stock the pattern draws itself. Only three kinds of
 * file exist — <suit>.png, <rank>.png, back.png.
 */

var ImageIO = Java.type("javax.imageio.ImageIO");
var JFile = Java.type("java.io.File");
var IntArray = Java.type("int[]");

var IMAGE_DIR = "Images/Cards";

/**
 * Where IMAGE_DIR lives. Left null, the media root is found by probing (see
 * mediaRoot); set it to an absolute path if that ever guesses wrong.
 */
var MEDIA_ROOT = null;

var SUITS = ["heart", "diamond", "club", "spade"];
var RANKS = ["A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"];

// Card proportions: width over height. 0.714 is a real poker card (2.5 x 3.5).
var CARD_ASPECT = 0.714;

// Corner index layout, in fractions of the card. Horizontal positions are
// fractions of the width, vertical ones fractions of the height, and sprite
// heights are fractions of the height — a sprite's width follows from its own
// pixel aspect, so nothing the user drops in gets stretched.
var INDEX_X = 0.155;
var SUIT_Y = 0.105;
var RANK_Y = 0.255;
var SPRITE_H = 0.135;

var CORNER_RADIUS = 0.055; // fraction of card height
var STOCK = 0xfff2f2ee; // the white the card is printed on

var BLACK = 0xff000000;

knobi("suit", "Suit", "Suit: heart, diamond, club, spade", 0, 4);
knobi("rank", "Rank", "Rank: A, 2-10, J, Q, K", 0, 13);

knob("angle", "Angle", "Rotation about the vertical axis, 0 = face on, 180 = back on", 0);
knob("spin", "Spin", "Continuous rotation added to Angle; 0.5 is still", 0.5);
knob("persp", "Persp", "Perspective strength; 0 is an orthographic projection", 0.45);

knob("size", "Size", "Card height as a fraction of the frame", 0.6);
knob("posx", "X", "Card center, horizontal", 0.5);
knob("posy", "Y", "Card center, vertical", 0.5);

knob("shade", "Shade", "How much the card dims as it turns edge on", 0.4);
knob("level", "Level", "Overall brightness", 1);

toggle("tintRank", "Tint", "Recolor the rank glyph to the suit color", true);
toggle("autoAspect", "Aspect", "Correct for a non-square model", true);

// ---------------------------------------------------------------- card angle
//
// Held outside the knob so a controller can drive the card without fighting a
// parameter, and so spin can accumulate across frames at whatever rate the
// engine happens to be running.

var cardAngleDeg = 0;
var spinAngleDeg = 0;
var angleOverride = null;

/**
 * Set the card's rotation directly, in degrees. 0 is face on, 180 is back on.
 *
 * The override holds until clearCardAngle(), which is what makes this usable
 * from a controller: it does not have to be re-set every frame to keep the
 * knob and the spin from taking the card back.
 */
function setCardAngle(deg) {
  angleOverride = deg;
}

/** Hand the card back to the Angle and Spin knobs. */
function clearCardAngle() {
  angleOverride = null;
}

// -------------------------------------------------------------- image loading

var imageCache = {};
var tintCache = {};
var resolvedRoot;

/**
 * The directory IMAGE_DIR is relative to.
 *
 * There is no handle on LX from a script, so the media root cannot be asked
 * for — it gets found instead, by walking up from the JVM's working directory
 * looking for the image folder, then falling back to ~/Chromatik. Resolved once
 * and remembered, including the failure, so a bad path is not re-probed every
 * frame.
 */
function mediaRoot() {
  if (resolvedRoot !== undefined) {
    return resolvedRoot;
  }
  var candidates = [];
  if (MEDIA_ROOT) {
    candidates.push(MEDIA_ROOT);
  }
  var dir = new JFile(System.getProperty("user.dir"));
  for (var i = 0; i < 5 && dir != null; ++i) {
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
  if (resolvedRoot == null) {
    print("PlayingCard.js: could not find " + IMAGE_DIR + " — set MEDIA_ROOT");
  }
  return resolvedRoot;
}

/**
 * Load Images/Cards/<name>.png as { w, h, px }, or null if it is not there.
 *
 * Pixels are pulled into a Java int[] of ARGB rather than a JS array: one
 * allocation, and no per-frame trip through BufferedImage's color model.
 * Misses are cached too, so a missing file costs one failed lookup, not one
 * per point per frame.
 */
function loadImage(name) {
  if (name in imageCache) {
    return imageCache[name];
  }
  var image = null;
  var root = mediaRoot();
  if (root != null) {
    var file = new JFile(new JFile(root, IMAGE_DIR), name + ".png");
    try {
      var buffered = ImageIO.read(file);
      if (buffered != null) {
        var w = buffered.getWidth();
        var h = buffered.getHeight();
        image = { w: w, h: h, px: buffered.getRGB(0, 0, w, h, new IntArray(w * h), 0, w) };
      } else {
        print("PlayingCard.js: cannot read " + file);
      }
    } catch (e) {
      print("PlayingCard.js: " + file + ": " + e);
    }
  }
  imageCache[name] = image;
  return image;
}

/**
 * The color of a suit, taken from its own artwork.
 *
 * An alpha-weighted mean over the pip, so the rank glyph tints to match
 * whatever art is in the folder instead of a hard-coded table.
 */
function suitTint(name) {
  if (name in tintCache) {
    return tintCache[name];
  }
  var image = loadImage(name);
  var tint = STOCK;
  if (image != null) {
    var sumA = 0, sumR = 0, sumG = 0, sumB = 0;
    for (var i = 0; i < image.px.length; ++i) {
      var argb = image.px[i];
      var a = (argb >>> 24) & 0xff;
      sumA += a;
      sumR += ((argb >> 16) & 0xff) * a;
      sumG += ((argb >> 8) & 0xff) * a;
      sumB += (argb & 0xff) * a;
    }
    if (sumA > 0) {
      tint = pack(255, (sumR / sumA) | 0, (sumG / sumA) | 0, (sumB / sumA) | 0);
    }
  }
  tintCache[name] = tint;
  return tint;
}

// ------------------------------------------------------------------- pixel ops

function pack(a, r, g, b) {
  return ((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
}

/** Nearest-neighbor texel at (fu, fv) in 0..1, image space, y down. */
function texel(image, fu, fv) {
  var x = (fu * image.w) | 0;
  var y = (fv * image.h) | 0;
  if (x < 0) { x = 0; } else if (x >= image.w) { x = image.w - 1; }
  if (y < 0) { y = 0; } else if (y >= image.h) { y = image.h - 1; }
  return image.px[y * image.w + x];
}

/** Source over destination. Destination is opaque, so the result is too. */
function over(dst, src) {
  var a = (src >>> 24) & 0xff;
  if (a === 0) {
    return dst;
  }
  if (a === 255) {
    return src | 0xff000000;
  }
  var ia = 255 - a;
  return pack(255,
    (((src >> 16) & 0xff) * a + ((dst >> 16) & 0xff) * ia) / 255,
    (((src >> 8) & 0xff) * a + ((dst >> 8) & 0xff) * ia) / 255,
    ((src & 0xff) * a + (dst & 0xff) * ia) / 255);
}

/** Replace a texel's color, keeping its alpha — used to tint the rank glyph. */
function recolor(argb, tint) {
  return (argb & 0xff000000) | (tint & 0x00ffffff);
}

function scaleColor(argb, amount) {
  return pack(255,
    ((argb >> 16) & 0xff) * amount,
    ((argb >> 8) & 0xff) * amount,
    (argb & 0xff) * amount);
}

// -------------------------------------------------------------- per-frame state
//
// Everything renderPoint needs that does not vary per point, so the trig and
// the image lookups happen once a frame rather than once an LED.

var cosT, sinT, perspK, cardW, cardH, faceUp;
var shading, aspectX;
var suitImage, rankImage, backImage, rankTint;

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (angleOverride != null) {
    cardAngleDeg = angleOverride;
  } else {
    // Knob 0.5 is still, the ends are one revolution per second either way.
    spinAngleDeg += (spin - 0.5) * 2 * 360 * deltaMs / 1000;
    cardAngleDeg = angle * 360 + spinAngleDeg;
  }

  var theta = cardAngleDeg * Math.PI / 180;
  cosT = Math.cos(theta);
  sinT = Math.sin(theta);

  // The projection is parameterized by 1/eyeDistance, so 0 falls out as a
  // clean orthographic view instead of a division by infinity.
  perspK = persp * 1.6;

  cardH = lerp(0.15, 1.15, size);
  cardW = cardH * CARD_ASPECT;

  // The visible face is the one the card's normal points away from. The normal
  // of a card rotated by theta is (-sin, 0, cos), and the eye is on +Z.
  faceUp = cosT >= 0;

  // Lambert-ish falloff, so a card turning away reads as turning rather than
  // just narrowing.
  shading = 1 - shade * (1 - Math.abs(cosT));

  aspectX = 1;
  if (autoAspect && model.yRange > 0 && model.xRange > 0) {
    aspectX = model.xRange / model.yRange;
  }

  suitImage = loadImage(SUITS[suit]);
  rankImage = loadImage(RANKS[rank]);
  backImage = loadImage("back");
  rankTint = suitTint(SUITS[suit]);
}

/**
 * Sample one sprite placed on the card.
 *
 * @param {object} image - Loaded image, or null
 * @param {number} u - Point to shade, in card-local units, origin at center
 * @param {number} v - Point to shade, in card-local units, +v is up
 * @param {number} cu - Sprite center
 * @param {number} cv - Sprite center
 * @param {number} h - Sprite height; width follows the image's pixel aspect
 * @param {boolean} rotated - Draw it upside down, for the far corner
 * @return {number} ARGB, or 0 outside the sprite
 */
function sprite(image, u, v, cu, cv, h, rotated) {
  if (image == null) {
    return 0;
  }
  var w = h * image.w / image.h;
  var du = u - cu;
  var dv = v - cv;
  if (rotated) {
    du = -du;
    dv = -dv;
  }
  var fu = du / w + 0.5;
  var fv = 0.5 - dv / h;
  if (fu < 0 || fu >= 1 || fv < 0 || fv >= 1) {
    return 0;
  }
  return texel(image, fu, fv);
}

/** The printed face: stock, then a corner index in each of two corners. */
function renderFace(u, v) {
  var color = STOCK;
  var cu = (INDEX_X - 0.5) * cardW;
  var suitV = (0.5 - SUIT_Y) * cardH;
  var rankV = (0.5 - RANK_Y) * cardH;
  var h = SPRITE_H * cardH;

  // Second pass is the same index through a point reflection: same offsets
  // negated, same art turned upside down.
  for (var i = 0; i < 2; ++i) {
    var rotated = (i === 1);
    var s = rotated ? -1 : 1;

    color = over(color, sprite(suitImage, u, v, s * cu, s * suitV, h, rotated));

    var glyph = sprite(rankImage, u, v, s * cu, s * rankV, h, rotated);
    color = over(color, tintRank ? recolor(glyph, rankTint) : glyph);
  }
  return color;
}

/** The back, drawn edge to edge across the whole card. */
function renderBack(u, v) {
  if (backImage == null) {
    return STOCK;
  }
  // The horizontal texture coordinate runs backwards along the card's own
  // u axis, which is what makes the back read the right way round on screen:
  // physically turning a card about its vertical axis reverses which way its
  // reverse side faces.
  return over(STOCK, texel(backImage, 0.5 - u / cardW, 0.5 - v / cardH));
}

/** Inside the card's rounded rectangle, in card-local units. */
function onCard(u, v) {
  var hu = cardW / 2;
  var hv = cardH / 2;
  if (u < -hu || u > hu || v < -hv || v > hv) {
    return false;
  }
  var r = CORNER_RADIUS * cardH;
  var du = Math.abs(u) - (hu - r);
  var dv = Math.abs(v) - (hv - r);
  if (du <= 0 || dv <= 0) {
    return true;
  }
  return du * du + dv * dv <= r * r;
}

function renderPoint(point, deltaMs) {
  // Screen coordinates, centered on the card and corrected so the card is not
  // stretched by a model that is wider than it is tall.
  var sx = (point.xn - posx) * aspectX;
  var sy = point.yn - posy;

  // Invert the projection. A card point (u, v) sits at world
  // (u*cos, v, u*sin), which projects to sx = u*cos / (1 - u*sin*k). Solving
  // for u gives the line below; v then scales by the same depth divisor.
  var den = cosT + sx * sinT * perspK;
  if (den === 0) {
    return BLACK;
  }
  var u = sx / den;
  var depth = 1 - u * sinT * perspK;
  if (depth <= 0) {
    // Behind the eye — the card plane has passed the viewer at this angle.
    return BLACK;
  }
  var v = sy * depth;

  if (!onCard(u, v)) {
    return BLACK;
  }

  var color = faceUp ? renderFace(u, v) : renderBack(u, v);
  return scaleColor(color, shading * level);
}
