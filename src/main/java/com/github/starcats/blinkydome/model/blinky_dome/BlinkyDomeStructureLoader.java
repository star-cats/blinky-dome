package com.github.starcats.blinkydome.model.blinky_dome;

import heronarts.lx.LX;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;
import processing.data.Table;
import processing.data.TableRow;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Loads the structure of the Blinky Dome, including the triangles, harnesses, and nodes
 */
public class BlinkyDomeStructureLoader {

    /**
     * Color of the node / harness.
     * <p>
     * We refer to the top 3x harnesses as green, the next tier down as orange, and the bottom tier as red.
     */
    public enum StructureColor {
        GREEN, ORANGE, RED
    }

    /**
     * Type of harness.
     * <p>
     * Single harnesses are used for the red tier, double harnesses are used for the green and orange tiers.
     */
    public enum HarnessType {
        SINGLE, DOUBLE,
    }

    /**
     * Direction of the harness around the dome.
     * <p>
     * Harness direction governs triangle orientation.
     */
    public enum HarnessDirection {
        CLOCKWISE, COUNTER_CLOCKWISE,
    }

    /**
     * Triangle orientation.
     * <p>
     * The "front" of a triangle is the side where, when looking down, the first edge (red on the test pattern) is
     * on the left.
     * <p>
     * A triangle orientated in the UP direction has the front facing up, and the DOWN direction has the front facing
     * down.
     * <p>
     * Up, in this case is outwards from the origin of the dome sphere, down is towards the origin.
     */
    public enum Orientation {
        UP, DOWN,
    }

    /**
     * A node in the dome structure.
     */
    public static class Node {

        /**
         * IP address of the node
         */

        public final String address;

        /**
         * Color of the node
         */
        public final StructureColor color;

        /**
         * Position of the node
         */
        public final LXVector position;

        public Node(String address, StructureColor color, LXVector position) {
            this.address = address;
            this.color = color;
            this.position = position;
        }

        public int getRenderColor() {
            return getStructureRenderColor(color);
        }
    }

    /**
     * Data representing a triangle orientation arrow.
     */
    public static class TriangleOrientationArrow {
        public final Orientation orientation;
        public final LXVector centroid;

        public TriangleOrientationArrow(Orientation orientation, LXVector centroid) {
            this.orientation = orientation;
            this.centroid = centroid;
        }
    }

    /**
     * A dome wiring harness.
     */
    public class Harness {
        /**
         * Color of the harness.
         */
        public final StructureColor color;

        /**
         * Type of harness.
         */
        public final HarnessType type;

        /**
         * Direction of the harness around the dome.
         */
        public final HarnessDirection direction;

        /**
         * Vertices of the harness.
         */
        public final List<BlinkyDomeStructureTriangle.Vertex> vertices;

        /**
         * Orientations of the triangles on the harness.
         */
        public List<TriangleOrientationArrow> orientations = new ArrayList<>();

        public Harness(StructureColor color, HarnessType type, HarnessDirection direction, List<BlinkyDomeStructureTriangle.Vertex> vertices) {
            this.color = color;
            this.type = type;
            this.direction = direction;
            this.vertices = vertices;
            this.calculateOrientations();
        }

        /**
         * Calculates the orientations of the triangles on the harness, taking into consideration harness type and
         * direction around the dome.
         */
        private void calculateOrientations() {
            // This loop has a disgusting time complexity of O(n^2), but it's only run once at startup so it's fine.
            for (int i = 0; i < vertices.size() - 1; i++) {
                BlinkyDomeStructureTriangle.Vertex v1 = vertices.get(i);
                BlinkyDomeStructureTriangle.Vertex v2 = vertices.get(i + 1);

                BlinkyDomeStructureTriangle[] southAndNorth = getSouthAndNorthTriangles(v1.position, v2.position);
                BlinkyDomeStructureTriangle south = southAndNorth[0];
                BlinkyDomeStructureTriangle north = southAndNorth[1];

                if (type == HarnessType.DOUBLE) {
                    orientations.add(new TriangleOrientationArrow(direction == HarnessDirection.COUNTER_CLOCKWISE ? Orientation.DOWN : Orientation.UP, south.centroid));
                    orientations.add(new TriangleOrientationArrow(direction == HarnessDirection.COUNTER_CLOCKWISE ? Orientation.UP : Orientation.DOWN, north.centroid));
                }

                if (type == HarnessType.SINGLE) {
                    orientations.add(new TriangleOrientationArrow(direction == HarnessDirection.COUNTER_CLOCKWISE ? Orientation.UP : Orientation.DOWN, north.centroid));
                }
            }
        }

        /**
         * For a pair of dome vertices, returns the triangles that contain both vertices, in order from south to north.
         * <p>
         * South is towards the ground, north is towards the apex of the dome.
         */
        private BlinkyDomeStructureTriangle[] getSouthAndNorthTriangles(LXVector v1, LXVector v2) {
            List<BlinkyDomeStructureTriangle> same = new ArrayList<>();
            for (BlinkyDomeStructureTriangle triangle : triangles) {
                int v1Same = 0;
                int v2Same = 0;
                for (int i = 0; i < 3; i++) {
                    if (triangle.getVertex(i).position.dist(v1) < 0.01) {
                        v1Same++;
                    }
                    if (triangle.getVertex(i).position.dist(v2) < 0.01) {
                        v2Same++;
                    }
                }
                if (v1Same > 0 && v2Same > 0) {
                    same.add(triangle);
                }
            }
            if (same.size() != 2) {
                throw new RuntimeException("Expected 2 triangles, got " + same.size());
            }

            BlinkyDomeStructureTriangle t1 = same.get(0);
            BlinkyDomeStructureTriangle t2 = same.get(1);

            if (t1.centroid.y < t2.centroid.y) {
                return new BlinkyDomeStructureTriangle[]{t1, t2};
            } else {
                return new BlinkyDomeStructureTriangle[]{t2, t1};
            }
        }

        public int getRenderColor() {
            return getStructureRenderColor(color);
        }
    }

    /**
     * Returns the actual RGB color to user for rendering the structure.
     */
    private static int getStructureRenderColor(StructureColor color) {
        return switch (color) {
            case GREEN -> LX.rgb(50, 168, 82);
            case ORANGE -> LX.rgb(250, 130, 17);
            case RED -> LX.rgb(219, 24, 24);
            default -> throw new IllegalArgumentException("Invalid color: " + color);
        };
    }


    /**
     * All structural triangles in the dome.
     */
    private List<BlinkyDomeStructureTriangle> triangles;
    /**
     * All harnesses in the dome.
     */
    private List<Harness> harnesses = new LinkedList<>();
    /**
     * All nodes in the dome.
     */
    private List<Node> nodes = new LinkedList<>();

    private final PApplet p;

    public BlinkyDomeStructureLoader(PApplet p) {
        this.p = p;
        this.loadTriangles();
        this.defineHarnesses();
        this.defineNodes();
    }

    /**
     * Loads physical dome structure from vertex-locations.csv.
     */
    private void loadTriangles() {
        Table triangleTable = p.loadTable("vertex-locations.csv", "header,csv");
        if (triangleTable == null) {
            throw new RuntimeException("Error: could not load LED position data");
        }

        List<BlinkyDomeStructureTriangle> allTriangles = new ArrayList<>(triangleTable.getRowCount());
        int index = 0;
        for (TableRow row : triangleTable.rows()) {
            LXVector v1 = new LXVector(
                    row.getFloat("vertex_1_x"),
                    row.getFloat("vertex_1_z"),
                    row.getFloat("vertex_1_y")
            );
            LXVector v2 = new LXVector(
                    row.getFloat("vertex_2_x"),
                    row.getFloat("vertex_2_z"),
                    row.getFloat("vertex_2_y")
            );
            LXVector v3 = new LXVector(
                    row.getFloat("vertex_3_x"),
                    row.getFloat("vertex_3_z"),
                    row.getFloat("vertex_3_y")
            );
            allTriangles.add(new BlinkyDomeStructureTriangle(index++, v1, v2, v3));
        }
        triangles = allTriangles;
    }

    /**
     * Defines all wiring harnesses.
     * <p>
     * This is hardcoded for the BM2024 dome structure.
     */
    private void defineHarnesses() {
        addHarnessFromIndices(StructureColor.GREEN, HarnessType.DOUBLE, HarnessDirection.COUNTER_CLOCKWISE, new int[][]{{10, 0}, {13, 0}, {12, 1}, {28, 1}, {44, 1}, {60, 1}, {13, 0}});
        addHarnessFromIndices(StructureColor.GREEN, HarnessType.DOUBLE, HarnessDirection.COUNTER_CLOCKWISE, new int[][]{{10, 0}, {8, 0}, {7, 1}, {24, 0}, {23, 1}, {40, 0}});
        addHarnessFromIndices(StructureColor.GREEN, HarnessType.DOUBLE, HarnessDirection.CLOCKWISE, new int[][]{{10, 0}, {72, 0}, {55, 1}, {56, 0}, {39, 1}, {40, 0}});
        addHarnessFromIndices(StructureColor.ORANGE, HarnessType.DOUBLE, HarnessDirection.COUNTER_CLOCKWISE, new int[][]{{5, 0}, {3, 0}, {1, 0}, {0, 1}, {19, 0}, {17, 0}});
        addHarnessFromIndices(StructureColor.ORANGE, HarnessType.DOUBLE, HarnessDirection.CLOCKWISE, new int[][]{{5, 0}, {65, 0}, {67, 0}, {48, 1}, {49, 0}, {51, 0}});
        addHarnessFromIndices(StructureColor.ORANGE, HarnessType.DOUBLE, HarnessDirection.COUNTER_CLOCKWISE, new int[][]{{17, 0}, {16, 1}, {35, 0}, {33, 0}, {32, 1}, {51, 0}});
        addHarnessFromIndices(StructureColor.RED, HarnessType.SINGLE, HarnessDirection.COUNTER_CLOCKWISE, new int[][]{{6, 2}, {4, 2}, {2, 2}, {0, 2}, {0, 0}, {20, 2}, {18, 2}, {16, 2}, {16, 0}, {36, 2}, {34, 2}});
        addHarnessFromIndices(StructureColor.RED, HarnessType.SINGLE, HarnessDirection.CLOCKWISE, new int[][]{{6, 2}, {64, 2}, {66, 2}, {68, 2}, {48, 0}, {48, 2}, {50, 2}, {52, 2}, {32, 0}, {32, 2}, {34, 2}});
    }

    /**
     * Defines all nodes.
     * <p>
     * This is hardcoded for the BM2024 dome structure.
     */
    private void defineNodes() {
        nodes.add(new Node("10.1.1.2", StructureColor.GREEN, triangles.get(10).getVertex(0).position));
        nodes.add(new Node("10.1.1.3", StructureColor.ORANGE, triangles.get(5).getVertex(0).position));
        nodes.add(new Node("10.1.1.4", StructureColor.ORANGE, triangles.get(17).getVertex(0).position));
        nodes.add(new Node("10.1.1.5", StructureColor.RED, triangles.get(6).getVertex(2).position));
    }

    private void addHarnessFromIndices(StructureColor color, HarnessType type, HarnessDirection direction, int[][] indices) {
        List<BlinkyDomeStructureTriangle.Vertex> harnessVertices = new ArrayList<>(indices.length);
        for (int[] index : indices) {
            int triangleIndex = index[0];
            int vertexIndex = index[1];
            harnessVertices.add(triangles.get(triangleIndex).getVertex(vertexIndex));
        }
        harnesses.add(new Harness(color, type, direction, harnessVertices));
    }

    /**
     * Returns all harnesses.
     */
    public List<Harness> getHarnesses() {
        return harnesses;
    }

    /**
     * Returns all nodes.
     */
    public List<Node> getNodes() {
        return nodes;
    }

    /**
     * Returns all triangles.
     */
    public List<BlinkyDomeStructureTriangle> getTriangles() {
        return triangles;
    }
}
