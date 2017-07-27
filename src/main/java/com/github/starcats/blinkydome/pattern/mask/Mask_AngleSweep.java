package com.github.starcats.blinkydome.pattern.mask;

import com.github.starcats.blinkydome.util.SCFixture;
import com.github.starcats.blinkydome.util.TempoLock;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameterListener;
import processing.core.PVector;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Masking pattern that selects fixtures according to an angular sweep.
 *
 * We define the origin as (0,0,0).  Each fixture is defined as a vector from the origin to the fixture centroid.
 *
 * Then, we define a reference vector going from the origin to some point.  Thus, every fixture has an angle defined
 * relative to the reference vector.
 *
 * A modulator then sweeps from 0 - 180 degrees.  The fixture(s) who's angle fall within the modulation get lit.
 * The modulator is exposed as a pattern user-modulator.
 */
public class Mask_AngleSweep extends LXPattern {

  public final BooleanParameter sweepTrigger = new BooleanParameter("Trigger")
      .setDescription("Hit to start/restart the wipe")
      .setMode(BooleanParameter.Mode.MOMENTARY);

  public final VariableLFO sweepModulator = (VariableLFO) new VariableLFO("Sweep")
      .setRange(0, Math.PI)
      .setDescription("Sweep modulation");

  public final CompoundParameter doRandomize = new CompoundParameter("do rand", 0.3, 0, 1)
      .setDescription("Probability that a new sweep angle will be selected on a modulation loop (0 = off, 1 = always)");


  public final DiscreteParameter referenceX = (DiscreteParameter) new DiscreteParameter("x", new Integer[] {-1, 0, 1})
      .setDescription("Set sweep's reference angle X")
      .setValue(0);
  public final DiscreteParameter referenceY = (DiscreteParameter) new DiscreteParameter("y", new Integer[] {-1, 0, 1})
      .setDescription("Set sweep's reference angle Y")
      .setValue(0);
  public final DiscreteParameter referenceZ = (DiscreteParameter) new DiscreteParameter("z", new Integer[] {-1, 0, 1})
      .setDescription("Set sweep's reference angle Z")
      .setValue(0);


  public final BooleanParameter tempoLock = new BooleanParameter("tempo lck")
      .setDescription("Trigger to lock sweep modulator to global Tempo");


  private List<FixtureAngle> fixtures = Collections.emptyList();
  private Tempo tempo;

  public Mask_AngleSweep(LX lx, PVector reference, List<? extends SCFixture> fixtures, Tempo tempo) {
    super(lx);

    this.addParameter(sweepTrigger);

    sweepTrigger.addListener(parameter -> {
      if (parameter.getValue() != 1) {
        return;
      }

      sweepModulator.trigger();
    });
    this.modulation.addModulator(sweepModulator);
    sweepModulator.looping.setValue(true);
    sweepModulator.start();


    LXParameterListener onReferenceAngleChange = parameter -> {
      PVector newReferenceAngle = new PVector(
          (Integer) referenceX.getObject(),
          (Integer) referenceY.getObject(),
          (Integer) referenceZ.getObject()
      );
      this.setReferenceAngle(newReferenceAngle, false);
    };
    referenceX.addListener(onReferenceAngleChange);
    referenceY.addListener(onReferenceAngleChange);
    referenceZ.addListener(onReferenceAngleChange);
    this.addParameter(referenceX);
    this.addParameter(referenceY);
    this.addParameter(referenceZ);


    BooleanParameter randomize = new BooleanParameter("randomize")
        .setDescription("Randomize direction of sweep")
        .setMode(BooleanParameter.Mode.MOMENTARY);
    randomize.addListener(parameter -> {
      if (parameter.getValue() != 1) return;

      this.setRandomReferenceAngle();
    });
    this.addParameter(randomize);

    this.addParameter(doRandomize);


    this.fixtures = fixtures.stream().map(FixtureAngle::new).collect(Collectors.toList());
    this.setReferenceAngle(reference);


    if (tempo != null) {
      TempoLock tempoLock = new TempoLock(tempo, sweepModulator);
      sweepModulator.phase.setValue(0.5); // beats will be full-sweep "on"
      addParameter(tempoLock.enableLock);
    }
  }

  /** Change the reference angle for all the fixtures */
  public void setReferenceAngle(PVector referenceAngle) {
    this.setReferenceAngle(referenceAngle, true);
  }

  private void setReferenceAngle(PVector referenceAngle, boolean setAngleParams) {
    this.fixtures.forEach(fixture -> fixture.initAngle(referenceAngle));

    if (setAngleParams) {
      // Need Integer casts to setValue(Object), not setValue(double)
      referenceX.setValue( (Integer) (referenceAngle.x == 0 ? 0 : (referenceAngle.x > 1 ? 1 : -1)) );
      referenceY.setValue( (Integer) (referenceAngle.y == 0 ? 0 : (referenceAngle.y > 1 ? 1 : -1)) );
      referenceZ.setValue( (Integer) (referenceAngle.z == 0 ? 0 : (referenceAngle.z > 1 ? 1 : -1)) );
    }
  }

  public void setRandomReferenceAngle() {
    int x = (int) (Math.random() * 3 - 1);
    int y = (int) (Math.random() * 3 - 1);
    int z = (int) (Math.random() * 3 - 1);

    if (x == 0 && y == 0 && z == 0) {
      // try again
      setRandomReferenceAngle();
      return;
    }

    // Need Integer casts to setValue(Object), not setValue(double)
    setReferenceAngle(new PVector(x, y, z));
  }

  @Override
  protected void run(double deltaMs) {
    for (LXPoint p : getModel().points) {
      setColor(p.index, LXColor.BLACK);
    }

    double maxAngle = sweepModulator.getValue();
    for (FixtureAngle fixtureAngle : fixtures) {
      if (fixtureAngle.angle <= maxAngle) {
        setColor(fixtureAngle.fixture, LXColor.WHITE);
      }
    }

    if ( (sweepModulator.loop() || sweepModulator.finished()) && Math.random() < doRandomize.getValue()) {
      setRandomReferenceAngle();
    }
  }

  private class FixtureAngle {
    final SCFixture fixture;
    float angle;

    public FixtureAngle(SCFixture fixture) {
      this.fixture = fixture;
    }

    public FixtureAngle(SCFixture fixture, PVector reference) {
      this(fixture);
      this.initAngle(reference);
    }

    public void initAngle(PVector reference) {
      angle = PVector.angleBetween(reference, fixture.getCentroid());
    }
  }


}
