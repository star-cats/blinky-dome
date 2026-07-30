#!/usr/bin/env python3
"""Regenerate Fixtures/V3DomeHarness.lxf with geodesically-correct placement.

The fixture's identity is preserved exactly: the same 225 strip components in
the same order, carrying the same labels, tags, meta (box / harness / port /
position / triangle / side) and the same Art-Net output table. Only where each
strip sits -- x, y, z and direction -- is recomputed.

That distinction matters. Component order fixes each strip's pixel index, and
the outputs map pixel ranges onto universes, so leaving both alone means every
pattern and every channel already built against this fixture keeps working; the
LEDs simply move to where they actually are on the dome.

Placement comes from the same 3V 5/9 geodesic that generate_v3_dome_model.py
draws, so the LEDs and the scaffold mesh are guaranteed to agree:

  - Subdivide a point-up icosahedron to 3V, project onto the sphere, and keep
    the top ten face rings -- 75 faces, one LED triangle each.
  - An LED triangle's corners are the midpoints of its face's three struts,
    which is why the LEDs read as a smaller triangle inset within each panel.
  - Triangles are numbered 1..75 ring by ring from the apex down, and by
    azimuth within a ring. The wiring table in constants.V3_HARNESS maps those
    numbers onto boxes and harnesses.

Absolute azimuth is deliberately left alone: the dome is five-fold symmetric,
so the whole fixture can be spun to taste with the instance yaw in the model.

Coordinates are metres, Y up, origin at the sphere centre.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

from constants import FIXTURES_DIR, V3_HARNESS
from generate_v3_dome_model import (
    FREQUENCY,
    RINGS_KEPT,
    icosahedron,
    subdivide,
    truncate,
)

OUTPUT_PATH = FIXTURES_DIR / V3_HARNESS["output_file"]

DOME_RADIUS_M = V3_HARNESS["dome_radius_m"]
LEDS_PER_SIDE = V3_HARNESS["leds_per_side"]
UNIVERSE_SIZE_PX = V3_HARNESS["universe_size_px"]
# A strip starts on its corner and runs LEDS_PER_SIDE pixels toward the next,
# stopping half a pixel short -- hence the 0.5 rather than the usual 1.
LED_SPACING_M = V3_HARNESS["led_triangle_edge_m"] / LEDS_PER_SIDE

Vec = tuple[float, float, float]

# Rings are found by grouping equal centroid heights, so they need a tolerance
# loose enough to absorb float noise and far tighter than the real ring gaps
# (the closest pair of rings differs by ~0.028 on the unit sphere).
RING_TOLERANCE = 1e-6


def rounded(value: float, digits: int) -> float:
    result = round(value, digits)
    return 0.0 if result == 0 else result


def midpoint(a: Vec, b: Vec) -> Vec:
    return tuple((a[axis] + b[axis]) / 2.0 for axis in range(3))


def centroid(points: list[Vec]) -> Vec:
    return tuple(sum(p[axis] for p in points) / len(points) for axis in range(3))


def azimuth(point: Vec) -> float:
    """Angle about the vertical axis, in [0, 2*pi)."""
    angle = math.atan2(point[2], point[0])
    return angle + 2.0 * math.pi if angle < 0 else angle


def normalize(vector: Vec) -> Vec:
    magnitude = math.sqrt(sum(axis * axis for axis in vector))
    return tuple(axis / magnitude for axis in vector)


def cross(a: Vec, b: Vec) -> Vec:
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def outward_winding(corners: list[Vec]) -> list[Vec]:
    """Order corners counter-clockwise as seen from outside the dome."""
    a, b, c = corners
    edge1 = tuple(b[axis] - a[axis] for axis in range(3))
    edge2 = tuple(c[axis] - a[axis] for axis in range(3))
    normal = (
        edge1[1] * edge2[2] - edge1[2] * edge2[1],
        edge1[2] * edge2[0] - edge1[0] * edge2[2],
        edge1[0] * edge2[1] - edge1[1] * edge2[0],
    )
    outward = centroid(corners)
    facing = sum(normal[axis] * outward[axis] for axis in range(3))
    return [a, b, c] if facing > 0 else [a, c, b]


def equilateral_panel(medial: list[Vec], edge_m: float) -> list[Vec]:
    """The rigid LED triangle seated in a face whose medial triangle is given.

    An LED triangle is three identical strips bolted into a fixed equilateral
    frame, so it cannot take the face's own slightly irregular shape -- a 3V
    dome's three strut lengths differ by about 18%. Instead it is centred on
    the face and rotated until its corners face the strut midpoints, which is
    what the fixture's "corners_face" metadata records.
    """
    centre = centroid(medial)
    outward = normalize(centre)
    # First corner aims at the first strut midpoint, flattened into the panel.
    toward = tuple(medial[0][axis] - centre[axis] for axis in range(3))
    lean = sum(toward[axis] * outward[axis] for axis in range(3))
    u = normalize(tuple(toward[axis] - lean * outward[axis] for axis in range(3)))
    v = cross(outward, u)

    # Corner radius of an equilateral triangle: edge / sqrt(3).
    radius = edge_m / math.sqrt(3.0)
    corners = []
    for step in range(3):
        angle = 2.0 * math.pi * step / 3.0
        corners.append(
            tuple(
                centre[axis] + radius * (math.cos(angle) * u[axis] + math.sin(angle) * v[axis])
                for axis in range(3)
            )
        )
    return corners


def led_triangles() -> dict[int, list[Vec]]:
    """Triangle number -> its three corners, in strip order, in metres.

    Corners start at the triangle's highest and wind outward-facing from there,
    so the numbering is reproducible run to run and consistent between the five
    identical fifths of the dome.
    """
    sphere, faces = subdivide(*icosahedron(), FREQUENCY)
    hubs, faces = truncate(sphere, faces, RINGS_KEPT)

    panels = []
    for face in faces:
        a, b, c = (hubs[index] for index in face)
        corners = [midpoint(a, b), midpoint(b, c), midpoint(c, a)]
        panels.append((centroid(corners), corners))

    # Ring by ring from the apex, then by azimuth inside each ring.
    panels.sort(key=lambda panel: -panel[0][1])
    rings: list[list[tuple[Vec, list[Vec]]]] = []
    for panel in panels:
        if rings and abs(rings[-1][0][0][1] - panel[0][1]) <= RING_TOLERANCE:
            rings[-1].append(panel)
        else:
            rings.append([panel])

    lit = [panel for ring in rings[:RINGS_KEPT] for panel in sorted(ring, key=lambda p: azimuth(p[0]))]
    expected = sum(len(harness) for box in V3_HARNESS["wiring"].values() for harness in box)
    if len(lit) != expected:
        raise ValueError(f"Wiring covers {expected} triangles, geometry offers {len(lit)}")

    triangles = {}
    for number, (_, medial) in enumerate(lit, start=1):
        scaled = [tuple(axis * DOME_RADIUS_M for axis in corner) for corner in medial]
        corners = outward_winding(
            equilateral_panel(scaled, V3_HARNESS["led_triangle_edge_m"])
        )
        highest = max(range(3), key=lambda i: (rounded(corners[i][1], 9), azimuth(corners[i])))
        triangles[number] = corners[highest:] + corners[:highest]
    return triangles


def strip_components(triangles: dict[int, list[Vec]]) -> list[dict]:
    """Three strips per triangle, walked in the installed wiring order."""
    components = []
    for box in V3_HARNESS["boxes"]:
        for harness_index, harness in enumerate(V3_HARNESS["wiring"][box], start=1):
            for position, number in enumerate(harness, start=1):
                corners = triangles[number]
                for side in (1, 2, 3):
                    corner = corners[side - 1]
                    end = corners[side % 3]
                    span = math.dist(corner, end)
                    direction = tuple((end[axis] - corner[axis]) / span for axis in range(3))
                    # Each LED owns one pitch of strip, so the first sits half a
                    # pitch in from the corner and the last half a pitch shy of
                    # the next -- the run of pixels is centred on the edge.
                    start = tuple(
                        corner[axis] + direction[axis] * LED_SPACING_M / 2.0
                        for axis in range(3)
                    )
                    components.append(
                        {
                            "type": "strip",
                            "label": f"{box} H{harness_index} P{position} · T{number} · side {side}",
                            "tags": ["triangle", box, f"harness{harness_index}", f"triangle{position}"],
                            "meta": {
                                "box": box,
                                "harness": harness_index,
                                "port": harness_index,
                                "position": position,
                                "triangle": number,
                                "side": side,
                                "flipped": False,
                            },
                            "x": rounded(start[0], 5),
                            "y": rounded(start[1], 5),
                            "z": rounded(start[2], 5),
                            "direction": {
                                "x": rounded(direction[0], 6),
                                "y": rounded(direction[1], 6),
                                "z": rounded(direction[2], 6),
                            },
                            "spacing": round(LED_SPACING_M, 6),
                            "numPoints": LEDS_PER_SIDE,
                        }
                    )
    return components


def artnet_outputs() -> list[dict]:
    """Universes walked in the same order the components were emitted.

    Each harness starts on a fresh universe (port * 3 - 3) and its pixels spill
    across as many universes as it needs, so a harness that is one triangle
    short simply uses one universe fewer.
    """
    outputs = []
    pixel = 0
    for box_index, box in enumerate(V3_HARNESS["boxes"], start=1):
        for harness_index, harness in enumerate(V3_HARNESS["wiring"][box], start=1):
            remaining = len(harness) * 3 * LEDS_PER_SIDE
            universe = (harness_index - 1) * 3
            while remaining > 0:
                count = min(UNIVERSE_SIZE_PX, remaining)
                outputs.append(
                    {
                        "protocol": "artnet",
                        "enabled": True,
                        "host": f"192.168.123.$ipJ{box_index}",
                        "universe": universe,
                        "channel": 0,
                        "byteOrder": "rgb",
                        "start": pixel,
                        "num": count,
                    }
                )
                pixel += count
                remaining -= count
                universe += 1
    return outputs


def controller_parameters() -> dict:
    return {
        f"ipJ{index}": {
            "type": "int",
            "default": 10 + index,
            "min": 0,
            "max": 255,
            "label": f"J{index} controller",
            "description": (
                f"Last octet of junction box J{index} — its IP is 192.168.123.x"
            ),
        }
        for index, _ in enumerate(V3_HARNESS["boxes"], start=1)
    }


def build_fixture() -> dict:
    triangles = led_triangles()
    components = strip_components(triangles)
    harness_sizes = [len(harness) for harness in V3_HARNESS["wiring"]["J1"]]

    return {
        "label": "V3 Dome Cable Harness",
        "tags": ["dome", "geodesic", "V3", "harness"],
        "parameters": controller_parameters(),
        "metadata": {
            "dome_radius_m": DOME_RADIUS_M,
            "led_triangle_edge_m": V3_HARNESS["led_triangle_edge_m"],
            "leds_per_side": LEDS_PER_SIDE,
            "leds_per_triangle": LEDS_PER_SIDE * 3,
            "triangles_per_harness": max(harness_sizes),
            "harnesses_per_box": len(harness_sizes),
            "harness_sizes": harness_sizes,
            "corners_face": "strut-midpoints",
            "junction_hub": V3_HARNESS["junction_hub"],
            "artnet": (
                "box IP = 192.168.123.<ipJn>, harness = port "
                f"(universe = port*3-3), {UNIVERSE_SIZE_PX} px/universe"
            ),
            "coordinate_system": V3_HARNESS["coordinate_system"],
            "geometry": "strip components (spacing set), 3 per triangle",
            "total_triangles": len(triangles),
            "total_strips": len(components),
            "total_pixels": len(components) * LEDS_PER_SIDE,
        },
        "components": components,
        "outputs": artnet_outputs(),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Regenerate the V3 dome cable harness fixture."
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=OUTPUT_PATH,
        help=f"Fixture output path (default: {OUTPUT_PATH}).",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    fixture = build_fixture()
    output_path = args.output.resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(fixture, indent=2) + "\n", encoding="utf-8")
    print(
        f"Wrote {output_path} "
        f"({fixture['metadata']['total_triangles']} triangles, "
        f"{fixture['metadata']['total_strips']} strips, "
        f"{fixture['metadata']['total_pixels']} pixels, "
        f"{len(fixture['outputs'])} outputs)"
    )


if __name__ == "__main__":
    main()
