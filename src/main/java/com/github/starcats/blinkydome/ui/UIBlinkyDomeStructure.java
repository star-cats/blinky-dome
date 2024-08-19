package com.github.starcats.blinkydome.ui;

import com.github.starcats.blinkydome.model.blinky_dome.BlinkyDomeStructureTriangle;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.p3lx.ui.UI;
import heronarts.p3lx.ui.component.UIButton;
import heronarts.p3lx.ui.component.UIKnob;
import heronarts.p3lx.ui.studio.UICollapsibleSection;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * UI for the Blinky Dome structure, shown in the global left side panel.
 */
public class UIBlinkyDomeStructure extends UICollapsibleSection {

    /**
     * The vertices that have been traced by the user.
     */
    private final List<BlinkyDomeStructureTriangle.Vertex> trace = new LinkedList<>();

    /**
     * When true, allow the user to click nodes to trace the structure.
     */
    private final BooleanParameter tracing = new BooleanParameter("Tracing");

    public UIBlinkyDomeStructure(UI ui, BlinkyDomePreview domePreview, float x, float y, float w) {
        super(ui, x, y, w, 200);

        tracing.addListener((p) -> {
            if (!tracing.getValueb()) {
                // When user stops tracing, print out the trace of {triangleIndex, vertexIndex} pairs.
                String values = trace.stream().map(
                        vertex ->
                                "{"
                                        + String.valueOf(vertex.triangleIndex) + ", "
                                        + String.valueOf(vertex.vertexIndex)
                                        + "}").collect(Collectors.joining(", "));
                System.out.println(values);
                trace.clear();
                domePreview.setSelectedVertices(trace);
            }
        });

        domePreview.setVertexSelectedEventConsumer(event -> {
            if (tracing.getValueb()) {
                // Add the selected vertex to the trace.
                trace.add(event.vertex);
                domePreview.setSelectedVertices(trace);
            }
        });

        setTitle("Blinky Dome Structure");

        new UIButton(0, 100, 50, 20)
                .setLabel("Trace")
                .setParameter(tracing)
                .addToContainer(this);

        // Knob to control strut alpha.
        new UIKnob(0, 0, 50, 20)
                .setParameter(domePreview.strutAlphaParam)
                .addToContainer(this);

        // Knob to control node alpha.
        new UIKnob(50, 0, 50, 20)
                .setParameter(domePreview.nodeAlphaParam)
                .addToContainer(this);

        // Knob to control triangle orientation arrow alpha.
        new UIKnob(100, 0, 50, 20)
                .setParameter(domePreview.arrowAlphaParam)
                .addToContainer(this);
    }
}
