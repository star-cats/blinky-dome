package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * A luminance gradient that sweeps through space.
 *
 * Every LED gets a luminosity based on *where it is* (its position along some
 * spatial axis) plus a phase that advances over time. The output is grayscale:
 * rgb = LLL.
 *
 * This is the reference example for writing custom Java patterns in this repo.
 * See Patterns/README.md for how to build and install it.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Spatial Luminance")
@LXComponent.Description("Grayscale luminance gradient that sweeps across the model in space")
public class SpatialRainbowPattern extends LXPattern {

  private static final float TWO_PI = (float) (2 * Math.PI);

  /**
   * Maps an LXPoint to a normalized 0-1 position along some spatial axis.
   *
   * LX pre-computes these coordinates on every point when the model loads, so
   * reading them in the render loop is free. The normalized (xn/yn/zn/rcn)
   * variants are always 0-1 across the model's bounding box, which means a
   * pattern written against them looks the same on a dome, a strip, or a cube.
   */
  private interface Coordinate {
    float get(LXPoint p);
  }

  public enum Axis {
    X("X", p -> p.xn),
    Y("Y", p -> p.yn),
    Z("Z", p -> p.zn),
    RADIUS("Radius", p -> p.rcn),
    // azimuth is 0-2PI around the Y axis; elevation is -PI/2 (down) to +PI/2 (up)
    AZIMUTH("Azimuth", p -> p.azimuth / TWO_PI),
    ELEVATION("Elevation", p -> (float) (.5 + p.elevation / Math.PI));

    private final String label;
    private final Coordinate coordinate;

    private Axis(String label, Coordinate coordinate) {
      this.label = label;
      this.coordinate = coordinate;
    }

    public float position(LXPoint p) {
      return this.coordinate.get(p);
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  public final EnumParameter<Axis> axis =
    new EnumParameter<Axis>("Axis", Axis.ELEVATION)
    .setDescription("Which spatial axis the luminance ramp runs along");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .2, -2, 2)
    .setDescription("Luminance sweeps per second, negative to reverse");

  public final CompoundParameter spread =
    new CompoundParameter("Spread", 1, .25, 5)
    .setDescription("How many full luminance ramps fit across the model");

  public final CompoundParameter level =
    new CompoundParameter("Level", 100, 0, 100)
    .setUnits(LXParameter.Units.PERCENT)
    .setDescription("Brightness");

  /*
   * Radius-axis perturbation. On the Radius axis alone the rainbow is perfectly
   * concentric, which reads as flat. Offsetting the radial position by a sine of
   * the angle bends those rings into petals.
   *
   * Lobes is deliberately an integer: the sine has to close seamlessly where
   * theta wraps from 2PI back to 0, and a fractional lobe count would leave a
   * visible luminance discontinuity along that seam.
   */
  public final DiscreteParameter lobes =
    new DiscreteParameter("Lobes", 5, 0, 13)
    .setDescription("Radius axis: number of petals around theta (0 disables)");

  public final CompoundParameter warp =
    new CompoundParameter("Warp", .15, 0, .5)
    .setDescription("Radius axis: depth of the petal perturbation");

  /** Animation phase, 0-1. Advanced by run() rather than derived from wall time. */
  private double phase = 0;

  public SpatialRainbowPattern(LX lx) {
    super(lx);
    // addParameter() is what makes a parameter show up in the UI, get saved into
    // the project file, and become a target for modulation and MIDI mapping.
    addParameter("axis", this.axis);
    addParameter("speed", this.speed);
    addParameter("spread", this.spread);
    addParameter("level", this.level);
    addParameter("lobes", this.lobes);
    addParameter("warp", this.warp);
  }

  /**
   * Called once per frame. deltaMs is the time since the previous frame.
   *
   * Always drive animation off deltaMs instead of a frame counter — that keeps
   * the motion the same speed whether the engine is running at 60fps or bogging
   * down, and it's what lets Chromatik render deterministically when rendering
   * to a file.
   */
  @Override
  protected void run(double deltaMs) {
    final Axis axis = this.axis.getEnum();
    final float spread = this.spread.getValuef();
    final float level = this.level.getValuef() / 100f;

    // The petal perturbation only applies on the Radius axis, where it has a
    // meaningful angle to work against.
    final int lobes = this.lobes.getValuei();
    final float warp = this.warp.getValuef();
    final boolean perturb = (axis == Axis.RADIUS) && (lobes > 0) && (warp > 0);

    this.phase += deltaMs * .001 * this.speed.getValue();
    this.phase -= Math.floor(this.phase);

    for (LXPoint p : model.points) {
      float position = axis.position(p);

      if (perturb) {
        // thetaN is 0-1 for one trip around, so sweeping it through lobes * 2PI
        // gives exactly `lobes` complete sine cycles per revolution — and lands
        // back on the starting value, so there's no seam at the wrap point.
        final float thetaN = p.theta / TWO_PI;
        position += warp * (float) Math.sin(lobes * TWO_PI * thetaN);
      }

      final float luminosity = level * wrap(position * spread - (float) this.phase);
      // colors[] is the frame buffer this pattern renders into. p.index is the
      // point's slot in it — never assume it matches the loop iteration count,
      // since a view may be rendering a subset of the full model.
      colors[p.index] = LXColor.grayn(luminosity);
    }
  }

  /** Wraps any float into the range 0-1, handling negatives. */
  private static float wrap(float value) {
    return value - (float) Math.floor(value);
  }
}
