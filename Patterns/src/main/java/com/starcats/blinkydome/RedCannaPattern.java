package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Red Canna — petal folds after Georgia O'Keeffe's flower close-ups. Ported from
 * Scripts/RedCanna.js.
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
 * before turning over at the tip, and a ripple along the edge. The angle is
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
@LXCategory("Blinky Dome")
@LXComponent.Name("Red Canna")
@LXComponent.Description("Scrolling petal folds after O'Keeffe's Red Canna")
public class RedCannaPattern extends LXPattern {

  private static final double TAU = Math.PI * 2;

  /** Ring capacity. Generous against the densest, slowest-retiring settings. */
  private static final int MAX_FOLDS = 128;

  /** A fold is retired once it has descended this far past the bottom. */
  private static final double RETIRE_DEPTH = 1.45;

  /** Depth slabs used to find the folds covering an LED. */
  private static final int SLAB_COUNT = 96;
  private static final double SLAB_LO = -.25;
  private static final double SLAB_HI = 1.5;

  /** Span at birth, as a fraction of frame width. */
  private static final double SPAN_BIRTH_MIN = .02;
  private static final double SPAN_BIRTH_MAX = .06;

  /** Span at maturity, before the Span knob scales it. */
  private static final double SPAN_MATURE_MIN = .15;
  private static final double SPAN_MATURE_MAX = .55;

  /** Depth by which a fold has finished opening. Varies per fold. */
  private static final double MATURE_DEPTH_MIN = .28;
  private static final double MATURE_DEPTH_MAX = .62;

  /** Color coordinate drawn per fold, read through the palette ramp. */
  private static final double COLOR_MIN = .3;
  private static final double COLOR_MAX = 1;

  /** How far the drifting color walk can move between one fold and the next. */
  private static final double COLOR_STEP = .24;
  private static final double COLOR_JITTER = .05;

  /** The palette ramp: gold at 0, walking down through red into magenta. */
  private static final double HUE_ORIGIN = 55;

  /** How much of the fold, from its top edge, the light envelope covers. */
  private static final double RIM_INNER = .68;
  private static final double RIM_GAIN = .3;

  /** Falloff outward along the fold and downward across it. */
  private static final double SHADE_GAIN = .2;
  private static final double SHADE_OUTWARD = .35;
  private static final double SHADE_DOWNWARD = .65;

  /** Where the light envelope starts bleaching toward white. */
  private static final double WHITEN_KNEE = .72;
  private static final double WHITEN = .6;

  /**
   * How far a fold reaches back across the contour, as a fraction of its span,
   * and how fat it still is where it crosses. Folds have to interlock over the
   * center line rather than all stopping on it, or their bases stack into a
   * hard vertical seam down the middle of the flower.
   */
  private static final double BASE_BLEED = .3;

  /**
   * Fullness through the middle of the fold. At 0 the lobe is a plain ellipse;
   * raising it carries the fold's height further out before it turns over,
   * which is the difference between a petal and a wedge.
   */
  private static final double PETAL_BELLY = .45;

  /** Ruffle along a fold's edge: cycles across the span, and its depth. */
  private static final double RUFFLE_CYCLES = 2.8;
  private static final double RUFFLE_MAX = .3;

  /** Lateral tightness of the glow the ground keeps along the contour. */
  private static final double GROUND_TIGHT = 5;
  private static final double GROUND_HUE = 340;

  /** How the contour's two sinusoids drift, in radians per second. */
  private static final double DRIFT_1 = .1;
  private static final double DRIFT_2 = -.147;

  /**
   * How steeply a fold may leave the contour, up or down, before Fan scales it.
   * Without this every fold departs horizontally and the flower stacks into a
   * layer cake; this is what makes the folds radiate.
   */
  private static final double TILT_MAX = .8;

  /** Chance a new fold goes to the opposite side of the contour from the last. */
  private static final double ALTERNATE_CHANCE = .66;

  /**
   * How far a fold's anchor may slip off the even spacing, as a fraction of it.
   * Kept under a half so birth order still tracks depth and the slab runs stay
   * front to back; two folds that did swap would be a hair apart anyway.
   */
  private static final double SPACING_JITTER = .32;

  public final CompoundParameter scroll =
    new CompoundParameter("Scroll", .3, 0, 1)
    .setDescription("Pans the camera up the flower; 0 holds the scene still");

  public final CompoundParameter density =
    new CompoundParameter("Density", .5, 0, 1)
    .setDescription("Folds on screen at once; center is about 35");

  public final CompoundParameter span =
    new CompoundParameter("Span", .5, 0, 1)
    .setDescription("Scales how wide a fold opens; center is 15% to 55%");

  public final CompoundParameter contour =
    new CompoundParameter("Contour", .4, 0, 1)
    .setDescription("Cycles the center line runs through down the frame");

  public final CompoundParameter sway =
    new CompoundParameter("Sway", .6, 0, 1)
    .setDescription("How far the center line swings off center");

  public final CompoundParameter detune =
    new CompoundParameter("Detune", .4, 0, 1)
    .setDescription("How far the second sinusoid sits from the first");

  public final CompoundParameter fan =
    new CompoundParameter("Fan", .55, 0, 1)
    .setDescription("How steeply folds leave the contour, up and down");

  public final CompoundParameter curl =
    new CompoundParameter("Curl", .5, 0, 1)
    .setDescription("How far a fold arcs downward as it reaches out");

  public final CompoundParameter depth =
    new CompoundParameter("Fold Depth", .5, 0, 1)
    .setDescription("Height of a fold against its own width");

  public final CompoundParameter ruffle =
    new CompoundParameter("Ruffle", .36, 0, 1)
    .setDescription("Waviness of a fold's edge");

  public final CompoundParameter soft =
    new CompoundParameter("Softness", .28, 0, 1)
    .setDescription("Edge softness of a fold");

  public final CompoundParameter lum =
    new CompoundParameter("Luminance", .5, 0, 1)
    .setDescription("Brightness of a fold's body, before the envelope");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 1)
    .setDescription("Rotates the whole palette");

  public final CompoundParameter range =
    new CompoundParameter("Hue Range", .6, 0, 1)
    .setDescription("How far the palette walks from gold toward magenta");

  public final CompoundParameter drift =
    new CompoundParameter("Color Drift", .4, 0, 1)
    .setDescription("Ties a fold's color to its neighbors'; 0 draws each independently");

  public final CompoundParameter sat =
    new CompoundParameter("Saturation", .82, 0, 1)
    .setDescription("Saturation of the palette");

  public final CompoundParameter ground =
    new CompoundParameter("Ground", .4, 0, 1)
    .setDescription("Level of the dark field the folds sit in");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Keep folds square on a non-square model");

  // Per fold, fixed at birth.
  private final double[] fWorld = new double[MAX_FOLDS];
  private final int[] fSide = new int[MAX_FOLDS];
  private final double[] fSpanBirth = new double[MAX_FOLDS];
  private final double[] fSpanMature = new double[MAX_FOLDS];
  private final double[] fMatureDepth = new double[MAX_FOLDS];
  private final double[] fColor = new double[MAX_FOLDS];
  private final double[] fLevelJitter = new double[MAX_FOLDS];
  private final double[] fCurl = new double[MAX_FOLDS];
  private final double[] fTilt = new double[MAX_FOLDS];
  private final double[] fDepth = new double[MAX_FOLDS];
  private final double[] fRuffle = new double[MAX_FOLDS];
  private final double[] fSatJitter = new double[MAX_FOLDS];

  // Per fold, resolved once per frame.
  private final double[] fP = new double[MAX_FOLDS];
  private final double[] fX = new double[MAX_FOLDS];
  private final double[] fSpan = new double[MAX_FOLDS];
  private final double[] fHalfH = new double[MAX_FOLDS];
  private final double[] fCurlAmt = new double[MAX_FOLDS];
  private final double[] fTiltAmt = new double[MAX_FOLDS];
  private final double[] fArcLo = new double[MAX_FOLDS];
  private final double[] fArcHi = new double[MAX_FOLDS];
  private final double[] fHue = new double[MAX_FOLDS];
  private final double[] fSat = new double[MAX_FOLDS];
  private final double[] fBase = new double[MAX_FOLDS];

  // Live folds are the counters [foldFirst, foldNext), oldest first. Counters
  // are absolute and only ever increase; the arrays above are indexed modulo
  // capacity.
  private int foldFirst = 0;
  private int foldNext = 0;
  private int lastSide = 1;

  // A slow reflecting walk over the palette, so consecutive folds land in the
  // same family and the color drifts across the procession rather than
  // shimmering fold to fold. Color Drift decides how much of it a fold takes.
  private double colorWalk = .65;

  // Contiguous run of fold counters touching each depth slab.
  private final int[] slabLo = new int[SLAB_COUNT];
  private final int[] slabHi = new int[SLAB_COUNT];
  private static final double SLAB_SCALE = SLAB_COUNT / (SLAB_HI - SLAB_LO);

  // Camera position down the flower, in frame heights. Never wrapped.
  private double camera = 0;
  private double spawnedTo = 0;

  // Per-frame scene values.
  private double spineA1 = 0;
  private double spineA2 = 0;
  private double spineK1 = TAU;
  private double spineK2 = TAU;
  private double spinePhase1 = 0;
  private double spinePhase2 = 0;
  private double ruffleAmt = 0;
  private double edgeSoft = .1;
  private static final double RIM_SCALE = 1 / (1 - RIM_INNER);
  private double groundLevel = 0;
  private double groundSat = .8;
  private boolean seeded = false;

  // Scratch for the one color conversion per fold per LED.
  private double outR = 0;
  private double outG = 0;
  private double outB = 0;

  public RedCannaPattern(LX lx) {
    super(lx);
    addParameter("scroll", this.scroll);
    addParameter("density", this.density);
    addParameter("span", this.span);
    addParameter("contour", this.contour);
    addParameter("sway", this.sway);
    addParameter("detune", this.detune);
    addParameter("fan", this.fan);
    addParameter("curl", this.curl);
    addParameter("depth", this.depth);
    addParameter("ruffle", this.ruffle);
    addParameter("soft", this.soft);
    addParameter("lum", this.lum);
    addParameter("hue", this.hue);
    addParameter("range", this.range);
    addParameter("drift", this.drift);
    addParameter("sat", this.sat);
    addParameter("ground", this.ground);
    addParameter("autoAspect", this.autoAspect);
  }

  @Override
  protected void run(double deltaMs) {
    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs * .001, 0, .25) : 0;

    this.spinePhase1 += DRIFT_1 * dt;
    this.spinePhase2 += DRIFT_2 * dt;

    double swayAmp = lerp(0, .32, this.sway.getValue());
    this.spineA1 = swayAmp * .62;
    this.spineA2 = swayAmp * .38;
    this.spineK1 = TAU * lerp(.3, 2.2, this.contour.getValue());
    this.spineK2 = this.spineK1 * lerp(1.05, 2.2, this.detune.getValue());

    this.ruffleAmt = RUFFLE_MAX * this.ruffle.getValue();
    this.edgeSoft = Math.max(lerp(.02, .35, this.soft.getValue()), 1e-3);
    this.groundLevel = lerp(0, .5, this.ground.getValue());
    this.groundSat = lerp(.3, .95, this.sat.getValue());

    // One fold every `spacing` of travel puts `1 / spacing` of them on screen.
    double spacing = 1 / lerp(20, 50, this.density.getValue());

    this.camera += lerp(0, .9, this.scroll.getValue()) * dt;

    // Backdating the first frame's spawn point by a whole procession is what
    // makes the flower load already full instead of growing in from nothing. It
    // is also the stall guard: an engine that froze for a second owes the same
    // debt, and paying more than a screenful of it would be a burst, not a
    // catch up.
    if (!this.seeded) {
      this.seeded = true;
      this.spawnedTo = this.camera - RETIRE_DEPTH;
    }
    if (this.camera - this.spawnedTo > RETIRE_DEPTH) {
      this.spawnedTo = this.camera - RETIRE_DEPTH;
    }
    while (this.spawnedTo + spacing <= this.camera) {
      this.spawnedTo += spacing;
      spawn(this.spawnedTo, spacing);
    }

    while (this.foldFirst < this.foldNext
        && this.camera - this.fWorld[this.foldFirst % MAX_FOLDS] > RETIRE_DEPTH) {
      ++this.foldFirst;
    }

    resolveFolds();
    buildSlabs();
    draw();
  }

  /** The contour: two detuned sinusoids in the scroll coordinate. */
  private double spineX(double w) {
    return .5 +
      this.spineA1 * Math.sin(this.spineK1 * w + this.spinePhase1) +
      this.spineA2 * Math.sin(this.spineK2 * w + this.spinePhase2);
  }

  private void spawn(double world, double spacing) {
    // Overflow can only happen if the ring is undersized for the settings; drop
    // the oldest rather than overwrite a fold that is still on screen.
    while (this.foldNext - this.foldFirst >= MAX_FOLDS) {
      ++this.foldFirst;
    }

    int i = this.foldNext % MAX_FOLDS;
    // Even spacing sets the density; the slip off it keeps the procession from
    // marching in step.
    this.fWorld[i] = world + randomRange(-SPACING_JITTER, SPACING_JITTER) * spacing;

    // Mostly alternating, so both sides of the contour keep filling, but not so
    // regularly that the procession reads as a zipper.
    this.lastSide = (Math.random() < ALTERNATE_CHANCE) ? -this.lastSide : this.lastSide;
    this.fSide[i] = this.lastSide;

    this.fSpanBirth[i] = randomRange(SPAN_BIRTH_MIN, SPAN_BIRTH_MAX);
    this.fSpanMature[i] = randomRange(SPAN_MATURE_MIN, SPAN_MATURE_MAX);
    this.fMatureDepth[i] = randomRange(MATURE_DEPTH_MIN, MATURE_DEPTH_MAX);

    this.colorWalk = reflect(this.colorWalk + randomRange(-COLOR_STEP, COLOR_STEP));
    double drawn = lerp(Math.random(), this.colorWalk, this.drift.getValue()) +
      randomRange(-COLOR_JITTER, COLOR_JITTER);
    this.fColor[i] = COLOR_MIN + (COLOR_MAX - COLOR_MIN) * clamp(drawn, 0, 1);

    this.fLevelJitter[i] = randomRange(.86, 1);
    this.fSatJitter[i] = randomRange(.88, 1);
    // Mostly sweeping down, occasionally lifting, which is what keeps the stack
    // from reading as a single fan.
    this.fCurl[i] = randomRange(-.12, .55);
    this.fTilt[i] = randomRange(-TILT_MAX, TILT_MAX);
    this.fDepth[i] = randomRange(.22, .4);
    this.fRuffle[i] = Math.random() * TAU;

    ++this.foldNext;
  }

  private void resolveFolds() {
    // Span is a fraction of width and depth is a fraction of height, so a fold
    // is only square on a square model unless the ratio is put back in.
    double aspect = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      aspect = this.model.xRange / this.model.yRange;
    }

    double spanScale = lerp(.4, 1.6, this.span.getValue());
    double curlScale = lerp(0, 2, this.curl.getValue());
    double tiltScale = this.fan.getValue();
    double depthScale = lerp(.45, 1.8, this.depth.getValue());
    double baseLevel = lerp(.25, .85, this.lum.getValue());
    double hueSpan = lerp(20, 150, this.range.getValue());
    double hueOffset = this.hue.getValue() * 360;
    double satBase = lerp(.35, 1, this.sat.getValue());

    for (int k = this.foldFirst; k < this.foldNext; ++k) {
      int i = k % MAX_FOLDS;
      double p = this.camera - this.fWorld[i];
      this.fP[i] = p;

      // The only thing a fold animates: it eases open as the camera passes it.
      double t = clamp(p / this.fMatureDepth[i], 0, 1);
      double grown = t * t * (3 - 2 * t);
      double birth = this.fSpanBirth[i];
      double s = birth + (this.fSpanMature[i] * spanScale - birth) * grown;
      if (s < birth) {
        s = birth;
      }
      this.fSpan[i] = s;

      this.fX[i] = spineX(this.fWorld[i]);
      this.fHalfH[i] = s * this.fDepth[i] * depthScale * aspect;

      // The fold's center line, tilt*a + curl*a*a across a running 0 to 1. Its
      // extremes are the two ends and, if the parabola turns inside the fold,
      // the vertex — the slab table needs all three or a fold gets clipped.
      double tiltAmt = s * this.fTilt[i] * tiltScale * aspect;
      double curlAmt = s * this.fCurl[i] * curlScale * aspect;
      this.fTiltAmt[i] = tiltAmt;
      this.fCurlAmt[i] = curlAmt;

      double end = tiltAmt + curlAmt;
      double arcLo = (end < 0) ? end : 0;
      double arcHi = (end > 0) ? end : 0;
      if (curlAmt != 0) {
        double vertex = -tiltAmt / (2 * curlAmt);
        if (vertex > 0 && vertex < 1) {
          double v = tiltAmt * vertex + curlAmt * vertex * vertex;
          if (v < arcLo) {
            arcLo = v;
          } else if (v > arcHi) {
            arcHi = v;
          }
        }
      }
      this.fArcLo[i] = arcLo;
      this.fArcHi[i] = arcHi;

      double h = (HUE_ORIGIN + hueOffset - this.fColor[i] * hueSpan) % 360;
      this.fHue[i] = (h < 0) ? h + 360 : h;
      this.fSat[i] = satBase * this.fSatJitter[i];
      this.fBase[i] = baseLevel * this.fLevelJitter[i];
    }
  }

  /**
   * The run of fold counters touching each slab. Marking slabs while walking
   * the folds in birth order leaves every run contiguous by construction, and
   * because depth tracks birth order the run is already sorted front to back.
   */
  private void buildSlabs() {
    for (int s = 0; s < SLAB_COUNT; ++s) {
      this.slabLo[s] = 0;
      this.slabHi[s] = -1;
    }

    for (int k = this.foldFirst; k < this.foldNext; ++k) {
      int i = k % MAX_FOLDS;
      // The profile peaks at exactly the nominal half height; only the ruffle
      // reaches past it, and the slabs have to reserve room for that.
      double half = this.fHalfH[i] * (1 + this.ruffleAmt);
      double pMin = this.fP[i] - half + this.fArcLo[i];
      double pMax = this.fP[i] + half + this.fArcHi[i];

      int lo = (int) Math.floor((pMin - SLAB_LO) * SLAB_SCALE);
      int hi = (int) Math.floor((pMax - SLAB_LO) * SLAB_SCALE);
      if (hi < 0 || lo >= SLAB_COUNT) {
        continue;
      }
      if (lo < 0) {
        lo = 0;
      }
      if (hi >= SLAB_COUNT) {
        hi = SLAB_COUNT - 1;
      }
      for (int b = lo; b <= hi; ++b) {
        if (this.slabHi[b] < this.slabLo[b]) {
          this.slabLo[b] = k;
        }
        this.slabHi[b] = k;
      }
    }
  }

  private void draw() {
    for (LXPoint point : this.model.points) {
      double xn = point.xn;
      // Depth into the picture: 0 at the top of the frame, which is the back.
      double p = 1 - point.yn;

      double accR = 0;
      double accG = 0;
      double accB = 0;
      double trans = 1;

      int slab = (int) Math.floor((p - SLAB_LO) * SLAB_SCALE);
      if (slab >= 0 && slab < SLAB_COUNT) {
        int lo = this.slabLo[slab];
        int hi = this.slabHi[slab];

        for (int k = lo; k <= hi; ++k) {
          int i = k % MAX_FOLDS;

          // Lateral distance from the contour, signed into the fold's own side,
          // as a fraction of the span. Negative is the part reaching back
          // across.
          double s = this.fSpan[i];
          double a = (xn - this.fX[i]) * this.fSide[i] / s;
          if (a >= 1 || a <= -BASE_BLEED) {
            continue;
          }

          // Full where it crosses the contour, carrying that height out through
          // the middle before turning over at the tip, rounded off where it
          // reaches back across, with the edge rippled hardest where the fold
          // is widest.
          double profile;
          if (a > 0) {
            double e = 1 - a * a;
            profile = Math.sqrt(e) * (1 - PETAL_BELLY + PETAL_BELLY * e);
          } else {
            double r = a / BASE_BLEED;
            profile = Math.sqrt(1 - r * r);
          }
          double ripple = (a > 0) ? 4 * a * (1 - a) : 0;
          double hh = this.fHalfH[i] * profile *
            (1 + this.ruffleAmt * ripple * Math.sin(RUFFLE_CYCLES * TAU * a + this.fRuffle[i]));
          if (hh <= 0) {
            continue;
          }

          // Height across the fold, measured from its own arcing center line.
          // The arc and the falloff both run from the contour outward, so the
          // part reaching back across the middle rides at the base value.
          double out = (a > 0) ? a : 0;
          double n = (p - this.fP[i] - this.fTiltAmt[i] * out
            - this.fCurlAmt[i] * out * out) / hh;
          double away = (n < 0) ? -n : n;
          if (away >= 1) {
            continue;
          }

          double alpha = (1 - away) / this.edgeSoft;
          if (alpha > 1) {
            alpha = 1;
          }

          // The top-side envelope, brightest exactly at the upper edge.
          double rimT = (-n - RIM_INNER) * RIM_SCALE;
          double rim = (rimT <= 0) ? 0 : ((rimT >= 1) ? 1 : rimT * rimT * (3 - 2 * rimT));

          // Falling off outward along the fold and downward across it.
          double shade = SHADE_OUTWARD * out + SHADE_DOWNWARD * (n + 1) * .5;

          double b = this.fBase[i] + RIM_GAIN * rim - SHADE_GAIN * shade;
          if (b > 1) {
            b = 1;
          } else if (b < 0) {
            b = 0;
          }

          // Light bleaches toward white rather than staying a saturated hue.
          double sat0 = this.fSat[i];
          if (b > WHITEN_KNEE) {
            sat0 *= 1 - WHITEN * (b - WHITEN_KNEE) / (1 - WHITEN_KNEE);
          }

          hsbToRgb(this.fHue[i], sat0, b);

          double weight = alpha * trans;
          accR += this.outR * weight;
          accG += this.outG * weight;
          accB += this.outB * weight;
          trans -= weight;
          if (trans < .004) {
            trans = 0;
            break;
          }
        }
      }

      if (trans > 0) {
        // Whatever the folds left uncovered is the flower's own shadowed depth,
        // which keeps a little glow along the contour.
        double d = xn - spineX(this.camera - p);
        double glow = 1 / (1 + d * d * GROUND_TIGHT);
        hsbToRgb(GROUND_HUE, this.groundSat, this.groundLevel * (.35 + .65 * glow));
        accR += this.outR * trans;
        accG += this.outG * trans;
        accB += this.outB * trans;
      }

      this.colors[point.index] = LXColor.rgb(
        (accR > 255) ? 255 : (int) accR,
        (accG > 255) ? 255 : (int) accG,
        (accB > 255) ? 255 : (int) accB
      );
    }
  }

  /** Hue in degrees, saturation and value in 0..1, into outR/outG/outB in 0..255. */
  private void hsbToRgb(double h, double s, double v) {
    double value = v * 255;
    if (s <= 0) {
      this.outR = value;
      this.outG = value;
      this.outB = value;
      return;
    }

    double sector = h / 60;
    int index = (int) Math.floor(sector);
    double f = sector - index;
    double q = value * (1 - s);
    double w = value * (1 - s * f);
    double t = value * (1 - s * (1 - f));

    switch (Math.floorMod(index, 6)) {
      case 0: this.outR = value; this.outG = t; this.outB = q; break;
      case 1: this.outR = w; this.outG = value; this.outB = q; break;
      case 2: this.outR = q; this.outG = value; this.outB = t; break;
      case 3: this.outR = q; this.outG = w; this.outB = value; break;
      case 4: this.outR = t; this.outG = q; this.outB = value; break;
      default: this.outR = value; this.outG = q; this.outB = w; break;
    }
  }

  private static double randomRange(double lo, double hi) {
    return lo + Math.random() * (hi - lo);
  }

  /** Folds a value back into 0..1 rather than clamping, so a walk keeps moving. */
  private static double reflect(double v) {
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

  private static double clamp(double v, double low, double high) {
    return (v < low) ? low : (v > high) ? high : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
