package com.starcats.blinkydome;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * A single playing card rotating in 3D about its vertical axis, ported from
 * Scripts/PlayingCard.js.
 *
 * The card is a flat rectangle in space. Rendering is done backwards, per LED:
 * take the point's normalized position as a spot on the screen, cast a ray from
 * the eye through it, intersect that ray with the rotated card plane, and shade
 * whatever the card has at the intersection. Nothing is rasterized, so the result
 * is exact at any LED density and the perspective is real rather than faked with
 * a horizontal squash.
 *
 * The card is driven by beats, not by an angle. Each beat throws it into a spin
 * that bleeds off exponentially: Kick sets how far one beat turns it, Decay how
 * long that takes to die away, and Spin adds a constant drift underneath.
 * Nothing can assign the angle, which is the point — a card mid-flip has
 * momentum, and snapping it would throw away the state that makes the flip read
 * as a flip.
 *
 * Those beats come off the {@link PrimaryController}'s grid through a
 * {@link PrimaryController.Follower} rather than from a trigger somebody has to
 * wire up, with Phase to slide them against it. The manual Kick trigger is still
 * there and adds on top, so a hit can be thrown in over the automatic ones.
 *
 * Each time the card passes 180 degrees — square on to its back, the one instant
 * where the face is entirely hidden — the next card off a shuffled deck is dealt,
 * so the deck changes without the change ever being seen.
 *
 * Art comes from Images/Cards/ as PNGs with alpha. The face is assembled here
 * rather than loaded whole: a suit pip with its rank glyph underneath in one
 * corner, the same pair rotated 180 degrees in the other, over white stock the
 * pattern draws itself. Only three kinds of file exist: &lt;suit&gt;.png,
 * &lt;rank&gt;.png and back.png.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Playing Card")
@LXComponent.Description("A playing card flipping in 3D, thrown by the beat")
public class PlayingCardPattern extends LXPattern {

  private static final String IMAGE_DIR = "Images/Cards";

  private static final String[] SUITS = { "heart", "diamond", "club", "spade" };
  private static final String[] RANKS =
    { "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K" };

  /** Card proportions: width over height. 0.714 is a real poker card. */
  private static final double CARD_ASPECT = .714;

  // Corner index layout, in fractions of the card. Horizontal positions are
  // fractions of the width, vertical ones fractions of the height, and sprite
  // heights are fractions of the height — a sprite's width follows from its own
  // pixel aspect, so nothing dropped into the folder gets stretched.
  private static final double INDEX_X = .225;
  private static final double SUIT_Y = .15;
  private static final double RANK_Y = .34;
  private static final double SPRITE_H = .2;

  /**
   * Vertical sense of the printed face, relative to the back.
   *
   * Empirical, and I could not derive it. On paper the face and the back share
   * one vertical convention, and feeding the same bitmap through both renders it
   * the same way up in simulation. On the actual rig they disagree: with the back
   * reading correctly the face comes out flipped. Set it to 1 to collapse the two
   * paths back together if a model ever turns up where they agree.
   */
  private static final double FACE_V_SIGN = -1;

  /** Fraction of card height. */
  private static final double CORNER_RADIUS = .055;

  /** The white the card is printed on. */
  private static final int STOCK = 0xfff2f2ee;

  /**
   * A beat turns the card this many degrees at most, so Kick reads as "how far
   * does one beat throw it" — a half turn, a full turn, four turns.
   */
  private static final double KICK_MAX = 1440;

  /**
   * Bounds on the decay time constant. The floor is what keeps a hard, short
   * decay from asking for an unbounded angular velocity.
   */
  private static final double TAU_MIN = .06;
  private static final double TAU_MAX = 2.;

  public final DiscreteParameter suit =
    new DiscreteParameter("Suit", SUITS)
    .setDescription("Suit when Deal is off");

  public final DiscreteParameter rank =
    new DiscreteParameter("Rank", RANKS)
    .setDescription("Rank when Deal is off");

  public final TriggerParameter kickNow =
    new TriggerParameter("Kick", this::onBeat)
    .setDescription("Throw the card into a spin by hand, on top of the automatic beats");

  public final CompoundParameter kick =
    new CompoundParameter("Kick", .25, 0, 1)
    .setDescription("How far one beat turns the card, up to four full turns");

  public final CompoundParameter decay =
    new CompoundParameter("Decay", .3, 0, 1)
    .setDescription("How long a beat's spin takes to bleed off");

  public final CompoundParameter spin =
    new CompoundParameter("Spin", .5, 0, 1)
    .setDescription("Constant rotation underneath the beats; 0.5 is still");

  public final BooleanParameter autoBeat =
    new BooleanParameter("Auto", true)
    .setDescription("Throw the card on every beat of the controller's grid");

  public final CompoundParameter phase =
    new CompoundParameter("Phase", 0, -.5, .5)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Slide the throw earlier or later against the beat grid, in beats");

  public final BoundedParameter fallbackBpm =
    new BoundedParameter("Free BPM", 120, 40, 200)
    .setDescription("Tempo to throw at when there is no controller, or before it has found one");

  public final CompoundParameter sync =
    new CompoundParameter("Sync", 1, .1, 10)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("How long the throws take to drift back onto the controller's beat grid");

  public final CompoundParameter persp =
    new CompoundParameter("Persp", .45, 0, 1)
    .setDescription("Perspective strength; 0 is an orthographic projection");

  public final CompoundParameter size =
    new CompoundParameter("Size", .6, 0, 1)
    .setDescription("Card height as a fraction of the frame");

  public final CompoundParameter posX =
    new CompoundParameter("X", .5, 0, 1)
    .setDescription("Card center, horizontal");

  public final CompoundParameter posY =
    new CompoundParameter("Y", .5, 0, 1)
    .setDescription("Card center, vertical");

  public final CompoundParameter shade =
    new CompoundParameter("Shade", .4, 0, 1)
    .setDescription("How much the card dims as it turns edge on");

  public final CompoundParameter level =
    new CompoundParameter("Level", 1, 0, 1)
    .setDescription("Overall brightness");

  public final BooleanParameter deal =
    new BooleanParameter("Deal", true)
    .setDescription("Deal a new card each time the back turns to the viewer");

  public final BooleanParameter tintRank =
    new BooleanParameter("Tint", true)
    .setDescription("Recolor the rank glyph to the suit color");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Correct for a non-square model");

  /** A loaded sprite: dimensions and a flat ARGB array. */
  private static final class Sprite {
    final int w;
    final int h;
    final int[] px;

    Sprite(BufferedImage image) {
      this.w = image.getWidth();
      this.h = image.getHeight();
      this.px = image.getRGB(0, 0, this.w, this.h, new int[this.w * this.h], 0, this.w);
    }
  }

  private final Map<String, Sprite> imageCache = new HashMap<String, Sprite>();
  private final Map<String, Integer> tintCache = new HashMap<String, Integer>();

  // --- Card angle ------------------------------------------------------------
  //
  // Integrated, not set: a constant rate from Spin plus a decaying impulse per
  // beat. Left unwrapped, because a double holds days of spinning exactly.

  private double cardAngleDeg = 0;

  /** Degrees per second still owed by past beats. */
  private double kickVelocity = 0;

  /**
   * Beats that arrived since the last frame.
   *
   * Counted rather than applied, because the manual trigger fires from whatever
   * thread rang it and the angle belongs to the render pass. Counting also means
   * a manual kick landing in the same frame as an automatic one adds to it
   * instead of replacing it.
   */
  private int pendingBeats = 0;

  private final PrimaryController.Follower clock = new PrimaryController.Follower();
  private long lastBeatIndex = Long.MIN_VALUE;

  // --- The deck --------------------------------------------------------------
  //
  // Dealt from a shuffled 52 rather than drawn at random: random repeats itself,
  // and a card that flips to become the card it already was reads as a dropped
  // frame rather than a deal.

  private final List<Integer> deck = new ArrayList<Integer>();
  private int deckPos = 0;
  private int dealtSuit = 0;
  private int dealtRank = 0;

  /** Which 180-crossing the card is past; a change in this arms a deal. */
  private long flipIndex = backOnIndex(0);

  /** A crossing has happened and is waiting for the face to be out of sight. */
  private boolean dealPending = false;

  // Per-frame render state, so the trig and the image lookups happen once a
  // frame rather than once an LED.
  private double cosT, sinT, perspK, cardW, cardH;
  private boolean faceUp;
  private double shading, aspectX;
  private Sprite suitImage, rankImage, backImage;
  private int rankTint;

  public PlayingCardPattern(LX lx) {
    super(lx);
    addParameter("suit", this.suit);
    addParameter("rank", this.rank);
    addParameter("kickNow", this.kickNow);
    addParameter("kick", this.kick);
    addParameter("decay", this.decay);
    addParameter("spin", this.spin);
    addParameter("autoBeat", this.autoBeat);
    addParameter("phase", this.phase);
    addParameter("fallbackBpm", this.fallbackBpm);
    addParameter("sync", this.sync);
    addParameter("persp", this.persp);
    addParameter("size", this.size);
    addParameter("posX", this.posX);
    addParameter("posY", this.posY);
    addParameter("shade", this.shade);
    addParameter("level", this.level);
    addParameter("deal", this.deal);
    addParameter("tintRank", this.tintRank);
    addParameter("autoAspect", this.autoAspect);

    shuffleDeck();
    dealNext();
  }

  /** Add a beat's worth of spin. Wired to the Kick trigger; called on beats too. */
  private void onBeat() {
    ++this.pendingBeats;
  }

  @Override
  protected void run(double deltaMs) {
    this.clock.loop(deltaMs, this.fallbackBpm.getValue(), this.sync.getValue());
    collectBeat();
    advance(deltaMs / 1000);
    layout();
    draw();
  }

  /** One kick per beat of the clock, if Auto is on. */
  private void collectBeat() {
    long beatIndex = this.clock.beatIndex(this.phase.getValue());
    // Forward crossings only, as Follower.beatIndex requires: the drift
    // correction can walk the clock back over a boundary it just crossed, and
    // re-crossing should not throw the card twice for one beat.
    if (beatIndex <= this.lastBeatIndex) {
      return;
    }
    boolean first = (this.lastBeatIndex == Long.MIN_VALUE);
    this.lastBeatIndex = beatIndex;
    if (!first && this.autoBeat.isOn()) {
      onBeat();
    }
  }

  /**
   * Move the card forward by dt seconds.
   *
   * A beat sets the card spinning and the spin bleeds away exponentially, so the
   * angle over one beat is the integral of a decaying exponential — which has a
   * closed form, used here rather than stepped. That makes the turn per beat
   * exactly the Kick knob's value regardless of frame rate, and it stays exact
   * when the engine hitches.
   *
   * Velocity is derived from the distance rather than dialed directly for the
   * same reason: total turn is v0 * tau, so scaling v0 by 1/tau leaves Kick
   * meaning "degrees per beat" no matter where Decay sits.
   */
  private void advance(double dt) {
    if (!Double.isFinite(dt)) {
      dt = 0;
    }
    // tau is a divisor, so it gets a floor rather than a check: a zero would turn
    // a beat into infinite velocity rather than a fast one.
    double tau = clamp(lerp(TAU_MIN, TAU_MAX, this.decay.getValue()), TAU_MIN, TAU_MAX);

    while (this.pendingBeats > 0) {
      --this.pendingBeats;
      this.kickVelocity += this.kick.getValue() * KICK_MAX / tau;
    }

    double remaining = Math.exp(-dt / tau);
    this.cardAngleDeg += (this.spin.getValue() - .5) * 2 * 360 * dt
      + this.kickVelocity * tau * (1 - remaining);
    this.kickVelocity *= remaining;

    // Both of these feed back into themselves every frame, so a NaN is not a
    // glitch that passes — it is a state the card never leaves, and it fails
    // silently: every comparison against NaN is false, so onCard() rejects every
    // point and the card does not draw wrong, it just vanishes.
    if (!Double.isFinite(this.cardAngleDeg) || !Double.isFinite(this.kickVelocity)) {
      this.cardAngleDeg = 0;
      this.kickVelocity = 0;
      // Resync the crossing counter too, or the jump back to zero reads as a
      // flip and deals a card the viewer never saw turn.
      this.flipIndex = backOnIndex(this.cardAngleDeg);
      this.dealPending = false;
    }

    long index = backOnIndex(this.cardAngleDeg);
    if (index != this.flipIndex) {
      this.flipIndex = index;
      this.dealPending = true;
    }

    // Arming the deal on the crossing but holding it until the back is actually
    // toward the viewer: a beat hard enough to turn the card several times inside
    // one frame can land the crossing anywhere, and swapping the face while it is
    // in view is the one thing this must never do.
    if (this.dealPending && Math.cos(Math.toRadians(this.cardAngleDeg)) < 0) {
      this.dealPending = false;
      dealNext();
    }
  }

  /**
   * How many back-on angles — 180, 540, 900 ... — the card has passed.
   *
   * Counting rather than testing a window means a beat violent enough to spin the
   * card several times in one frame still registers as having flipped.
   */
  private static long backOnIndex(double deg) {
    return (long) Math.floor((deg - 180) / 360);
  }

  private void shuffleDeck() {
    this.deck.clear();
    for (int i = 0; i < SUITS.length * RANKS.length; ++i) {
      this.deck.add(i);
    }
    Collections.shuffle(this.deck);
    this.deckPos = 0;
  }

  private void dealNext() {
    if (this.deckPos >= this.deck.size()) {
      shuffleDeck();
    }
    int card = this.deck.get(this.deckPos++);
    this.dealtSuit = card % SUITS.length;
    this.dealtRank = card / SUITS.length;
  }

  private void layout() {
    double theta = Math.toRadians(this.cardAngleDeg);
    this.cosT = Math.cos(theta);
    this.sinT = Math.sin(theta);

    // The projection is parameterized by 1/eyeDistance, so 0 falls out as a clean
    // orthographic view instead of a division by infinity.
    this.perspK = this.persp.getValue() * 1.6;

    this.cardH = lerp(.15, 1.15, this.size.getValue());
    this.cardW = this.cardH * CARD_ASPECT;

    // The visible face is the one the card's normal points away from. The normal
    // of a card rotated by theta is (-sin, 0, cos), and the eye is on +Z.
    this.faceUp = (this.cosT >= 0);

    // Lambert-ish falloff, so a card turning away reads as turning rather than
    // just narrowing.
    this.shading = 1 - this.shade.getValue() * (1 - Math.abs(this.cosT));

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.yRange > 0 && this.model.xRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    boolean dealing = this.deal.isOn();
    String suitName = SUITS[dealing ? this.dealtSuit : this.suit.getValuei()];
    this.suitImage = loadImage(suitName);
    this.rankImage = loadImage(RANKS[dealing ? this.dealtRank : this.rank.getValuei()]);
    this.backImage = loadImage("back");
    this.rankTint = suitTint(suitName);
  }

  private void draw() {
    final double brightness = this.shading * this.level.getValue();
    final double px = this.posX.getValue();
    final double py = this.posY.getValue();

    for (LXPoint p : this.model.points) {
      // Screen coordinates, centered on the card and corrected so the card is not
      // stretched by a model that is wider than it is tall.
      double sx = (p.xn - px) * this.aspectX;
      double sy = p.yn - py;

      // Invert the projection. A card point (u, v) sits at world
      // (u*cos, v, u*sin), which projects to sx = u*cos / (1 - u*sin*k). Solving
      // for u gives the line below; v then scales by the same depth divisor.
      double den = this.cosT + sx * this.sinT * this.perspK;
      if (den == 0) {
        this.colors[p.index] = LXColor.BLACK;
        continue;
      }
      double u = sx / den;
      double depth = 1 - u * this.sinT * this.perspK;
      if (depth <= 0) {
        // Behind the eye — the card plane has passed the viewer at this angle.
        this.colors[p.index] = LXColor.BLACK;
        continue;
      }
      double v = sy * depth;

      if (!onCard(u, v)) {
        this.colors[p.index] = LXColor.BLACK;
        continue;
      }

      int color = this.faceUp ? renderFace(u, v) : renderBack(u, v);
      this.colors[p.index] = scaleColor(color, brightness);
    }
  }

  /** The printed face: stock, then a corner index in each of two corners. */
  private int renderFace(double u, double v) {
    v *= FACE_V_SIGN;
    int color = STOCK;
    double cu = (INDEX_X - .5) * this.cardW;
    double suitV = (.5 - SUIT_Y) * this.cardH;
    double rankV = (.5 - RANK_Y) * this.cardH;
    double h = SPRITE_H * this.cardH;

    // Second pass is the same index through a point reflection: same offsets
    // negated, same art turned upside down.
    for (int i = 0; i < 2; ++i) {
      boolean rotated = (i == 1);
      double s = rotated ? -1 : 1;

      color = over(color, sprite(this.suitImage, u, v, s * cu, s * suitV, h, rotated));

      int glyph = sprite(this.rankImage, u, v, s * cu, s * rankV, h, rotated);
      color = over(color, this.tintRank.isOn() ? recolor(glyph, this.rankTint) : glyph);
    }
    return color;
  }

  /** The back, drawn edge to edge across the whole card. */
  private int renderBack(double u, double v) {
    if (this.backImage == null) {
      return STOCK;
    }
    // The horizontal texture coordinate runs backwards along the card's own u
    // axis, which is what makes the back read the right way round on screen:
    // physically turning a card about its vertical axis reverses which way its
    // reverse side faces.
    return over(STOCK, texel(this.backImage, .5 - u / this.cardW, .5 - v / this.cardH));
  }

  /** Inside the card's rounded rectangle, in card-local units. */
  private boolean onCard(double u, double v) {
    double hu = this.cardW / 2;
    double hv = this.cardH / 2;
    if (u < -hu || u > hu || v < -hv || v > hv) {
      return false;
    }
    double r = CORNER_RADIUS * this.cardH;
    double du = Math.abs(u) - (hu - r);
    double dv = Math.abs(v) - (hv - r);
    if (du <= 0 || dv <= 0) {
      return true;
    }
    return du * du + dv * dv <= r * r;
  }

  /**
   * Sample one sprite placed on the card.
   *
   * @param image loaded sprite, or null
   * @param u point to shade, in card-local units, origin at center
   * @param v point to shade, in card-local units, +v is up
   * @param cu sprite center
   * @param cv sprite center
   * @param h sprite height; width follows the image's own pixel aspect
   * @param rotated draw it upside down, for the far corner
   * @return ARGB, or 0 outside the sprite
   */
  private static int sprite(Sprite image, double u, double v,
      double cu, double cv, double h, boolean rotated) {
    if (image == null) {
      return 0;
    }
    double w = h * image.w / image.h;
    double du = u - cu;
    double dv = v - cv;
    if (rotated) {
      du = -du;
      dv = -dv;
    }
    double fu = du / w + .5;
    double fv = .5 - dv / h;
    if (fu < 0 || fu >= 1 || fv < 0 || fv >= 1) {
      return 0;
    }
    return texel(image, fu, fv);
  }

  /** Nearest-neighbor texel at (fu, fv) in 0..1, image space, y down. */
  private static int texel(Sprite image, double fu, double fv) {
    int x = (int) (fu * image.w);
    int y = (int) (fv * image.h);
    if (x < 0) {
      x = 0;
    } else if (x >= image.w) {
      x = image.w - 1;
    }
    if (y < 0) {
      y = 0;
    } else if (y >= image.h) {
      y = image.h - 1;
    }
    return image.px[y * image.w + x];
  }

  /** Source over destination. Destination is opaque, so the result is too. */
  private static int over(int dst, int src) {
    int a = (src >>> 24) & 0xff;
    if (a == 0) {
      return dst;
    }
    if (a == 255) {
      return src | 0xff000000;
    }
    int ia = 255 - a;
    return pack(255,
      (((src >> 16) & 0xff) * a + ((dst >> 16) & 0xff) * ia) / 255,
      (((src >> 8) & 0xff) * a + ((dst >> 8) & 0xff) * ia) / 255,
      ((src & 0xff) * a + (dst & 0xff) * ia) / 255);
  }

  /** Replace a texel's color, keeping its alpha — used to tint the rank glyph. */
  private static int recolor(int argb, int tint) {
    return (argb & 0xff000000) | (tint & 0x00ffffff);
  }

  private static int scaleColor(int argb, double amount) {
    return pack(255,
      (int) (((argb >> 16) & 0xff) * amount),
      (int) (((argb >> 8) & 0xff) * amount),
      (int) ((argb & 0xff) * amount));
  }

  private static int pack(int a, int r, int g, int b) {
    return ((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
  }

  /**
   * Load Images/Cards/&lt;name&gt;.png, or null if it is not there.
   *
   * Misses are cached too, so a missing file costs one failed lookup rather than
   * one per point per frame.
   */
  private Sprite loadImage(String name) {
    if (this.imageCache.containsKey(name)) {
      return this.imageCache.get(name);
    }
    Sprite sprite = null;
    File file = this.lx.getMediaFile(IMAGE_DIR + "/" + name + ".png");
    try {
      BufferedImage image = ImageIO.read(file);
      if (image != null) {
        sprite = new Sprite(image);
      } else {
        LX.error("PlayingCardPattern: cannot read " + file);
      }
    } catch (IOException iox) {
      LX.error(iox, "PlayingCardPattern: cannot read " + file);
    }
    this.imageCache.put(name, sprite);
    return sprite;
  }

  /**
   * The color of a suit, taken from its own artwork.
   *
   * An alpha-weighted mean over the pip, so the rank glyph tints to match
   * whatever art is in the folder instead of a hard-coded table.
   */
  private int suitTint(String name) {
    Integer cached = this.tintCache.get(name);
    if (cached != null) {
      return cached.intValue();
    }
    Sprite image = loadImage(name);
    int tint = STOCK;
    if (image != null) {
      long sumA = 0, sumR = 0, sumG = 0, sumB = 0;
      for (int i = 0; i < image.px.length; ++i) {
        int argb = image.px[i];
        int a = (argb >>> 24) & 0xff;
        sumA += a;
        sumR += ((argb >> 16) & 0xff) * a;
        sumG += ((argb >> 8) & 0xff) * a;
        sumB += (argb & 0xff) * a;
      }
      if (sumA > 0) {
        tint = pack(255, (int) (sumR / sumA), (int) (sumG / sumA), (int) (sumB / sumA));
      }
    }
    this.tintCache.put(name, tint);
    return tint;
  }

  private static double clamp(double v, double min, double max) {
    return (v < min) ? min : (v > max) ? max : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
