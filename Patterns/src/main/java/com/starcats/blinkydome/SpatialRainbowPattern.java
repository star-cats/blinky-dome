package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * A rainbow gradient that sweeps through space.
 *
 * Every LED gets a hue based on *where it is* (its position along some spatial
 * axis) plus a phase that advances over time. That's the whole trick behind most
 * spatial animation: hue = f(position) + g(time).
 *
 * This is the reference example for writing custom Java patterns in this repo.
 * See Patterns/README.md for how to build and install it.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Spatial Rainbow")
@LXComponent.Description("Rainbow gradient that sweeps across the model in space")
public class SpatialRainbowPattern extends LXPattern {

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
    AZIMUTH("Azimuth", p -> (float) (p.azimuth / (2 * Math.PI))),
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
    .setDescription("Which spatial axis the rainbow runs along");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", .2, -2, 2)
    .setDescription("Rainbow sweeps per second, negative to reverse");

  public final CompoundParameter spread =
    new CompoundParameter("Spread", 1, .25, 5)
    .setDescription("How many full rainbows fit across the model");

  public final CompoundParameter saturation =
    new CompoundParameter("Sat", 100, 0, 100)
    .setUnits(LXParameter.Units.PERCENT)
    .setDescription("Color saturation");

  public final CompoundParameter level =
    new CompoundParameter("Level", 100, 0, 100)
    .setUnits(LXParameter.Units.PERCENT)
    .setDescription("Brightness");

  /** Animation phase, 0-1. Advanced by run() rather than derived from wall time. */
  private double phase = 0;

  public SpatialRainbowPattern(LX lx) {
    super(lx);
    // addParameter() is what makes a parameter show up in the UI, get saved into
    // the project file, and become a target for modulation and MIDI mapping.
    addParameter("axis", this.axis);
    addParameter("speed", this.speed);
    addParameter("spread", this.spread);
    addParameter("saturation", this.saturation);
    addParameter("level", this.level);
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
    final float saturation = this.saturation.getValuef();
    final float level = this.level.getValuef();

    this.phase += deltaMs * .001 * this.speed.getValue();
    this.phase -= Math.floor(this.phase);

    for (LXPoint p : model.points) {
      final float hue = 360f * wrap(axis.position(p) * spread - (float) this.phase);
      // colors[] is the frame buffer this pattern renders into. p.index is the
      // point's slot in it — never assume it matches the loop iteration count,
      // since a view may be rendering a subset of the full model.
      colors[p.index] = LXColor.hsb(hue, saturation, level);
    }
  }

  /** Wraps any float into the range 0-1, handling negatives. */
  private static float wrap(float value) {
    return value - (float) Math.floor(value);
  }
}
