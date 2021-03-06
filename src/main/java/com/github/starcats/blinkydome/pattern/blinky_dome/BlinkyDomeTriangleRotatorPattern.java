package com.github.starcats.blinkydome.pattern.blinky_dome;

import com.github.starcats.blinkydome.model.blinky_dome.BlinkyLED;
import com.github.starcats.blinkydome.model.blinky_dome.BlinkyModel;
import com.github.starcats.blinkydome.model.blinky_dome.BlinkyTriangle;
import com.github.starcats.blinkydome.pattern.AbstractFixtureSelectorPattern;
import heronarts.lx.LX;
import heronarts.lx.model.LXFixture;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.EnumParameter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Calibration pattern to rotate triangles on the blinky-dome
 */
public class BlinkyDomeTriangleRotatorPattern
        extends AbstractFixtureSelectorPattern<BlinkyModel, BlinkyDomeTriangleRotatorPattern.TriangleSelectorType>
{
  public enum TriangleSelectorType {
    DOME_GROUP,
    DOME_INDEX,
    PP_GROUP,
    PP_PORT,
    TRIANGLE
  }

  public final BooleanParameter rotateSelected;
  public final BooleanParameter flipSelected;
  public final BooleanParameter dumpButton;


  public BlinkyDomeTriangleRotatorPattern(LX lx, BlinkyModel model) {
    super(lx, model);


    // Button to trigger triangle rotation
    this.rotateSelected = new BooleanParameter("rotate", false)
            .setMode(BooleanParameter.Mode.MOMENTARY)
            .setDescription("Rotate the pixels in the underlying triangle to match visualized mapping");
    addParameter(this.rotateSelected);

    this.rotateSelected.addListener(param -> {
      if (param.getValue() == 0) return;

      this.rotateSelectedTriangles();
    });


    // Button to trigger triangle flipping
    this.flipSelected = new BooleanParameter("flip", false)
            .setMode(BooleanParameter.Mode.MOMENTARY)
            .setDescription("Flip the pixels in the underlying triangle (eg CW to CCW)");
    addParameter(this.flipSelected);

    this.flipSelected.addListener(param -> {
      if (param.getValue() == 0) return;

      this.flipSelectedTriangles();
    });


    // Button to dump mappings
    this.dumpButton = new BooleanParameter("DUMP", false)
            .setMode(BooleanParameter.Mode.MOMENTARY)
            .setDescription("Dump the triangle mappings into a new csv format! (Check console output!)");
    addParameter(this.dumpButton);

    this.dumpButton.addListener(param -> {
      if (param.getValue() == 0) return;

      this.dumpNewVertexLocationsCsv();
    });
  }

  private void rotateSelectedTriangles() {
    for (LXFixture fixture : getCurrentFixtures()) {
      BlinkyTriangle triangle = (BlinkyTriangle) fixture;
      triangle.rotate();
    }
  }

  private void flipSelectedTriangles() {
    for (LXFixture fixture : getCurrentFixtures()) {
      BlinkyTriangle triangle = (BlinkyTriangle) fixture;
      triangle.flip();
    }
  }

  @Override
  public void run(double deltaMs) {
    for (LXPoint point : model.points) {
      setColor(point.index, LX.hsb(0, 0, 0));
    }

    // For every selected triangle, we paint each side a different red, green, or blue.
    // We fade the colors down the length of the sides to visualize directionality.

    int colorStep = 255 / BlinkyTriangle.NUM_LEDS_PER_SIDE;
    for (LXFixture fixture : getCurrentFixtures()) {
      BlinkyTriangle triangle = (BlinkyTriangle) fixture;

      int color = 255;
      int i = 0;
      for (BlinkyLED point : triangle.getPixelChain()) {
        // Iterate over all the pointsTyped, which is the LEDs in pixelpusher string order

        // First side: Red
        if (i < BlinkyTriangle.NUM_LEDS_PER_SIDE) {
          setColor(point.index, LX.rgb(color, 0, 0));

        // Middle side: Green
        } else if (i < BlinkyTriangle.NUM_LEDS_PER_SIDE * 2) {
          setColor(point.index, LX.rgb(0, color, 0));

        // Last side: Blue
        } else {
          setColor(point.index, LX.rgb(0, 0, color));
        }

        i += 1;
        if (i % BlinkyTriangle.NUM_LEDS_PER_SIDE == 0) {
          color = 255;
        } else {
          color -= colorStep;
        }
      }
    }
  }


  @Override
  protected EnumParameter<TriangleSelectorType> makeFixtureFamilyParameter() {
    return new EnumParameter<>("class", TriangleSelectorType.DOME_INDEX);
  }

  @Override
  protected Object[] getFixtureKeysForFamily(TriangleSelectorType fixtureFamily) {
    Set<Integer> keys;
    if (fixtureFamily == TriangleSelectorType.DOME_GROUP) {
      keys = model.getTriangleGroupsKeys();

    } else if (fixtureFamily == TriangleSelectorType.DOME_INDEX) {
      keys = model.allTriangles.stream()
              .map(triangle -> triangle.domeGroupIndex)
              .collect(Collectors.toSet());

    } else if (fixtureFamily == TriangleSelectorType.PP_GROUP) {
      keys = model.allTriangles.stream()
              .map(triangle -> triangle.ppGroup)
              .collect(Collectors.toSet());

    } else if (fixtureFamily == TriangleSelectorType.PP_PORT) {
      keys = model.allTriangles.stream()
              .map(triangle -> triangle.ppPort)
              .collect(Collectors.toSet());

    } else if (fixtureFamily == TriangleSelectorType.TRIANGLE) {
      keys = new HashSet<>();
      for (int i=0; i < model.allTriangles.size(); i++) {
        keys.add(i);
      }

    } else {
      throw new RuntimeException("Unsupported fixture type: " + fixtureFamily);
    }

    List<Integer> ints = new ArrayList<>(keys);
    ints.sort(Comparator.naturalOrder());

    return ints.toArray();
  }

  @Override
  protected List<? extends LXFixture> getFixturesByKey(TriangleSelectorType fixtureFamily, Object keyObj) {
    List<BlinkyTriangle> fixtures;
    Integer key = (Integer) keyObj;

    if (fixtureFamily == TriangleSelectorType.DOME_GROUP) {
      fixtures = model.getTrianglesByGroup(key);

    } else if (fixtureFamily == TriangleSelectorType.DOME_INDEX) {
      fixtures = model.allTriangles.stream()
              .filter(triangle -> triangle.domeGroupIndex == key)
              .collect(Collectors.toList());

    } else if (fixtureFamily == TriangleSelectorType.PP_PORT) {
      fixtures = model.allTriangles.stream()
              .filter(triangle -> triangle.ppPort == key)
              .collect(Collectors.toList());

    } else if (fixtureFamily == TriangleSelectorType.PP_GROUP) {
      fixtures = model.allTriangles.stream()
              .filter(triangle -> triangle.ppGroup == key)
              .collect(Collectors.toList());

    } else if (fixtureFamily == TriangleSelectorType.TRIANGLE) {
      fixtures = Collections.singletonList(model.allTriangles.get(key));

    } else {
      throw new RuntimeException("Unsupported fixture type: " + fixtureFamily);
    }

    return fixtures;
  }

  public void dumpNewVertexLocationsCsv() {
    System.out.println("\n\n\nDUMPING NEW vertex-locations.csv!");
    System.out.println("--------------------");
    System.out.println("domeGroup,domeIndex,vertex_1_x,vertex_1_y,vertex_1_z,vertex_2_x,vertex_2_y,vertex_2_z,vertex_3_x,vertex_3_y,vertex_3_z,ppGroup,ppPort,ppFirstLedOffset");

    for (BlinkyTriangle tri : model.allTriangles) {
      System.out.print(tri.domeGroup);
      System.out.print(",");
      System.out.print(tri.domeGroupIndex);
      System.out.print(",");

      // Heads up! In BlinkyDomeFactory, note that y and z dimensions are switched in mapping.
      // We need to make that same switch here so when it's reloaded, things are good.
      // HEY DEVELOPER: Make sure this switch stays consistent with BlinkyDomeFactory!

      System.out.print(tri.getVA().x);
      System.out.print(",");
      System.out.print(tri.getVA().z); // Note z and y switched
      System.out.print(",");
      System.out.print(tri.getVA().y);
      System.out.print(",");

      System.out.print(tri.getVB().x);
      System.out.print(",");
      System.out.print(tri.getVB().z); // Note z and y switched
      System.out.print(",");
      System.out.print(tri.getVB().y);
      System.out.print(",");

      System.out.print(tri.getVC().x);
      System.out.print(",");
      System.out.print(tri.getVC().z);  // Note z and y switched
      System.out.print(",");
      System.out.print(tri.getVC().y);
      System.out.print(",");


      System.out.print(tri.ppGroup);
      System.out.print(",");
      System.out.print(tri.ppPort);
      System.out.print(",");
      System.out.print(tri.firstPpIndex);

      System.out.print("\n");
    }
  }
}
