package com.github.starcats.blinkydome.ui;

import com.github.starcats.blinkydome.model.blinky_dome.BlinkyDomeStructureLoader;
import com.github.starcats.blinkydome.model.blinky_dome.BlinkyDomeStructureTriangle;
import heronarts.lx.LX;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXModel;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.transform.LXVector;
import heronarts.p3lx.LXStudio;
import heronarts.p3lx.ui.UI;
import heronarts.p3lx.ui.UI3dComponent;
import processing.core.PConstants;
import processing.core.PGraphics;
import processing.core.PMatrix3D;
import processing.core.PVector;
import processing.event.MouseEvent;
import processing.opengl.PGraphics3D;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class BlinkyDomePreview extends UI3dComponent {
    /**
     * X offset of preview window (this is hardcoded in P3LX, but private, so we replicate it here.
     */
    private static final int PREVIEW_WINDOW_X = 208;
    private static final int PREVIEW_WINDOW_Y = 0;
    /**
     * When user clicks the preview window, we project a ray from the camera through the click point and find the
     * closest vertex. This ray must pass within this distance threshold to be considered "clicked".
     */
    private static final float VERTEX_SELECTION_THRESHOLD = 10.0f;
    /**
     * Alpha of dome struts.
     */
    public final CompoundParameter strutAlphaParam = new CompoundParameter("Struts", 128.0, 0.0, 255.0);
    /**
     * Alpha of dome nodes.
     */
    public final CompoundParameter nodeAlphaParam = new CompoundParameter("Nodes", 128.0, 0.0, 255.0);
    /**
     * Alpha of triangle orientation arrows.
     */
    public final CompoundParameter arrowAlphaParam = new CompoundParameter("Arrows", 128.0, 0.0, 255.0);
    private final LXStudio.UI.PreviewWindow preview;
    private final BlinkyDomeStructureLoader structure;
    /**
     * The vertices that have been selected by the user, a green ball will be rendered at each selected vertex.
     */
    private List<BlinkyDomeStructureTriangle.Vertex> selectedVertices = new LinkedList<>();

    /**
     * We only have access to the PGraphics object with the correct modelView matrix within the onDraw function, so
     * following a mouse click, we set clickEvent, and then on the next onDraw call, we process the click event to
     * work out which vertex was clicked.
     */
    private boolean clickEvent = false;
    /**
     * Screen space x position of the most recent click event.
     */
    private float clickX = 0f;
    /**
     * Screen space y position of the most recent click event.
     */
    private float clickY = 0f;

    /**
     * Event listener that will be called when the user clicks on a vertex.
     */
    private Consumer<VertexSelectedEvent> vertexSelectedEventConsumer;

    public BlinkyDomePreview(LXStudio lx, LXModel model) {
        super();
        this.preview = lx.ui.preview;
        structure = new BlinkyDomeStructureLoader(lx.applet);
        // Force this component to get all mouseEvent so that other UI controls don't eat them.
        lx.applet.registerMethod("mouseEvent", this);
    }

    /**
     * Draws a small x, y, z (red, green, blue) axis at the origin.
     */
    private static void drawAxes(PGraphics pg) {
        pg.noFill();
        pg.strokeWeight(2.0f);
        pg.stroke(LX.rgb(255, 0, 0));
        pg.line(0, 0, 0, 20, 0, 0);
        pg.stroke(LX.rgb(0, 255, 0));
        pg.line(0, 0, 0, 0, 20, 0);
        pg.stroke(LX.rgb(0, 0, 255));
        pg.line(0, 0, 0, 0, 0, 20);
    }

    /**
     * Find the mid-point between two vectors.
     */
    private static LXVector midpoint(LXVector a, LXVector b) {
        return a.copy().add(b).mult(0.5f);
    }

    /**
     * Moves a vertex along the ray from the origin of the sphere by distance.
     */
    private static LXVector moveAlongOriginRay(LXVector vertex, float distance) {
        return vertex.copy().add(vertex.copy().normalize().mult(distance));
    }

    /**
     * Set the consumer to be called when a user clicks a vertex.
     */
    public void setVertexSelectedEventConsumer(Consumer<VertexSelectedEvent> consumer) {
        vertexSelectedEventConsumer = consumer;
    }

    /**
     * Updates the selected vertices, green balls will be rendered at each selected vertex.
     */
    public void setSelectedVertices(List<BlinkyDomeStructureTriangle.Vertex> vertices) {
        selectedVertices = vertices;
    }

    /**
     * Handles mouse event, checking if they fall within the preview window.
     */
    public void mouseEvent(MouseEvent e) {
        int mx = e.getX();
        int my = e.getY();
        boolean contains = mx >= PREVIEW_WINDOW_X && mx < preview.getWidth() + PREVIEW_WINDOW_X && my >= PREVIEW_WINDOW_Y && my < preview.getHeight() + PREVIEW_WINDOW_Y;

        if (e.getAction() == MouseEvent.CLICK && contains) {
            mouseClicked(e, mx, my);
        }
    }

    /**
     * This is the key function, draw all dome geometry here.
     */
    @Override
    protected void onDraw(UI ui, PGraphics pg) {
        float strutAlpha = strutAlphaParam.getValuef();
        float nodeAlpha = nodeAlphaParam.getValuef();
        float arrowAlpha = arrowAlphaParam.getValuef();

        drawAxes(pg);

        if (strutAlpha > 0) {
            drawStruts(pg, strutAlpha);
        }

        processVertexClickEvent();
        drawSelectedVertices(pg);

        if (nodeAlpha > 0) {
            drawHarnesses(pg, nodeAlpha);
        }

        if (arrowAlpha > 0) {
            drawOrientationArrows(pg, arrowAlpha);
        }

        if (nodeAlpha > 0) {
            drawNodes(pg, nodeAlpha);
        }

        drawToPlayaArrow(pg);
    }

    /**
     * Draws the yellow arrow and "To Playa" text.
     */
    private void drawToPlayaArrow(PGraphics pg) {
        float playaAngle = (float) Math.toRadians(160);
        LXVector playaDirection = new LXVector(1, 0, 0);
        playaDirection.rotate(playaAngle, 0, 1, 0);
        float playaDirectionLength = 150;
        LXVector playaDirectionTail = playaDirection.copy().mult(350);
        LXVector playaDirectionHead = playaDirection.copy().mult(350 + playaDirectionLength);
        pg.noFill();
        pg.stroke(LX.rgb(255, 255, 0));
        pg.strokeWeight(5f);
        drawArrow(pg, playaDirectionTail, playaDirectionHead, 25f, false);

        pg.pushMatrix();
        pg.translate(playaDirectionTail.x, playaDirectionTail.y + 20f, playaDirectionTail.z);
        pg.rotate(playaAngle + PConstants.PI, 0, 1, 0);
        pg.rotate(PConstants.PI, 1, 0, 1);
        pg.scale(2);
        pg.fill(LX.rgb(255, 255, 0));
        pg.text("To Playa", 0, 0);
        pg.popMatrix();
    }

    /**
     * Draws all nodes and their IP address text.
     */
    private void drawNodes(PGraphics pg, float nodeAlpha) {
        for (BlinkyDomeStructureLoader.Node node : structure.getNodes()) {
            pg.fill(node.getRenderColor(), nodeAlpha);
            pg.stroke(LX.rgb(128, 128, 128), nodeAlpha);
            pg.strokeWeight(2.0f);
            drawBoxNormalToDome(pg, node.position, 15f, 10, 5);

            pg.noStroke();
            pg.fill(LX.rgb(255, 255, 255), nodeAlpha);
            pg.stroke(LX.rgb(0, 0, 0), nodeAlpha);
            pg.strokeWeight(2.0f);
            drawTextNormalToDome(pg, node.position, node.address);
        }
    }

    /**
     * Draws the orientation arrows for each triangle.
     */
    private void drawOrientationArrows(PGraphics pg, float arrowAlpha) {
        for (BlinkyDomeStructureLoader.Harness harness : structure.getHarnesses()) {
            // Draw triangle direction arrows.
            pg.strokeWeight(1.5f);
            for (BlinkyDomeStructureLoader.TriangleOrientationArrow orientation : harness.orientations) {
                LXVector p = moveAlongOriginRay(orientation.centroid, orientation.orientation == BlinkyDomeStructureLoader.Orientation.UP ? 20.0f : -20.0f);
                if (orientation.orientation == BlinkyDomeStructureLoader.Orientation.UP) {
                    pg.stroke(LX.rgb(0, 255, 0), arrowAlpha);
                } else {
                    pg.stroke(LX.rgb(255, 0, 0), arrowAlpha);
                }
                pg.line(orientation.centroid.x, orientation.centroid.y, orientation.centroid.z, p.x, p.y, p.z);
                drawArrow(pg, orientation.centroid, p);
            }
        }
    }

    /**
     * Draws all harness wiring.
     */
    private void drawHarnesses(PGraphics pg, float nodeAlpha) {
        for (BlinkyDomeStructureLoader.Harness harness : structure.getHarnesses()) {
            // Draw the actual harness cable.
            pg.noFill();
            pg.strokeWeight(8.0f);
            pg.stroke(harness.getRenderColor(), nodeAlpha);
            for (int i = 0; i < harness.vertices.size() - 2; i++) {
                BlinkyDomeStructureTriangle.Vertex v1 = harness.vertices.get(i);
                BlinkyDomeStructureTriangle.Vertex v2 = harness.vertices.get(i + 1);
                LXVector p1 = moveAlongOriginRay(v1.position, 0.5f);
                LXVector p2 = moveAlongOriginRay(v2.position, 0.5f);
                drawLine(pg, p1, p2);
            }

            BlinkyDomeStructureTriangle.Vertex v1 = harness.vertices.get(harness.vertices.size() - 2);
            BlinkyDomeStructureTriangle.Vertex v2 = harness.vertices.get(harness.vertices.size() - 1);
            LXVector p1 = moveAlongOriginRay(v1.position, 0.5f);
            LXVector p2 = moveAlongOriginRay(midpoint(v1.position, v2.position), 0.5f);
            drawLine(pg, p1, p2);
        }
    }

    /**
     * Draws green balls at each selected vertex.
     */
    private void drawSelectedVertices(PGraphics pg) {
        pg.stroke(LX.rgb(0, 255, 0));
        pg.strokeWeight(5.0f);
        for (BlinkyDomeStructureTriangle.Vertex vertex : selectedVertices) {
            LXVector v = vertex.position;
            pg.pushMatrix();
            pg.translate(v.x, v.y, v.z);
            pg.sphere(5.0f);
            pg.popMatrix();
        }
    }

    /**
     * Project screen space x, y mouse coordinates into the 3D world and find the closest vertex.
     */
    private void processVertexClickEvent() {
        if (clickEvent) {
            PGraphics3D g3d = (PGraphics3D) this.preview.getGraphics();
            BlinkyDomeStructureTriangle.Vertex selectedVertex = findClosestVertex(clickX, clickY);
            if (vertexSelectedEventConsumer != null && selectedVertex != null) {
                vertexSelectedEventConsumer.accept(new VertexSelectedEvent(selectedVertex));
            }
            clickEvent = false;
        }
    }

    /**
     * Draws the dome struts.
     */
    private void drawStruts(PGraphics pg, float strutAlpha) {
        pg.stroke(LXColor.rgb(128, 128, 128), strutAlpha);
        pg.strokeWeight(1.0f);

        for (BlinkyDomeStructureTriangle t : structure.getTriangles()) {

            pg.line(t.v1.position.x, t.v1.position.y, t.v1.position.z, t.v2.position.x, t.v2.position.y, t.v2.position.z);
            pg.line(t.v2.position.x, t.v2.position.y, t.v2.position.z, t.v3.position.x, t.v3.position.y, t.v3.position.z);
            pg.line(t.v3.position.x, t.v3.position.y, t.v3.position.z, t.v1.position.x, t.v1.position.y, t.v1.position.z);
        }
    }

    /**
     * Draws text such that its surface is norm to the dome at the given position.
     */
    private void drawTextNormalToDome(PGraphics pg, LXVector position, String text) {
        LXVector v = moveAlongOriginRay(position, 6.0f);
        pg.pushMatrix();
        pg.applyMatrix(calculateNormalToDomeMatrix(v));
        pg.scale(0.5f);
        pg.textAlign(PConstants.CENTER, PConstants.CENTER);
        pg.text(text, 0, 0);
        pg.popMatrix();
    }

    /**
     * Draws a 3D box such that its surface is norm to the dome at the given position.
     */
    private void drawBoxNormalToDome(PGraphics pg, LXVector centroid, float width, float height, float depth) {
        pg.pushMatrix();
        pg.applyMatrix(calculateNormalToDomeMatrix(centroid));
        pg.beginShape(PConstants.QUADS);
        pg.vertex(-width / 2, -height / 2, 0.0f);
        pg.vertex(width / 2, -height / 2, 0.0f);
        pg.vertex(width / 2, height / 2, 0.0f);
        pg.vertex(-width / 2, height / 2, 0.0f);

        pg.vertex(-width / 2, -height / 2, 0.0f);
        pg.vertex(-width / 2, -height / 2, depth);
        pg.vertex(-width / 2, height / 2, depth);
        pg.vertex(-width / 2, height / 2, 0.0f);

        pg.vertex(width / 2, -height / 2, 0.0f);
        pg.vertex(width / 2, -height / 2, depth);
        pg.vertex(width / 2, height / 2, depth);
        pg.vertex(width / 2, height / 2, 0.0f);

        pg.vertex(-width / 2, -height / 2, 0.0f);
        pg.vertex(-width / 2, -height / 2, depth);
        pg.vertex(width / 2, -height / 2, depth);
        pg.vertex(width / 2, -height / 2, 0.0f);

        pg.vertex(-width / 2, height / 2, 0.0f);
        pg.vertex(-width / 2, height / 2, depth);
        pg.vertex(width / 2, height / 2, depth);
        pg.vertex(width / 2, height / 2, 0.0f);

        pg.vertex(-width / 2, -height / 2, depth);
        pg.vertex(width / 2, -height / 2, depth);
        pg.vertex(width / 2, height / 2, depth);
        pg.vertex(-width / 2, height / 2, depth);
        pg.endShape();
        pg.popMatrix();
    }

    /**
     * Returns a matrix that will rotate the coordinate system such that the z-axis is normal to the dome at the given
     * position and the x-axis is parallel to the dome's lines of latitude.
     */
    private PMatrix3D calculateNormalToDomeMatrix(LXVector point) {
        PVector normal = new PVector(point.x, point.y, point.z).normalize();

        // Calculate a reference up vector
        PVector up = new PVector(0, 1, 0);

        // Calculate tangent (perpendicular to normal and up)
        PVector tangent = normal.cross(up).normalize();

        // Recalculate up to ensure it's perpendicular to both normal and tangent
        up = normal.cross(tangent).normalize();

        PMatrix3D rotationMatrix = new PMatrix3D();
        rotationMatrix.translate(point.x, point.y, point.z);
        rotationMatrix.apply(tangent.x, up.x, normal.x, 0, tangent.y, up.y, normal.y, 0, tangent.z, up.z, normal.z, 0, 0, 0, 0, 1);

        return rotationMatrix;
    }

    /**
     * Draws an arrow from tail to tip.
     */
    private void drawArrow(PGraphics pg, LXVector tail, LXVector tip) {
        drawArrow(pg, tail, tip, 4.0f, true);
    }

    /**
     * Draws an arrow from tail to tip with head size.
     */
    private void drawArrow(PGraphics pg, LXVector tail, LXVector tip, float headSize, boolean both) {
        LXVector direction = tip.copy().add(tail.copy().mult(-1)).normalize();
        LXVector reverse = direction.copy().mult(-1);
        LXVector leftHeadDirection = reverse.copy().rotate(PConstants.PI / 6, -reverse.y, reverse.x, 0);
        LXVector rightHeadDirection = reverse.copy().rotate(-PConstants.PI / 6, -reverse.y, reverse.x, 0);
        LXVector northHeadDirection = reverse.copy().rotate(PConstants.PI / 6, -reverse.z, 0, reverse.x);
        LXVector southHeadDirection = reverse.copy().rotate(-PConstants.PI / 6, -reverse.z, 0, reverse.x);
        drawLine(pg, tail, tip);
        drawLine(pg, tip, tip.copy().add(leftHeadDirection.mult(headSize)));
        drawLine(pg, tip, tip.copy().add(rightHeadDirection.mult(headSize)));
        if (both) {
            drawLine(pg, tip, tip.copy().add(northHeadDirection.mult(headSize)));
            drawLine(pg, tip, tip.copy().add(southHeadDirection.mult(headSize)));
        }
    }

    /**
     * Draws a line from a to b
     */
    private void drawLine(PGraphics pg, LXVector a, LXVector b) {
        pg.line(a.x, a.y, a.z, b.x, b.y, b.z);
    }

    protected void mouseClicked(MouseEvent mouseEvent, float mx, float my) {
        clickEvent = true;
        clickX = mx;
        clickY = my;
    }

    /**
     * Mays a screen space x, y coordinate to a world space vector pointing from the camera eye position to the
     * clicked point.
     */
    private LXVector screenToWorld(PGraphics3D g, float x, float y) {
        // Get the current modelview and projection matrices
        PMatrix3D projection = g.projection;
        PMatrix3D modelView = g.modelview;

        // Unproject the mouse coordinates
        float[] viewport = {PREVIEW_WINDOW_X, PREVIEW_WINDOW_Y, preview.getWidth(), preview.getHeight()};
        float[] nearPoint = new float[3];
        float[] farPoint = new float[3];

        // Get two points along the ray
        unproject(x, y, 0, modelView, projection, viewport, nearPoint);
        unproject(x, y, 1, modelView, projection, viewport, farPoint);

        // Calculate the direction vector
        PVector direction = new PVector(farPoint[0] - nearPoint[0],
                farPoint[1] - nearPoint[1],
                farPoint[2] - nearPoint[2]);

        // Normalize the vector
        direction.normalize();

        return new LXVector(direction.x, direction.y, direction.z);
    }

    private void unproject(float winX, float winY, float winZ,
                           PMatrix3D modelView, PMatrix3D projection,
                           float[] viewport, float[] objPos) {
        // Transformation matrices
        PMatrix3D m = projection.get();
        m.apply(modelView);

        // Invert the transformation matrix
        PMatrix3D invM = m.get();
        invM.invert();

        float[] in = new float[4];
        in[0] = (winX - viewport[0]) / viewport[2] * 2.0f - 1.0f;
        in[1] = -((winY - viewport[1]) / viewport[3] * 2.0f - 1.0f);
        in[2] = 2.0f * winZ - 1.0f;
        in[3] = 1.0f;

        // Object coordinates
        float[] out = new float[4];
        invM.mult(in, out);

        if (out[3] == 0.0f) {
            return;
        }

        out[3] = 1.0f / out[3];

        objPos[0] = out[0] * out[3];
        objPos[1] = out[1] * out[3];
        objPos[2] = out[2] * out[3];
    }

    /**
     * Returns the current camera position.
     */
    private LXVector getCameraPosition(PGraphics3D g3d) {
        return new LXVector(g3d.cameraX, g3d.cameraY, g3d.cameraZ);
    }

    /**
     * Finds the closest vertex to the camera that's within the VERTEX_SELECTION_THRESHOLD of the click point ray.
     */
    private BlinkyDomeStructureTriangle.Vertex findClosestVertex(float clickX, float clickY) {
        PGraphics3D g3d = (PGraphics3D) this.preview.getGraphics();

        LXVector direction = screenToWorld(g3d, clickX, clickY);
        LXVector camera = getCameraPosition(g3d);

        // For every vertex in the model calculate its distance from the camera, and its distance from the ray between
        // the camera and click point. Order by distance from camera ascending.
        List<VertexWithDistance> vertByDistanceAsc = structure.getTriangles().stream().flatMap(triangle -> Stream.of(
                new VertexWithDistance(triangle.v1, camera.dist(triangle.v1.position), closestDistanceRayToVertex(camera, direction, triangle.v1.position)),
                new VertexWithDistance(triangle.v2, camera.dist(triangle.v2.position), closestDistanceRayToVertex(camera, direction, triangle.v2.position)),
                new VertexWithDistance(triangle.v3, camera.dist(triangle.v3.position), closestDistanceRayToVertex(camera, direction, triangle.v3.position))
        )).sorted(Comparator.comparingDouble(vertex -> vertex.cameraDistance)).toList();

        // Find the closest vertex to the camera that's within the VERTEX_SELECTION_THRESHOLD of the click point ray,
        // if none are found, return null.
        for (VertexWithDistance vertex : vertByDistanceAsc) {
            if (vertex.clickDistance < VERTEX_SELECTION_THRESHOLD) {
                return vertex.vertex;
            }
        }
        return null;
    }

    /**
     * Returns the closest distance from a ray in rayDirection from rayOrigin to vertex.
     */
    private float closestDistanceRayToVertex(LXVector rayOrigin, LXVector rayDirection, LXVector vertex) {
        // Ensure rayDirection is normalized
        rayDirection.normalize();

        // Calculate vector from ray origin to vertex
        LXVector w = vertex.copy().add(rayOrigin.copy().mult(-1));

        // Project w onto ray direction
        float t = w.dot(rayDirection);

        // Calculate closest point on ray
        LXVector closestPoint = rayOrigin.copy().add(rayDirection.copy().mult(t));

        // Calculate distance
        return closestPoint.dist(vertex);
    }

    /**
     * Event fired when a user clicks a vertex in the preview window.
     */
    public static class VertexSelectedEvent {
        public final BlinkyDomeStructureTriangle.Vertex vertex;

        public VertexSelectedEvent(BlinkyDomeStructureTriangle.Vertex vertex) {
            this.vertex = vertex;
        }
    }

    /**
     * A vertex with its distance from the camera and click ray.
     */
    private static class VertexWithDistance {
        public final BlinkyDomeStructureTriangle.Vertex vertex;
        /**
         * Distance of the vertex from the camera.
         */
        public final float cameraDistance;
        /**
         * Distance of the vertex from the ray between the camera and the click point.
         */
        public final float clickDistance;

        public VertexWithDistance(
                BlinkyDomeStructureTriangle.Vertex vertex,
                float cameraDistance,
                float clickDistance) {
            this.vertex = vertex;
            this.cameraDistance = cameraDistance;
            this.clickDistance = clickDistance;
        }
    }
}
