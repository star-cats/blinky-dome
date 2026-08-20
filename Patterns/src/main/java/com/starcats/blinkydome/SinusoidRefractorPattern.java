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
 * A vertical sinusoid with eight more hidden inside it, which fan out, detune
 * and scatter as Spread opens. Ported from Scripts/SinusoidRefractor.js.
 *
 * Everything is written in the wave's own frame, where a sinusoid is just
 *
 *   y = a1*sin(k1*x + phase1) + a2*sin(k2*x + phase2)
 *
 * and nothing else. There is no vertical special case and no rotation inside
 * that function, because the LED's coordinates are transformed by the inverse
 * of the scene rotation before it is ever called — the scene turns by turning
 * the points the other way, and what the curve sees is always a plain x running
 * along it and a plain y across it. A vertical sinusoid is that same canonical
 * curve looked at sideways, which is the last quarter turn of the transform.
 *
 * Two waves, not one: the second runs the other way, so what is drawn is a
 * partial standing wave whose crests swell and stall rather than simply march.
 *
 * Each oscillator owns its parameters, and its own two clocks, which integrate
 * its own rates every frame at full deviation, always. Detune sets its k,
 * Scatter its phases, Speed Spread its rates. None of them is scaled on its way
 * into anything — they define what this oscillator IS.
 *
 * Spread alone decides how much of that is seen. Every oscillator evaluates
 * twice per LED, once with the central oscillator's parameters and once with
 * its own, and Spread interpolates linearly between the two displacements and
 * between zero and its own lateral offset. At Spread 0 every one of the nine
 * evaluates to the central curve exactly — not nearly, identically — whatever
 * the other knobs say and however long they have been running. There is nothing
 * to unwind, because the deviation was never accumulated into anything: it is
 * recomputed from scratch every frame and multiplied by the knob at the end.
 *
 * Nothing is wrapped into 0..TAU. A wrapped phase cannot be interpolated — the
 * moment one oscillator wraps past another the lerp runs backwards through the
 * whole cycle — and doubles hold radians for weeks at these rates anyway.
 *
 * Thickness falls geometrically with rank, which is what makes the hiding work:
 * a curve thinner than the one in front of it cannot peek out from behind it.
 *
 * Distance to a sinusoid has no closed form, so each curve uses the linearized
 * distance: the gap in y, divided by the local slope's hypotenuse. That is the
 * perpendicular distance to the tangent, which keeps a stroke from fattening up
 * where the wave steepens. Curves are combined by taking the brightest, so
 * crossings stay stroke-brightness instead of blowing out.
 *
 * Output is pure luminosity, one 0-1 whiteness per LED.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Sinusoid Refractor")
@LXComponent.Description("One sinusoid with eight more hidden inside it")
public class SinusoidRefractorPattern extends LXPattern {

  private static final double TAU = Math.PI * 2;

  /** One central oscillator and eight to hide behind it. */
  private static final int SINUSOIDS = 9;

  /** The counter-running partner every oscillator carries, relative to a1. */
  private static final double SECONDARY_AMPLITUDE = .8;

  /** Deviation of an outermost oscillator at full knob. */
  private static final double SPEED_SPREAD_RATE = TAU;
  private static final double DETUNE_MAX = .5;
  private static final double SCATTER_MAX = TAU;
  private static final double SPREAD_MAX = .13;

  public final CompoundParameter amp =
    new CompoundParameter("Amplitude", .3, 0, 1)
    .setDescription("How far the central sinusoid swings");

  public final CompoundParameter freq =
    new CompoundParameter("Frequency", .25, 0, 1)
    .setDescription("Cycles down the height of the scene");

  public final CompoundParameter spread =
    new CompoundParameter("Spread", 0, 0, 1)
    .setDescription("Reveals the other eight; 0 collapses them onto the center");

  public final CompoundParameter detune =
    new CompoundParameter("Detune", 0, 0, 1)
    .setDescription("How far the others' frequencies sit from the center's");

  public final CompoundParameter scatter =
    new CompoundParameter("Scatter", 0, 0, 1)
    .setDescription("How far the others' phases sit from the center's");

  public final CompoundParameter speedSpread =
    new CompoundParameter("Speed Spread", 0, 0, 1)
    .setDescription("How far the others' advance rates sit from the center's");

  public final CompoundParameter thick =
    new CompoundParameter("Thickness", .25, 0, 1)
    .setDescription("Stroke width of the central sinusoid");

  public final CompoundParameter falloff =
    new CompoundParameter("Falloff", .5, 0, 1)
    .setDescription("How fast the outer strokes thin; low keeps them near-equal");

  public final CompoundParameter soft =
    new CompoundParameter("Soft", .3, 0, 1)
    .setDescription("Edge softness, as a fraction of each stroke's own width");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .5, 0, 1)
    .setPolarity(heronarts.lx.parameter.LXParameter.Polarity.BIPOLAR)
    .setDescription("How fast the wave advances; 0.5 is still");

  public final CompoundParameter speed2 =
    new CompoundParameter("Speed2", .5, 0, 1)
    .setPolarity(heronarts.lx.parameter.LXParameter.Polarity.BIPOLAR)
    .setDescription("How fast the counter-running wave advances; 0.5 is still");

  public final CompoundParameter zoom =
    new CompoundParameter("Zoom", .5, 0, 1)
    .setDescription("Scale of the scene; center is 1x, ends are 1/4x and 4x");

  public final CompoundParameter rot =
    new CompoundParameter("Rotate", 0, 0, 1)
    .setDescription("Rotation of the scene, a full turn across the knob");

  public final CompoundParameter panx =
    new CompoundParameter("Pan X", .5, 0, 1)
    .setPolarity(heronarts.lx.parameter.LXParameter.Polarity.BIPOLAR)
    .setDescription("Horizontal translation");

  public final CompoundParameter pany =
    new CompoundParameter("Pan Y", .5, 0, 1)
    .setPolarity(heronarts.lx.parameter.LXParameter.Polarity.BIPOLAR)
    .setDescription("Vertical translation");

  public final CompoundParameter level =
    new CompoundParameter("Level", 1, 0, 1)
    .setDescription("Overall brightness");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Keep the scene square on a non-square model");

  // One oscillator's worth of curve parameters per entry, at full deviation.
  // Index 0 is the central oscillator, which deviates from itself by nothing
  // and is therefore also the reference every other one interpolates back
  // towards.
  private final double[] sinK = new double[SINUSOIDS];
  private final double[] sinPhase1 = new double[SINUSOIDS];
  private final double[] sinPhase2 = new double[SINUSOIDS];
  private final double[] sinOffset = new double[SINUSOIDS];
  private final double[] sinThick = new double[SINUSOIDS];
  private final double[] sinSoft = new double[SINUSOIDS];

  // Each oscillator's own two clocks, integrated at its own rates every frame
  // whatever the knobs say. Never scaled, never wrapped.
  private final double[] sinAdvance1 = new double[SINUSOIDS];
  private final double[] sinAdvance2 = new double[SINUSOIDS];

  // Per-frame values.
  private double amp1 = 0;
  private double amp2 = 0;
  private double cosT = 1;
  private double sinT = 0;
  private double invZoom = 1;
  private double panWorldX = 0;
  private double panWorldY = 0;
  private double aspectX = 1;

  public SinusoidRefractorPattern(LX lx) {
    super(lx);
    addParameter("amp", this.amp);
    addParameter("freq", this.freq);
    addParameter("spread", this.spread);
    addParameter("detune", this.detune);
    addParameter("scatter", this.scatter);
    addParameter("speedSpread", this.speedSpread);
    addParameter("thick", this.thick);
    addParameter("falloff", this.falloff);
    addParameter("soft", this.soft);
    addParameter("speed", this.speed);
    addParameter("speed2", this.speed2);
    addParameter("zoom", this.zoom);
    addParameter("rot", this.rot);
    addParameter("panx", this.panx);
    addParameter("pany", this.pany);
    addParameter("level", this.level);
    addParameter("autoAspect", this.autoAspect);
  }

  @Override
  protected void run(double deltaMs) {
    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs * .001, 0, .25) : 0;

    double angle = this.rot.getValue() * TAU;
    this.cosT = Math.cos(angle);
    this.sinT = Math.sin(angle);
    this.invZoom = 1 / Math.pow(2, (this.zoom.getValue() - .5) * 4);
    this.panWorldX = (this.panx.getValue() - .5) * 2;
    this.panWorldY = (this.pany.getValue() - .5) * 2;

    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    this.amp1 = lerp(0, .5, this.amp.getValue());
    this.amp2 = this.amp1 * SECONDARY_AMPLITUDE;

    double centerK = TAU * lerp(.25, 8, this.freq.getValue());
    double centerOmega1 = (this.speed.getValue() - .5) * 6 * TAU;
    double centerOmega2 = (this.speed2.getValue() - .5) * 6 * TAU;

    double thickBase = lerp(.003, .08, this.thick.getValue());
    double thinning = lerp(1, .3, this.falloff.getValue());
    double softFraction = Math.max(lerp(.02, 2, this.soft.getValue()), 1e-3);

    double detuneAmount = this.detune.getValue();
    double scatterAmount = this.scatter.getValue();
    double speedSpreadAmount = this.speedSpread.getValue();

    for (int i = 0; i < SINUSOIDS; ++i) {
      // Rank 0 is the center; the rest come in pairs, one to each side, so the
      // bundle stays symmetric however far it is pushed.
      int rank = (i + 1) / 2;
      int side = (i % 2 == 1) ? 1 : -1;

      this.sinThick[i] = thickBase * Math.pow(thinning, rank);
      this.sinSoft[i] = Math.max(this.sinThick[i] * softFraction, 1e-5);

      // The center deviates from itself by nothing on every axis, so it is
      // fixed under all four knobs and the others always have something to
      // return to.
      double wobble = (rank == 0) ? 0 : signed11(i, 0);
      double slip = (rank == 0) ? 0 : signed11(i, 1);
      double lag1 = (rank == 0) ? 0 : balanced11(i, 3);
      double lag2 = (rank == 0) ? 0 : balanced11(i, 5);

      this.sinAdvance1[i] += (centerOmega1 + SPEED_SPREAD_RATE * lag1) * dt;
      this.sinAdvance2[i] += (centerOmega2 + SPEED_SPREAD_RATE * lag2) * dt;

      this.sinK[i] = centerK * (1 + DETUNE_MAX * detuneAmount * wobble);
      this.sinOffset[i] = rank * side * SPREAD_MAX;
      // The second wave's phase runs down by the same convention the first runs
      // up, which is what makes the pair counter-propagate.
      this.sinPhase1[i] = this.sinAdvance1[i] + SCATTER_MAX * scatterAmount * slip;
      this.sinPhase2[i] = -this.sinAdvance2[i] + SCATTER_MAX * scatterAmount * slip;
    }

    draw();
  }

  private void draw() {
    final double spreadAmount = this.spread.getValue();
    final double lvl = this.level.getValue();

    for (LXPoint p : this.model.points) {
      double sx = (p.xn - .5) * this.aspectX;
      double sy = (p.yn - .5);

      // Undo the scene transform: rotate the point by the inverse of the
      // scene's rotation, unzoom it, and shift it by the pan.
      double rx = (this.cosT * sx + this.sinT * sy) * this.invZoom + this.panWorldX;
      double ry = (-this.sinT * sx + this.cosT * sy) * this.invZoom + this.panWorldY;

      // Then the last quarter turn into the wave's own frame: x runs along the
      // curve, y across it. This is the only thing making the sinusoid vertical.
      double x = ry;
      double y = rx;

      // The central oscillator's curve, which every oscillator is interpolated
      // back towards, and which is the same for all nine.
      double centerY = waveY(x, this.amp1, this.sinK[0], this.sinPhase1[0],
        this.amp2, this.sinK[0], this.sinPhase2[0]);
      double centerSlope = waveSlope(x, this.amp1, this.sinK[0], this.sinPhase1[0],
        this.amp2, this.sinK[0], this.sinPhase2[0]);

      double best = 0;
      for (int i = 0; i < SINUSOIDS; ++i) {
        // This oscillator as it would be if it were fully revealed.
        double targetY = waveY(x, this.amp1, this.sinK[i], this.sinPhase1[i],
          this.amp2, this.sinK[i], this.sinPhase2[i]) + this.sinOffset[i];
        double targetSlope = waveSlope(x, this.amp1, this.sinK[i], this.sinPhase1[i],
          this.amp2, this.sinK[i], this.sinPhase2[i]);

        // Spread is the whole reveal: a straight lerp from the center's curve
        // to this one's. At 0 it is the center's curve exactly, for every
        // oscillator.
        double curveY = centerY + (targetY - centerY) * spreadAmount;
        double slope = centerSlope + (targetSlope - centerSlope) * spreadAmount;

        // Perpendicular distance to the tangent rather than the gap in y, so
        // the stroke keeps its width where the wave steepens.
        double away = (y - curveY) / Math.sqrt(1 + slope * slope);
        if (away < 0) {
          away = -away;
        }

        double edge = this.sinThick[i] + this.sinSoft[i];
        if (away >= edge) {
          continue;
        }
        double value = (edge - away) / this.sinSoft[i];
        if (value > 1) {
          value = 1;
        }
        if (value > best) {
          best = value;
        }
      }

      this.colors[p.index] = LXColor.gray(best * lvl * 100);
    }
  }

  /** y = a1*sin(k1*x + phase1) + a2*sin(k2*x + phase2). Nothing more. */
  private static double waveY(double x, double a1, double k1, double phase1,
      double a2, double k2, double phase2) {
    return a1 * Math.sin(k1 * x + phase1) + a2 * Math.sin(k2 * x + phase2);
  }

  /** Its slope, dy/dx, at the same x. */
  private static double waveSlope(double x, double a1, double k1, double phase1,
      double a2, double k2, double phase2) {
    return a1 * k1 * Math.cos(k1 * x + phase1) + a2 * k2 * Math.cos(k2 * x + phase2);
  }

  /** Deterministic, decorrelated -1..1 value for an oscillator and a salt. */
  private static double signed11(int i, int salt) {
    double s = Math.sin(i * 127.1 + salt * 311.7) * 43758.5453;
    return (s - Math.floor(s)) * 2 - 1;
  }

  /**
   * Deterministic -1..1 value for one of the eight, from a set that is balanced
   * by construction: evenly spaced across the range, summing to zero.
   *
   * The hash used for detune and scatter is fine for an offset that only has to
   * look irregular, but it cannot be trusted to straddle zero over a sample of
   * eight — for these indices it lands in -0.03..0.71, all but one on the same
   * side. That is invisible in a phase offset and ruinous in a rate: the whole
   * bundle would creep one way instead of splitting around a stationary center.
   * The step walks the slots in a full cycle, so consecutive oscillators get
   * rates from opposite ends and the spread does not read as a ramp. Any step
   * coprime with the eight slots works, which is how the two waves get
   * different orderings from the same balanced set.
   */
  private static double balanced11(int i, int step) {
    int slot = ((i - 1) * step) % (SINUSOIDS - 1);
    return slot / ((SINUSOIDS - 2) / 2.) - 1;
  }

  private static double clamp(double v, double low, double high) {
    return (v < low) ? low : (v > high) ? high : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
