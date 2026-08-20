package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Vertical rain columns, ported from Scripts/VerticalRainColumns.js.
 *
 * The frame is divided into 10 to 40 vertical bins. Rain is emitted into a
 * random bin at a steady rate and falls from the top under constant
 * acceleration, so its position is quadratic in time. Each drop leaves a white
 * trail that ramps from 1 at the drop to 0 at the tail.
 *
 * A drop adds water when it reaches the surface in its own column. A column is
 * one continuous gradient rather than a flat fill: full brightness exactly at
 * the surface, easing off to 0.8 across a thin top edge, and from there falling
 * away to 0.1 at the floor. The ramp is measured against the column's own
 * height, so it stretches as the column fills and the water always reads as
 * depth rather than as a bar that got taller. Once a column reaches the top it
 * enters a linear drain cycle; impacts during that cycle do not interrupt it,
 * and the column starts accepting water again when it is empty.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Vertical Rain Columns")
@LXComponent.Description("Falling rain that fills and drains vertical columns")
public class VerticalRainColumnsPattern extends LXPattern {

  private static final int MIN_BINS = 10;
  private static final int MAX_BINS = 40;
  private static final int MAX_DROPS = 128;

  /** Length of the fade behind a falling drop, as a fraction of frame height. */
  private static final double TRAIL_LENGTH = .2;

  /** Thickness of the bright surface on a water column. */
  private static final double WATER_EDGE = .018;

  /**
   * The column's gradient: full at the surface, easing to WATER_BODY over the
   * edge, then away to WATER_FLOOR at the bottom. The floor sits above zero so
   * a deep column still reads as water all the way down rather than fading out
   * into the unlit frame.
   */
  private static final double WATER_SURFACE = 1;
  private static final double WATER_BODY = .8;
  private static final double WATER_FLOOR = .1;

  public final CompoundParameter bins =
    new CompoundParameter("Bins", 1, 0, 1)
    .setDescription("Integer column count from 10 to 40");

  public final CompoundParameter rainRate =
    new CompoundParameter("Rain Rate", .35, 0, 1)
    .setDescription("Automatic rain emission rate");

  public final CompoundParameter fill =
    new CompoundParameter("Fill / Drop", .3, 0, 1)
    .setDescription("How much water one landed drop adds");

  public final CompoundParameter acceleration =
    new CompoundParameter("Acceleration", .42, 0, 1)
    .setDescription("Downward acceleration of each drop");

  public final CompoundParameter drain =
    new CompoundParameter("Drain Time", .2, 0, 1)
    .setDescription("Full-column drain time; default is one second");

  public final TriggerParameter cue =
    new TriggerParameter("Cue Rain", this::onCueRain)
    .setDescription("Emit one drop in a random column");

  private final double[] water = new double[MAX_BINS];
  private final boolean[] draining = new boolean[MAX_BINS];

  private final boolean[] dropActive = new boolean[MAX_DROPS];
  private final int[] dropColumn = new int[MAX_DROPS];
  private final double[] dropAge = new double[MAX_DROPS];
  private final double[] dropY = new double[MAX_DROPS];

  private int activeBins = MAX_BINS;
  private double emissionAccumulator = 0;
  private int pendingCues = 0;

  // Values resolved once per frame rather than once per LED.
  private double dropsPerSecond = 2;
  private double fillPerDrop = .067;
  private double rainAcceleration = 2;
  private double drainSeconds = 1;

  public VerticalRainColumnsPattern(LX lx) {
    super(lx);
    addParameter("bins", this.bins);
    addParameter("rainRate", this.rainRate);
    addParameter("fill", this.fill);
    addParameter("acceleration", this.acceleration);
    addParameter("drain", this.drain);
    addParameter("cue", this.cue);

    for (int d = 0; d < MAX_DROPS; ++d) {
      this.dropY[d] = 1;
    }
  }

  /** Spend triggers in run() so all simulation state changes in one place. */
  private void onCueRain() {
    ++this.pendingCues;
  }

  @Override
  protected void run(double deltaMs) {
    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs * .001, 0, .25) : 0;

    this.activeBins = clampInt(
      (int) Math.round(lerp(MIN_BINS, MAX_BINS, this.bins.getValue())),
      MIN_BINS,
      MAX_BINS
    );

    // These mappings leave useful room at both ends. Drain Time is linear and
    // its default value (0.2) maps exactly to one second.
    this.dropsPerSecond = lerp(.2, 30, this.rainRate.getValue());
    this.fillPerDrop = lerp(.01, .2, this.fill.getValue());
    this.rainAcceleration = lerp(.25, 6, this.acceleration.getValue());
    this.drainSeconds = lerp(.1, 4.6, this.drain.getValue());

    while (this.pendingCues > 0) {
      --this.pendingCues;
      emitDrop();
    }

    this.emissionAccumulator += dt * this.dropsPerSecond;
    // A long engine stall should not release an enormous catch-up cloud.
    if (this.emissionAccumulator > 4) {
      this.emissionAccumulator = 4;
    }
    while (this.emissionAccumulator >= 1) {
      this.emissionAccumulator -= 1;
      emitDrop();
    }

    advanceWater(dt);
    advanceDrops(dt);
    draw();
  }

  private void emitDrop() {
    for (int i = 0; i < MAX_DROPS; ++i) {
      if (!this.dropActive[i]) {
        this.dropActive[i] = true;
        this.dropColumn[i] = (int) Math.floor(Math.random() * this.activeBins);
        this.dropAge[i] = 0;
        this.dropY[i] = 1;
        return;
      }
    }
  }

  private void advanceWater(double dt) {
    double drainStep = dt / this.drainSeconds;
    for (int i = 0; i < MAX_BINS; ++i) {
      if (i >= this.activeBins) {
        // Inactive bins retain no hidden state if the count is turned down.
        this.water[i] = 0;
        this.draining[i] = false;
      } else if (this.draining[i]) {
        this.water[i] -= drainStep;
        if (this.water[i] <= 0) {
          this.water[i] = 0;
          this.draining[i] = false;
        }
      }
    }
  }

  private void advanceDrops(double dt) {
    for (int i = 0; i < MAX_DROPS; ++i) {
      if (!this.dropActive[i]) {
        continue;
      }

      int column = this.dropColumn[i];
      if (column >= this.activeBins) {
        this.dropActive[i] = false;
        continue;
      }

      this.dropAge[i] += dt;
      double y = 1 - .5 * this.rainAcceleration * this.dropAge[i] * this.dropAge[i];
      this.dropY[i] = y;

      if (y <= this.water[column]) {
        this.dropActive[i] = false;
        if (!this.draining[column]) {
          this.water[column] += this.fillPerDrop;
          if (this.water[column] >= 1) {
            this.water[column] = 1;
            this.draining[column] = true;
          }
        }
      }
    }
  }

  private void draw() {
    for (LXPoint p : this.model.points) {
      int column = clampInt((int) Math.floor(p.xn * this.activeBins), 0, this.activeBins - 1);
      double y = clamp(p.yn, 0, 1);
      double value = 0;
      double surface = this.water[column];

      if (surface > 0 && y <= surface) {
        // Depth below the surface, and the edge clipped to what the column can
        // actually hold — a column shallower than the edge is all edge, which
        // is also what keeps the second ramp from dividing by a zero-height
        // body.
        double depth = surface - y;
        double edge = (WATER_EDGE < surface) ? WATER_EDGE : surface;

        if (depth <= edge) {
          value = lerp(WATER_SURFACE, WATER_BODY, depth / edge);
        } else {
          value = lerp(WATER_BODY, WATER_FLOOR, (depth - edge) / (surface - edge));
        }
      }

      // Multiple drops may share a column. Max-compositing preserves the
      // defined 0..1 trail instead of making overlaps exceed full brightness.
      for (int i = 0; i < MAX_DROPS; ++i) {
        if (!this.dropActive[i] || this.dropColumn[i] != column) {
          continue;
        }
        double behind = y - this.dropY[i];
        if (behind >= 0 && behind <= TRAIL_LENGTH) {
          double trail = 1 - behind / TRAIL_LENGTH;
          if (trail > value) {
            value = trail;
          }
        }
      }

      this.colors[p.index] = LXColor.gray(value * 100);
    }
  }

  private static int clampInt(int value, int low, int high) {
    return Math.max(low, Math.min(high, value));
  }

  private static double clamp(double v, double low, double high) {
    return (v < low) ? low : (v > high) ? high : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
