package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Three independently moving Lissajous tracers, ported from
 * Scripts/LissajousTracers.js.
 *
 * Each head follows independently integrated X and Y oscillator phases:
 *
 *   phaseX += Kx * speed * dt
 *   phaseY += Ky * speed * dt
 *   x = R * sin(phaseX)
 *   y = R * sin(phaseY)
 *
 * Integrating each oscillator means that changing K alters its future velocity
 * without multiplying its entire history and teleporting the tracer head. Each
 * head carries a sampled polyline behind it. Both stroke radius and luminosity
 * decrease linearly with trail age, reaching exactly zero at the tail. The
 * output alpha is always identical to luminosity.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Lissajous Tracers")
@LXComponent.Description("Three independently moving, tapered Lissajous tracers")
public class LissajousTracersPattern extends LXPattern {

  private static final double TAU = Math.PI * 2;
  private static final int TRACERS = 3;
  private static final int SEGMENTS = 96;
  private static final int POINTS_PER_TRACER = SEGMENTS + 1;
  private static final double PHASE_RESET = 10000 * TAU;
  private static final double EDGE_SOFTNESS = .0025;

  public final CompoundParameter speed1 =
    new CompoundParameter("Speed 1", .2, 0, 1)
    .setDescription("Tracer 1 speed; 0 is still, 1 is six-sevenths of a cycle per second");

  public final CompoundParameter r1 =
    new CompoundParameter("R 1", .88, 0, 1)
    .setDescription("Tracer 1 radius");

  public final CompoundParameter kx1 =
    new CompoundParameter("Kx 1", .4, 0, 1)
    .setDescription("Tracer 1 horizontal frequency, from 1 to 6");

  public final CompoundParameter ky1 =
    new CompoundParameter("Ky 1", .2, 0, 1)
    .setDescription("Tracer 1 vertical frequency, from 1 to 6");

  public final CompoundParameter speed2 =
    new CompoundParameter("Speed 2", .14, 0, 1)
    .setDescription("Tracer 2 speed; 0 is still, 1 is six-sevenths of a cycle per second");

  public final CompoundParameter r2 =
    new CompoundParameter("R 2", .67, 0, 1)
    .setDescription("Tracer 2 radius");

  public final CompoundParameter kx2 =
    new CompoundParameter("Kx 2", .8, 0, 1)
    .setDescription("Tracer 2 horizontal frequency, from 1 to 6");

  public final CompoundParameter ky2 =
    new CompoundParameter("Ky 2", .6, 0, 1)
    .setDescription("Tracer 2 vertical frequency, from 1 to 6");

  public final CompoundParameter speed3 =
    new CompoundParameter("Speed 3", .25, 0, 1)
    .setDescription("Tracer 3 speed; 0 is still, 1 is six-sevenths of a cycle per second");

  public final CompoundParameter r3 =
    new CompoundParameter("R 3", .5, 0, 1)
    .setDescription("Tracer 3 radius");

  public final CompoundParameter kx3 =
    new CompoundParameter("Kx 3", .2, 0, 1)
    .setDescription("Tracer 3 horizontal frequency, from 1 to 6");

  public final CompoundParameter ky3 =
    new CompoundParameter("Ky 3", .4, 0, 1)
    .setDescription("Tracer 3 vertical frequency, from 1 to 6");

  public final CompoundParameter thickness =
    new CompoundParameter("Thickness", .3, 0, 1)
    .setDescription("Shared width at each tracer head");

  public final CompoundParameter traceLength =
    new CompoundParameter("Tracer Length", .55, 0, 1)
    .setDescription("Shared trail length");

  // Per-axis oscillator clocks. The offsets keep equal parameter sets visually
  // distinct while K changes leave the current head position untouched.
  private final double[] phaseX = {
    Math.PI / 2,
    Math.PI / 2 + TAU / 3,
    Math.PI / 2 + 2 * TAU / 3
  };
  private final double[] phaseY = { 0, TAU / 3, 2 * TAU / 3 };

  // Flat per-frame polyline tables avoid all trigonometry in the per-LED loop.
  private final double[] pathX = new double[TRACERS * POINTS_PER_TRACER];
  private final double[] pathY = new double[TRACERS * POINTS_PER_TRACER];

  private double headRadius = .02;
  private double aspectX = 1;

  public LissajousTracersPattern(LX lx) {
    super(lx);
    addParameter("speed1", this.speed1);
    addParameter("r1", this.r1);
    addParameter("kx1", this.kx1);
    addParameter("ky1", this.ky1);
    addParameter("speed2", this.speed2);
    addParameter("r2", this.r2);
    addParameter("kx2", this.kx2);
    addParameter("ky2", this.ky2);
    addParameter("speed3", this.speed3);
    addParameter("r3", this.r3);
    addParameter("kx3", this.kx3);
    addParameter("ky3", this.ky3);
    addParameter("thickness", this.thickness);
    addParameter("traceLength", this.traceLength);
  }

  @Override
  protected void run(double deltaMs) {
    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs * .001, 0, .25) : 0;

    // Radius and thickness are relative to model height. Scaling normalized X
    // by the model aspect ratio keeps them circular on a wide model.
    this.aspectX = 1;
    if (this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    this.headRadius = lerp(.0015, .0825, this.thickness.getValue());
    double phaseLength = TAU * lerp(.03 / 7, 1.5 / 7, this.traceLength.getValue());

    buildTracer(0, this.speed1.getValue(), this.r1.getValue(),
      this.kx1.getValue(), this.ky1.getValue(), phaseLength, dt);
    buildTracer(1, this.speed2.getValue(), this.r2.getValue(),
      this.kx2.getValue(), this.ky2.getValue(), phaseLength, dt);
    buildTracer(2, this.speed3.getValue(), this.r3.getValue(),
      this.kx3.getValue(), this.ky3.getValue(), phaseLength, dt);

    draw();
  }

  private void buildTracer(int index, double speed, double radiusKnob,
      double kxKnob, double kyKnob, double phaseLength, double dt) {
    double radius = lerp(.02, .48, radiusKnob);
    int kx = 1 + (int) Math.floor(clamp(kxKnob, 0, 1) * 5.999999);
    int ky = 1 + (int) Math.floor(clamp(kyKnob, 0, 1) * 5.999999);
    int base = index * POINTS_PER_TRACER;

    // The speed range is 0..6/7 base cycles per second. Each oscillator owns
    // its K-scaled phase so a K change affects only future motion.
    double advance = clamp(speed, 0, 1) * (6. / 7) * TAU * dt;
    this.phaseX[index] += kx * advance;
    this.phaseY[index] += ky * advance;

    // Reduce only after 10,000 oscillator cycles. PHASE_RESET is an integer
    // multiple of TAU, and retaining the remainder makes this invisible.
    if (this.phaseX[index] >= PHASE_RESET) {
      this.phaseX[index] -= Math.floor(this.phaseX[index] / PHASE_RESET) * PHASE_RESET;
    }
    if (this.phaseY[index] >= PHASE_RESET) {
      this.phaseY[index] -= Math.floor(this.phaseY[index] / PHASE_RESET) * PHASE_RESET;
    }

    for (int i = 0; i <= SEGMENTS; ++i) {
      double age = i / (double) SEGMENTS;
      double back = phaseLength * age;
      this.pathX[base + i] = radius * Math.sin(this.phaseX[index] - kx * back);
      this.pathY[base + i] = radius * Math.sin(this.phaseY[index] - ky * back);
    }
  }

  private void draw() {
    double reach = this.headRadius + EDGE_SOFTNESS;

    for (LXPoint point : this.model.points) {
      double x = (point.xn - .5) * this.aspectX;
      double y = point.yn - .5;
      double best = 0;

      for (int tracer = 0; tracer < TRACERS; ++tracer) {
        int base = tracer * POINTS_PER_TRACER;

        for (int i = 0; i < SEGMENTS; ++i) {
          double ax = this.pathX[base + i];
          double ay = this.pathY[base + i];
          double bx = this.pathX[base + i + 1];
          double by = this.pathY[base + i + 1];

          // The head radius is the largest possible width, so this segment-box
          // rejection cannot discard a point covered by the tapered stroke.
          if (x < Math.min(ax, bx) - reach || x > Math.max(ax, bx) + reach
              || y < Math.min(ay, by) - reach || y > Math.max(ay, by) + reach) {
            continue;
          }

          double dx = bx - ax;
          double dy = by - ay;
          double length2 = dx * dx + dy * dy;
          double u = 0;
          if (length2 > 1e-12) {
            u = clamp(((x - ax) * dx + (y - ay) * dy) / length2, 0, 1);
          }

          double px = ax + dx * u;
          double py = ay + dy * u;
          double ex = x - px;
          double ey = y - py;
          double distance = Math.sqrt(ex * ex + ey * ey);

          double age = (i + u) / SEGMENTS;
          double radius = this.headRadius * (1 - age);
          double coverage = clamp((radius - distance) / EDGE_SOFTNESS + .5, 0, 1);

          // Ensure the zero-radius endpoint has zero alpha too. This only
          // modifies the final sub-pixel part of the taper.
          coverage *= clamp(radius / (EDGE_SOFTNESS * .5), 0, 1);

          // Luminosity and alpha use the same head-to-tail envelope and the
          // same edge coverage. At crossings, the brightest contributor wins.
          double value = coverage * (1 - age);
          if (value > best) {
            best = value;
          }
        }
      }

      int value = (int) Math.round(best * 255);
      this.colors[point.index] = LXColor.rgba(value, value, value, value);
    }
  }

  private static double clamp(double value, double low, double high) {
    return (value < low) ? low : (value > high) ? high : value;
  }

  private static double lerp(double a, double b, double amount) {
    return a + (b - a) * amount;
  }
}
