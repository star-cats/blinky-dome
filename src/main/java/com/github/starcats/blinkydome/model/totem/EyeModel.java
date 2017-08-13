package com.github.starcats.blinkydome.model.totem;

import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.transform.LXVector;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Totem eye
 */
public class EyeModel extends LXModel {
  private static final float EYE_DENSITY_PER_M = 144f;
  private static final float PX_SPACE_CM = 100f / EYE_DENSITY_PER_M; // ~0.7cm
  private static final float PX_WIDTH_CM = 1.2f; // 1.2cm

  /**
   * an EyeModel consists of a bunch of columns of tightly-packed LEDs
   */
  public static class EyeColumn implements LXFixture {
    private List<LXPoint> points;

    public EyeColumn(LXPoint[] points) {
      this(points, false);
    }

    public EyeColumn(LXPoint[] points, boolean reversed) {
      this.points = Arrays.asList(points);

      if (reversed) {
        Collections.reverse(this.points);
      }
    }

    @Override
    public List<LXPoint> getPoints() {
      return points;
    }
  }


  /**
   * Factory to produce a model
   * @param centerCM Center of the eye, in cm
   * @param left unit vector indicating 'left' for this eye
   * @param up unit vector indicating 'up' for this eye
   * @return the EyeModel
   */
  public static EyeModel makeModel(LXVector centerCM, LXVector left, LXVector up) {
    LXVector upPx = up.copy().setMag(PX_SPACE_CM);
    LXVector downPx = up.copy().setMag(-PX_SPACE_CM);
    LXVector rightPx = left.copy().setMag(-PX_WIDTH_CM);

    LXVector colPos = centerCM.copy()
        .add( left.copy().setMag(PX_WIDTH_CM * 3.5f) );

    // Snake the columns up/down to reflect strip wiring

    // Col0: going up.  1 px spacing b/w top0 and top1
    LXPoint[] col0 = new LXPoint[3];
    fillEyeCol(colPos, col0, upPx, 0, upPx, rightPx);

    // Col1: going down. 2 px spacing b/w bot1 and bot2
    LXPoint[] col1 = new LXPoint[6];
    fillEyeCol(colPos, col1, downPx, 1, downPx, rightPx);

    // Col2: going up. 0 px spacing b/w top2 and top3
    LXPoint[] col2 = new LXPoint[9];
    fillEyeCol(colPos, col2, upPx, 1, downPx, rightPx);

    // Col3: going down. -2 px spacing b/w bot3 and bot4
    LXPoint[] col3 = new LXPoint[9];
    fillEyeCol(colPos, col3, downPx, 3, upPx, rightPx);

    // Col4: going up. -1 px spacing b/w top4 and top5
    LXPoint[] col4 = new LXPoint[6];
    fillEyeCol(colPos, col4, upPx, 2, downPx, rightPx);

    // Col5: going down.
    LXPoint[] col5 = new LXPoint[3];
    fillEyeCol(colPos, col5, downPx, 0, null, rightPx);

    return new EyeModel( new EyeColumn[] {
        // Reverse every other column so fixtures are logically indexed top-to-bottom
        new EyeColumn(col0, true),
        new EyeColumn(col1, false),
        new EyeColumn(col2, true),
        new EyeColumn(col3, false),
        new EyeColumn(col4, true),
        new EyeColumn(col5, false)
    } );

  }
  private static LXPoint makePt(LXVector from) {
    return new LXPoint(from.x, from.y, from.z);
  }

  private static void fillEyeCol(LXVector colPos, LXPoint[] col, LXVector pxInc, int nextColExtraIncAmt, LXVector nextColInc, LXVector nextColShift) {
    for (int i=0; i<col.length; i++) {
      col[i] = makePt(colPos);
      colPos.add(pxInc);
    }

    for (int i=0; i<nextColExtraIncAmt; i++) {
      // if nextColInc == pxInc, note that we effectively have already done one in the last loop iteration
      // If not equal, need to undo that final one.
      colPos.add(nextColInc);
    }

    colPos.add(nextColShift);
  }

  private List<EyeColumn> eyeColumns;

  /** Constructor (use factory) */
  private EyeModel(EyeColumn[] eyeColumnFixtures) {
    super(eyeColumnFixtures);

    this.eyeColumns = Arrays.asList(eyeColumnFixtures);
  }

  public List<EyeColumn> getEyeColumns() {
    return eyeColumns;
  }
}
