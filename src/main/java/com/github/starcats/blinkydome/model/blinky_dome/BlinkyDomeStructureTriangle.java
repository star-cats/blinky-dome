package com.github.starcats.blinkydome.model.blinky_dome;

import heronarts.lx.transform.LXVector;

/**
 * A triangle in the Blinky Dome structure.
 * <p>
 * Note this represents the physical structure (e.g. metal struts) of the dome, not the LED layout. LEDs are
 * represented by the @{link BlinkyDomeTriangle} class.
 */
public class BlinkyDomeStructureTriangle {

    /**
     * The row index of this triangle from vertex-locations.csv file.
     */
    public final int triangleIndex;

    /**
     * A vertex of the triangle
     */
    public static class Vertex {
        /**
         * The index of the triangle this vertex belongs to.
         */
        public final int triangleIndex;
        /**
         * The index of the vertex within the triangle.
         */
        public final int vertexIndex;
        /**
         * The position of this vertex.
         */
        public final LXVector position;

        public Vertex(int triangleIndex, int vertexIndex, LXVector position) {
            this.triangleIndex = triangleIndex;
            this.vertexIndex = vertexIndex;
            this.position = position;
        }
    }

    /**
     * Vertex 1 of the triangle.
     */
    public final Vertex v1;
    /**
     * Vertex 2 of the triangle.
     */
    public final Vertex v2;
    /**
     * Vertex 3 of the triangle.
     */
    public final Vertex v3;
    /**
     * The centroid of all three triangle vertices (e.g. the "center" of the triangle).
     */
    public final LXVector centroid;

    public BlinkyDomeStructureTriangle(int triangleIndex, LXVector v1, LXVector v2, LXVector v3) {
        this.triangleIndex = triangleIndex;
        this.v1 = new Vertex(triangleIndex, 0, v1);
        this.v2 = new Vertex(triangleIndex, 1, v2);
        this.v3 = new Vertex(triangleIndex, 2, v3);
        this.centroid = this.v1.position.copy().add(this.v2.position).add(this.v3.position).mult(1.0f / 3.0f);
    }

    /**
     * Returns the vertex at the given index.
     */
    public Vertex getVertex(int index) {
        return switch (index) {
            case 0 -> v1;
            case 1 -> v2;
            case 2 -> v3;
            default -> throw new IllegalArgumentException("Invalid vertex index: " + index);
        };
    }
}
