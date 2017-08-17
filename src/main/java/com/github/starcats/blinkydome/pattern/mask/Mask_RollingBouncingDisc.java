package com.github.starcats.blinkydome.pattern.mask;

import com.github.starcats.blinkydome.util.TempoLock;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.LXWaveshape;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXCompoundModulation;
import heronarts.lx.transform.LXVector;

/**
 * A disc of LEDs that can tilt and bounce across a model
 */
public class Mask_RollingBouncingDisc extends LXPattern {

  private static double RIGHT_ANGLE = Math.PI / 2;


  public final CompoundParameter discThicknessRad = (CompoundParameter) new CompoundParameter(
      "thcknss", Math.PI / 8, 0, Math.PI/2)
      .setDescription("How many degrees (in radians) up/down from the hoop should be lit up")
      .setExponent(2);

  public final CompoundParameter position = new CompoundParameter("pos", 0., 0., 1.)
      .setDescription("Current position along the direction vector");

  public final CompoundParameter pitch = new CompoundParameter("pitch", 0., 0, Math.PI / 2)
      .setDescription("Tilt angle off the travel direction vector (radians)");

  public final CompoundParameter roll = new CompoundParameter("roll", 0., 0, 2. * Math.PI)
      .setDescription("Rotation around the travel direction vector (radians)");

  public final EnumParameter<MonitorDetailLevel> detailLevel = new EnumParameter<>("detail", MonitorDetailLevel.PITCH)
      .setDescription("Detail level for vizualization");

  /**
   * Interface that can monitor the state of this bouncing disc monitor.
   *
   * For example, a p3lx UI vizualization
   */
  public interface RollingBouncingDiscMonitor {
    void acceptMonitoree(Mask_RollingBouncingDisc monitoree);

    void acceptState(
        LXVector origin,
        LXVector direction,
        LXVector position,
        LXVector rollZero,
        LXVector zeroRollPitchAxis,
        LXVector pitchAxis,
        LXVector pitchOffset
    );
  }

  public enum MonitorDetailLevel {
    NONE(0),

    /** Pitch vector only */
    PITCH(1),

    /** Pitch + rotation */
    LESS(2),

    /** Pitch and travel vector */
    LESS_TRAVEL(3),

    /** All principal axes*/
    PRINCIPAL(4),

    /** Principal axes with travel vector */
    MORE(5),

    /** All vectors */
    FULL(6);

    public final int level;

    MonitorDetailLevel(int detailLevel) {
      this.level = detailLevel;
    }
  };


  private LXVector origin;
  private LXVector direction;
  private LXVector rollZero;

  private RollingBouncingDiscMonitor monitor;

  private VariableLFO posModulator;
  private TempoLock tempoLock;
  private boolean tempoLockInited;

  public Mask_RollingBouncingDisc(LX lx, LXVector origin, LXVector direction, LXVector rollZero) {
    super(lx);
    this.origin = origin;
    this.direction = direction;

    if (RIGHT_ANGLE - LXVector.angleBetween(direction, rollZero) > 0.01) { // allow some tolerance for floating-point math
      throw new RuntimeException("rollZero vector must be orthogonal (at a right angle) to direction vector");
    }
    this.rollZero = rollZero;

    addParameter(position);
    addParameter(discThicknessRad);
    addParameter(pitch);
    addParameter(roll);

    addParameter(detailLevel);

    this.tempoLock = new TempoLock(lx, "position tempo lock", lx.tempo);
    this.tempoLockInited = false; // need to wait for posModulator to be available, see initTempoLocks()
    addParameter(this.tempoLock.enable);
    addParameter(this.tempoLock.beatIndexTrigger);
  }

  /**
   * Adds modulators that show how to make this roll and bounce
   * @return this for chaining
   */
  public Mask_RollingBouncingDisc addDemoBounce() {
    // Position: modulate with sin, period 1s
    VariableLFO posModulator = (VariableLFO) new VariableLFO("pos")
        .setDescription("Modulates the position up and down");
    posModulator.period.setValue(1000);
    posModulator.period.setExponent(2);
    posModulator.start();
    this.posModulator = posModulator;
    this.modulation.addModulator(posModulator);

    LXCompoundModulation posModulation = new LXCompoundModulation(posModulator, position);
    position.setValue(0);
    posModulation.range.setValue(1);
    this.modulation.addModulation(posModulation);


    // Roll: modulate with up, period a bit shorter
    VariableLFO rollModulator = (VariableLFO) new VariableLFO("roll")
        .setDescription("Modulates the rotation around and around");
    rollModulator.waveshape.setValue(LXWaveshape.UP);
    rollModulator.period.setValue(925);
    rollModulator.period.setExponent(2);
    rollModulator.start();
    this.modulation.addModulator(rollModulator);

    LXCompoundModulation rollModulation = new LXCompoundModulation(rollModulator, roll);
    roll.setValue(0);
    rollModulation.range.setValue(1);
    this.modulation.addModulation(rollModulation);


    // And create a bit of a pitch to see the roll
    this.pitch.setValue(0.25);
    this.detailLevel.setValue(MonitorDetailLevel.LESS);

    return this;
  }

  public Mask_RollingBouncingDisc initTempoLocks() {
    if (posModulator != null) {
      this.tempoLock.setModulatorToLock(posModulator);
      tempoLockInited = true;
    }

    return this;
  }

  @Override
  public void dispose() {
    if (this.tempoLock != null) {
      this.tempoLock.dispose();
      this.tempoLock = null;
    }
    super.dispose();
  }

  @Override
  protected void run(double deltaMs) {
    // Init on first run for when deserializing out of json
    if (!tempoLockInited) {
      this.initTempoLocks();
    }

    LXVector curPosOffset = new LXVector(direction).mult(position.getValuef()).add(origin);

    // Okay, now that we're at the starting position for the disc, lets define principal axes:

    // rollZero: Vector that we pitch into when there is 0-roll (0 rotation)
    //   viz: red

    // zeroRollPitchAxis: Axis that the pitch vector rotates around when there's zero roll (hence, orthogonal to rollZero)
    //   viz: dark green
    LXVector zeroRollPitchAxis = new LXVector(rollZero).cross(direction);

    // pitchAxis: The axis that the pitch vector actually rotates around.  Is zeroRollPitchAxis with the roll rotation applied
    //   viz: full green
    LXVector pitchAxis = new LXVector(zeroRollPitchAxis).rotate(this.roll.getValuef(), direction.x, direction.y, direction.z);

    // pitchOffset: Actual pitch vector, all rotations applied
    //   viz: magenta
    LXVector pitchOffset = new LXVector(direction).rotate(pitch.getValuef(), pitchAxis.x, pitchAxis.y, pitchAxis.z);


    if (monitor != null) {
      monitor.acceptState(
          origin,
          direction,
          curPosOffset,
          rollZero,
          zeroRollPitchAxis,
          pitchAxis,
          pitchOffset
      );
    }

    // reverse the offsets to because we only have .add(), no subtract() (we're making LED offset vectors)
    curPosOffset.mult(-1);
    pitchOffset.mult(-1);


    LXVector led = new LXVector(0, 0, 0);
    for (LXPoint pt : model.getPoints()) {
      set(led, pt);
      led.add(curPosOffset);
      double theta = LXVector.angleBetween(pitchOffset, led);

      if (theta > RIGHT_ANGLE - discThicknessRad.getValue() && theta < RIGHT_ANGLE + discThicknessRad.getValue()) {
        setColor(pt.index, LXColor.WHITE);
      } else {
        setColor(pt.index, LXColor.BLACK);
      }
    }
  }

  private static void set(LXVector vector, LXPoint from) {
    vector.x = from.x;
    vector.y = from.y;
    vector.z = from.z;
  }


  public void setMonitor(RollingBouncingDiscMonitor monitor) {
    if (this.monitor != null) {
      throw new RuntimeException("TODO: only one monitor allowed");
    }
    monitor.acceptMonitoree(this);
    this.monitor = monitor;
  }

}
