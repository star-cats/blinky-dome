package com.github.starcats.blinkydome.pattern.effects;

import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.SawLFO;
import heronarts.lx.parameter.BoundedParameter;

import java.util.List;
import java.util.function.Function;

public class WhiteWipe {
  private final SawLFO wave;
  private final LXPattern host;
  private final List<LXPoint> points;
  private Function<LXPoint, Float> pointValueGetter;

  public final BoundedParameter durationMs;
  public final BoundedParameter widthPx;

  public WhiteWipe(LX lx, LXPattern host,
                   Function<LXModel, Float> minGetter,
                   Function<LXModel, Float> maxGetter,
                   Function<LXPoint, Float> pointValueGetter
  ) {
    this(lx, host, minGetter, maxGetter, pointValueGetter,
        new BoundedParameter("durationMs", 400, 50, 1000),
        new BoundedParameter("widthPx", 5, 0, 20)
    );
  }

  /**
   * Constructor that defines the wipe against some list of specific points
   */
  public WhiteWipe(LX lx, LXPattern host,
                   List<LXPoint> points,
                   Function<LXPoint, Float> pointValueGetter,
                   BoundedParameter durationMs,
                   BoundedParameter widthPx
  ) {
    this.host = host;
    this.pointValueGetter = pointValueGetter;
    this.points = points;

    float minValue = Float.MAX_VALUE;
    float maxValue = Float.MIN_VALUE;

    for (LXPoint pt : points) {
      float val = pointValueGetter.apply(pt);
      minValue = Math.min(minValue, val);
      maxValue = Math.max(maxValue, val);
    }

    wave = new SawLFO(
        minValue,
        maxValue,
        durationMs
    );
    wave.setLooping(false);

    this.durationMs = durationMs;
    this.widthPx = widthPx;
  }

  /**
   * Constructor that defines the wipe against ALL points in the model
   */
  public WhiteWipe(LX lx, LXPattern host,
                   Function<LXModel, Float> minGetter,
                   Function<LXModel, Float> maxGetter,
                   Function<LXPoint, Float> pointValueGetter,
                   BoundedParameter durationMs,
                   BoundedParameter widthPx
  ) {
    this.host = host;
    this.pointValueGetter = pointValueGetter;
    this.points = host.getModel().getPoints();

    wave = new SawLFO(
        minGetter.apply(host.getModel()),
        maxGetter.apply(host.getModel()),
        durationMs
    );
    wave.setLooping(false);

    this.durationMs = durationMs;
    this.widthPx = widthPx;
  }

  public void start() {
    wave.reset();
    wave.start();
  }

  public void run(double deltaMs) {
    wave.loop(deltaMs);

    if (wave.isRunning()) {
      double waveVal = wave.getValue();
      double min = waveVal - widthPx.getValue();
      double max = waveVal + widthPx.getValue();

      for (LXPoint p : points) {
        if (pointValueGetter.apply(p) > min && pointValueGetter.apply(p) < max) {
          host.getColors()[p.index] = LXColor.WHITE;
        }
      }
    }
  }
}
