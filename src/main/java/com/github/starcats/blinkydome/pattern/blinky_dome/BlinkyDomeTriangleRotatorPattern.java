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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Calibration pattern to rotate triangles on the blinky-dome.
 * <p>
 * Provides a DUMP button that dumps the updated geometry to /var/tmp/led-vertex-locations.csv.
 */
public class BlinkyDomeTriangleRotatorPattern
        extends AbstractFixtureSelectorPattern<BlinkyModel, BlinkyDomeTriangleRotatorPattern.TriangleSelectorType> {
    public enum TriangleSelectorType {
        /**
         * Show test pattern on all triangles.
         */
        ALL,
        /**
         * Show test pattern on triangles connected to the selected StarPusher node.
         */
        STARPUSHER,
        /**
         * Show test pattern on triangles connected to the selected StarPusher port.
         */
        PORT,
        /**
         * Show test pattern on triangles connected to the selected triangle, but show weak test pattern on all triangles.
         */
        TRIANGLE_ALL,
        /**
         * Show test pattern on triangles connected to the selected triangle.
         */
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

    private double animation = 0;

    private void colorFixture(LXFixture fixture, boolean placeholder) {
        BlinkyTriangle triangle = (BlinkyTriangle) fixture;
        int colorStep = 255 / BlinkyTriangle.NUM_LEDS_PER_SIDE;
        int color = placeholder ? 255 : (int) Math.round(255 * (animation - Math.floor(animation)));
        ;
        int i = 0;
        int scale = placeholder ? 4 : 1;
        for (BlinkyLED point : triangle.getPixelChain()) {
            // Iterate over all the pointsTyped, which is the LEDs in pixelpusher string order

            // First side: Red
            if (i < BlinkyTriangle.NUM_LEDS_PER_SIDE) {
                setColor(point.index, LX.rgb(color / scale, 0, 0));

                // Middle side: Green
            } else if (i < BlinkyTriangle.NUM_LEDS_PER_SIDE * 2) {
                setColor(point.index, LX.rgb(0, color / scale, 0));

                // Last side: Blue
            } else {
                setColor(point.index, LX.rgb(0, 0, color / scale));
            }

            i += 1;
            if (i % BlinkyTriangle.NUM_LEDS_PER_SIDE == 0) {
                //color = 255;
                color = placeholder ? 255 : (int) Math.round(255 * (animation - Math.floor(animation)));
            } else {
                color -= colorStep;
            }
        }
    }

    @Override
    public void run(double deltaMs) {
        animation += deltaMs / 1000;

        for (LXPoint point : model.points) {
            setColor(point.index, LX.hsb(0, 0, 0));
        }

        if (fixtureFamily.getEnum() == TriangleSelectorType.TRIANGLE_ALL) {
            for (BlinkyTriangle triangle : model.allTriangles) {
                colorFixture(triangle, true);
            }
        }

        // For every selected triangle, we paint each side a different red, green, or blue.
        // We fade the colors down the length of the sides to visualize directionality.

        for (LXFixture fixture : getCurrentFixtures()) {
            colorFixture(fixture, false);
        }
    }


    @Override
    protected EnumParameter<TriangleSelectorType> makeFixtureFamilyParameter() {
        return new EnumParameter<>("class", TriangleSelectorType.PORT);
    }

    @Override
    protected Object[] getFixtureKeysForFamily(TriangleSelectorType fixtureFamily) {
        if (fixtureFamily == TriangleSelectorType.ALL) {
            return new Object[]{"all"};
        } else if (fixtureFamily == TriangleSelectorType.STARPUSHER) {
            return model.getStarpusherAddressKeys().toArray();
        } else if (fixtureFamily == TriangleSelectorType.PORT) {
            return model.getStarpusherPortKeys().toArray();
        } else if (fixtureFamily == TriangleSelectorType.TRIANGLE || fixtureFamily == TriangleSelectorType.TRIANGLE_ALL) {
            Set<Integer> keys = new HashSet<>();
            for (int i = 0; i < model.allTriangles.size(); i++) {
                keys.add(i);
            }
            List<Integer> ints = new ArrayList<>(keys);
            ints.sort(Comparator.naturalOrder());
            return ints.toArray();

        } else {
            throw new RuntimeException("Unsupported fixture type: " + fixtureFamily);
        }

    }

    @Override
    protected List<? extends LXFixture> getFixturesByKey(TriangleSelectorType fixtureFamily, Object keyObj) {
        List<BlinkyTriangle> fixtures;

        if (fixtureFamily == TriangleSelectorType.ALL) {
            fixtures = model.allTriangles;
        } else if (fixtureFamily == TriangleSelectorType.STARPUSHER) {
            fixtures = model.getTriangleByStarpusherAddressKey((String) keyObj);

        } else if (fixtureFamily == TriangleSelectorType.PORT) {
            fixtures = model.getTriangleByStarpusherPortKey((String) keyObj);
        } else if (fixtureFamily == TriangleSelectorType.TRIANGLE || fixtureFamily == TriangleSelectorType.TRIANGLE_ALL) {
            fixtures = Collections.singletonList(model.allTriangles.get((Integer) keyObj));

        } else {
            throw new RuntimeException("Unsupported fixture type: " + fixtureFamily);
        }

        return fixtures;
    }

    public void dumpNewVertexLocationsCsv() {
        System.out.println("\n\n\nDUMPING NEW to /var/tmp/vertex-locations.csv!");
        System.out.println("--------------------");
        Path filePath = Path.of("/var/tmp/led-vertex-locations.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("domeGroup,domeIndex,vertex_1_x,vertex_1_y,vertex_1_z,vertex_2_x,vertex_2_y,vertex_2_z,vertex_3_x,vertex_3_y,vertex_3_z,spAddress,spPort,spFirstLedOffset");
            writer.newLine();

            for (BlinkyTriangle tri : model.allTriangles) {
                writer.write(String.valueOf(tri.domeGroup));
                writer.write(",");
                writer.write(String.valueOf(tri.domeGroupIndex));
                writer.write(",");

                // Heads up! In BlinkyDomeFactory, note that y and z dimensions are switched in mapping.
                // We need to make that same switch here so when it's reloaded, things are good.
                // HEY DEVELOPER: Make sure this switch stays consistent with BlinkyDomeFactory!

                writer.write(String.valueOf(tri.getVA().x));
                writer.write(",");
                writer.write(String.valueOf(tri.getVA().z)); // Note z and y switched
                writer.write(",");
                writer.write(String.valueOf(tri.getVA().y));
                writer.write(",");

                writer.write(String.valueOf(tri.getVB().x));
                writer.write(",");
                writer.write(String.valueOf(tri.getVB().z)); // Note z and y switched
                writer.write(",");
                writer.write(String.valueOf(tri.getVB().y));
                writer.write(",");

                writer.write(String.valueOf(tri.getVC().x));
                writer.write(",");
                writer.write(String.valueOf(tri.getVC().z));  // Note z and y switched
                writer.write(",");
                writer.write(String.valueOf(tri.getVC().y));
                writer.write(",");


                writer.write(tri.spAddress);
                writer.write(",");
                writer.write(String.valueOf(tri.spPort));
                writer.write(",");
                writer.write(String.valueOf(tri.firstSpIndex));

                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("An error occurred while writing to the file: " + e.getMessage());
        }
    }
}
