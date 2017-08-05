package com.github.starcats.blinkydome.ui;

import com.github.starcats.blinkydome.pattern.mask.Mask_RollingBouncingDisc;
import heronarts.lx.transform.LXVector;
import heronarts.p3lx.ui.UI;
import heronarts.p3lx.ui.UI3dComponent;
import processing.core.PConstants;
import processing.core.PGraphics;

/**
 * Principal axis visualization for {@link Mask_RollingBouncingDisc}
 */
public abstract class RollingBouncingDiscAxisViz extends UI3dComponent implements Mask_RollingBouncingDisc.RollingBouncingDiscMonitor {

  private LXVector origin = new LXVector(0, 0, 0);
  private LXVector direction = new LXVector(0, 0, 0);
  private LXVector position = new LXVector(0, 0, 0);
  private LXVector rollZero = new LXVector(0, 0, 0);
  private LXVector zeroRollPitchAxis = new LXVector(0, 0, 0);
  private LXVector pitchAxis = new LXVector(0, 0, 0);
  private LXVector pitchOffset = new LXVector(0, 0, 0);

  private Mask_RollingBouncingDisc monitoree;
  private float principalMagnitude = 50;

  @Override
  public void acceptState(
      LXVector origin,
      LXVector direction,
      LXVector position,
      LXVector rollZero,
      LXVector zeroRollPitchAxis,
      LXVector pitchAxis,
      LXVector pitchOffset
  ) {
    this.origin.set(origin);
    this.direction.set(direction).add(origin);
    this.position.set(position);

    this.rollZero.set(rollZero).setMag(principalMagnitude).add(position);
    this.zeroRollPitchAxis.set(zeroRollPitchAxis).setMag(principalMagnitude).add(position);
    this.pitchAxis.set(pitchAxis).setMag(principalMagnitude).add(position);
    this.pitchOffset.set(pitchOffset).setMag(principalMagnitude).add(position);
  }

  @Override
  public void acceptMonitoree(Mask_RollingBouncingDisc monitoree) {
    this.monitoree = monitoree;
  }

  /** Sets how many pixels to draw the principal axes */
  public void setPrincipalMagnitude(float principalMagnitude) {
    this.principalMagnitude = principalMagnitude;
  }

  protected abstract void dispose();

  @Override
  protected void onDraw(UI ui, PGraphics p) {
    if (monitoree == null) {
      return;
    }

    if (monitoree.getChannel() == null) {
      // Disconnected pattern (eg removed from channel), I'll show myself out.
      dispose();
      return;
    }

    // Idle
    if (monitoree.getChannel().getActivePattern() != monitoree) {
      return; // nothing to do, move along
    }

    Mask_RollingBouncingDisc.MonitorDetailLevel detailLevel = monitoree.detailLevel.getEnum();
    int detailLevelI = detailLevel.level;

    p.pushStyle();
    p.colorMode(PConstants.RGB, 255, 255, 255);
    p.strokeWeight(2);

    // Origin
    if (detailLevel == Mask_RollingBouncingDisc.MonitorDetailLevel.FULL) {
      p.strokeWeight(1);
      p.stroke(255, 0, 0);
      p.line(0, 0, 0, origin.x, origin.y, origin.z);
    }

    // Position
    if (detailLevelI >= Mask_RollingBouncingDisc.MonitorDetailLevel.MORE.level) {
      p.strokeWeight(2);
      p.stroke(0, 0, 255);
      p.line(origin.x, origin.y, origin.z, position.x, position.y, position.z);
    }

    // Direction vector: light blue
    if (detailLevel == Mask_RollingBouncingDisc.MonitorDetailLevel.LESS_TRAVEL ||
        detailLevelI >= Mask_RollingBouncingDisc.MonitorDetailLevel.MORE.level) {
      p.strokeWeight(1);
      p.stroke(0, 0, 127);
      p.line(origin.x, origin.y, origin.z, direction.x, direction.y, direction.z);
    }

    if (detailLevelI >= Mask_RollingBouncingDisc.MonitorDetailLevel.PRINCIPAL.level) {
      p.strokeWeight(2);

      // Roll-zero (Vector that we pitch into when there is 0-roll / 0-rotation): red
      p.stroke(255, 0, 0);
      p.line(position.x, position.y, position.z, rollZero.x, rollZero.y, rollZero.z);


      // zeroRollPitchAxis: Axis that the pitch vector rotates around when there's zero roll: dark green
      p.stroke(0, 100, 0);
      p.line(position.x, position.y, position.z, zeroRollPitchAxis.x, zeroRollPitchAxis.y, zeroRollPitchAxis.z);
    }

    // pitchAxis: The axis that the pitch vector actually rotates around: full green
    if (detailLevelI >= Mask_RollingBouncingDisc.MonitorDetailLevel.LESS.level) {
      p.strokeWeight(2);
      p.stroke(0, 255, 0);
      p.line(position.x, position.y, position.z, pitchAxis.x, pitchAxis.y, pitchAxis.z);
    }

    // Current pitch: magenta
    if (detailLevelI >= Mask_RollingBouncingDisc.MonitorDetailLevel.PITCH.level) {
      p.strokeWeight(2);
      p.stroke(255, 0, 255);
      p.line(position.x, position.y, position.z, pitchOffset.x, pitchOffset.y, pitchOffset.z);
    }


    p.popStyle();
  }
}
