package com.github.starcats.blinkydome.model.totem;

import com.github.starcats.blinkydome.model.util.VectorStripModel;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.transform.LXVector;

import java.util.Arrays;
import java.util.List;

/**
 * Totem face
 */
public class TotemModel extends LXModel {

  private static final float WHISKER_LEN_CM = 66.5f / 2f; // full whisker stril is 66.5cm, one whisker is half
  private static final int PX_PER_WHISKER = 20;

  public final List<VectorStripModel> leftWhiskers;
  public final List<VectorStripModel> rightWhiskers;
  public final EyeModel leftEye;
  public final EyeModel rightEye;

  public static TotemModel makeModel() {
    LXVector nose = new LXVector(0, 10f, 0);
    float whiskerOutZ = -10f;

    float whisker0Y = 6f;
    float whisker1Y = 3f;
    float whisker2Y = 0f;

    float whisker0X = solveForX(WHISKER_LEN_CM, nose, whisker0Y, whiskerOutZ);
    float whisker1X = solveForX(WHISKER_LEN_CM, nose, whisker0Y, whiskerOutZ);
    float whisker2X = solveForX(WHISKER_LEN_CM, nose, whisker0Y, whiskerOutZ);

    VectorStripModel<LXPoint> wh0a = new VectorStripModel<>(
        new LXVector(-whisker0X, whisker0Y, whiskerOutZ),
        nose,
        VectorStripModel.GENERIC_POINT_FACTORY, PX_PER_WHISKER
    );
    VectorStripModel<LXPoint> wh0b = new VectorStripModel<>(
        nose,
        new LXVector(whisker0X, whisker0Y, whiskerOutZ),
        VectorStripModel.GENERIC_POINT_FACTORY, PX_PER_WHISKER
    );

    VectorStripModel<LXPoint> wh1a = new VectorStripModel<>(
        new LXVector(whisker1X, whisker1Y, whiskerOutZ),
        nose,
        VectorStripModel.GENERIC_POINT_FACTORY, PX_PER_WHISKER
    );
    VectorStripModel<LXPoint> wh1b = new VectorStripModel<>(
        nose,
        new LXVector(-whisker1X, whisker1Y, whiskerOutZ),
        VectorStripModel.GENERIC_POINT_FACTORY, PX_PER_WHISKER
    );

    VectorStripModel<LXPoint> wh2a = new VectorStripModel<>(
        new LXVector(-whisker2X, whisker2Y, whiskerOutZ),
        nose,
        VectorStripModel.GENERIC_POINT_FACTORY, PX_PER_WHISKER
    );
    VectorStripModel<LXPoint> wh2b = new VectorStripModel<>(
        nose,
        new LXVector(whisker2X, whisker2Y, whiskerOutZ),
        VectorStripModel.GENERIC_POINT_FACTORY, PX_PER_WHISKER
    );


    EyeModel leftEye = EyeModel.makeModel(
        new LXVector(-20f, 20f, 0f),
        new LXVector(-1f, 0, 0),
        new LXVector(0, 1f, 0)
    );

    EyeModel rightEye = EyeModel.makeModel(
        new LXVector(20f, 20f, 0f),
        new LXVector(-1f, 0, 0),
        new LXVector(0, 1f, 0), true
    );


    int NUM_WHISKER_FIXTURES = 6;
    LXFixture[] allFixtures = new LXFixture[
        NUM_WHISKER_FIXTURES +
        leftEye.getEyeColumns().size() +
        rightEye.getEyeColumns().size()
    ];
    int i = 0;
    allFixtures[i++] = wh0a;
    allFixtures[i++] = wh0b;
    allFixtures[i++] = wh1a;
    allFixtures[i++] = wh1b;
    allFixtures[i++] = wh2a;
    allFixtures[i++] = wh2b;
    for (EyeModel.EyeColumn eyeColumn : leftEye.getEyeColumns()) {
      allFixtures[i++] = eyeColumn;
    }
    for (EyeModel.EyeColumn eyeColumn : rightEye.getEyeColumns()) {
      allFixtures[i++] = eyeColumn;
    }

    return new TotemModel(
        allFixtures,
        new VectorStripModel[] { wh0a, wh1a, wh2a },
        new VectorStripModel[] { wh0b, wh1b, wh2b },
        leftEye, rightEye
    );
  }

  private static float solveForX(float dist, LXVector end, float startY, float startZ) {
    return (float) Math.sqrt( (double)(
        dist * dist -
        ( startY - end.y ) * ( startY - end.y ) -
        ( startZ - end.z ) * ( startZ - end.z )
    ));
  }


  private TotemModel(LXFixture[] allFixtures, VectorStripModel[] leftWhiskers, VectorStripModel[] rightWhiskers,
                     EyeModel leftEye, EyeModel rightEye
  ) {
    super(allFixtures);

    this.leftWhiskers = Arrays.asList(leftWhiskers);
    this.rightWhiskers = Arrays.asList(rightWhiskers);

    this.leftEye = leftEye;
    this.rightEye = rightEye;
  }

  public LXFixture[] getWhiskerFixtures() {
    LXFixture[] whiskers = new LXFixture[ leftWhiskers.size() + rightWhiskers.size() ];
    int i = 0;

    for (LXFixture whisker : leftWhiskers) {
      whiskers[i++] = whisker;
    }
    for (LXFixture whisker : rightWhiskers) {
      whiskers[i++] = whisker;
    }
    return whiskers;
  }
}
