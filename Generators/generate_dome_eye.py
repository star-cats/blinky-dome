#!/usr/bin/env python3
"""Generate the two dome-eye fixtures (DomeEyeL.lxf / DomeEyeR.lxf).

Each eye rebuilds one retired harness group's 16 triangle patches as two
interlocking rows of 8 BlinkyTriangles (alternating point-up / point-down)
following the arc of the dome. The band is laid out flat, then wrapped onto
the dome surface: horizontal position becomes azimuth along the latitude arc
at the eye's elevation, vertical position becomes elevation along the
meridian, and each triangle is oriented tangent to the surface.

Like the BlinkyH harnesses, the dome geometry is baked into the fixture,
centred at azimuth 0 (dome front, +Z); the full model yaws each eye into
place. DomeEyeR mirrors DomeEyeL across x=0 so the pair is mirror symmetric.

Wiring matches the harness convention: an "ip" parameter into
192.168.123.$ip, with the 16 triangles chained 4-per-port onto Art-Net
universes 0-3 (ports 1-4), 48 LEDs (144 channels) per port.
"""

from __future__ import annotations

import json
import math

from constants import BLINKY_TRIANGLE, DOME_EYE, FIXTURES_DIR, WATERFALL

DOME_RADIUS_M = WATERFALL["dome"]["radius_m"]
EDGE_M = DOME_EYE["triangle_edge_m"]
TRIANGLES_PER_ROW = DOME_EYE["triangles_per_row"]
ROWS = DOME_EYE["rows"]
ELEVATION_DEGREES = DOME_EYE["elevation_degrees"]

LEDS_PER_TRIANGLE = 12  # 3 strips x 4 LEDs, from BlinkyTriangle.lxf
TRIANGLES_PER_PORT = 4
PORTS = 4


def rounded(value: float) -> float:
    result = round(value, 6)
    return 0 if result == 0 else result


def flat_band() -> list[dict]:
    """Centroids of the flat interlocking band, centred on (0, 0).

    Row 0 runs u in [0, (n+1)/2 * edge]; row 1 sits above it shifted half an
    edge so its point-up triangles share the point-down bases of row 0.
    Returns dicts with u (horizontal), v (vertical), and point_up.
    """
    height_m = EDGE_M * math.sqrt(3.0) / 2.0
    triangles = []
    for row in range(ROWS):
        row_shift = 0 #row * EDGE_M / 2.0
        for i in range(TRIANGLES_PER_ROW):
            point_up = (i + row) % 2 == 0
            centroid_v = height_m / 3.0 if point_up else 2.0 * height_m / 3.0
            triangles.append(
                {
                    "u": row_shift + (i + 1) * EDGE_M / 2.0,
                    "v": row * height_m - centroid_v,
                    "point_up": point_up,
                }
            )

    # Centre the band: vertices span [0, (n+1+rows-1)/2 * edge] horizontally
    # and [0, rows * height] vertically.
    u_span = (TRIANGLES_PER_ROW + ROWS) * EDGE_M / 2.0
    v_span = ROWS * height_m
    for triangle in triangles:
        triangle["u"] -= u_span / 2.0
        triangle["v"] -= v_span / 2.0
    return triangles


def wrap_on_dome(triangle: dict, mirror: bool) -> dict:
    """Map a flat-band centroid onto the dome surface, tangent-oriented."""
    u = -triangle["u"] if mirror else triangle["u"]

    elevation_center = math.radians(ELEVATION_DEGREES)
    # Horizontal arc runs along the latitude circle at the eye's elevation;
    # vertical arc climbs the meridian.
    azimuth = u / (DOME_RADIUS_M * math.cos(elevation_center))
    elevation = elevation_center + triangle["v"] / DOME_RADIUS_M

    x = DOME_RADIUS_M * math.cos(elevation) * math.sin(azimuth)
    y = DOME_RADIUS_M * math.sin(elevation)
    z = DOME_RADIUS_M * math.cos(elevation) * math.cos(azimuth)

    return {
        "x": rounded(x),
        "y": rounded(y),
        "z": rounded(z),
        "yaw": rounded(math.degrees(azimuth)),
        "pitch": rounded(-math.degrees(elevation)),
        "rotate": 0 if triangle["point_up"] else 180,
    }


def build_fixture(label: str, mirror: bool) -> dict:
    components = []
    for index, triangle in enumerate(flat_band()):
        placement = wrap_on_dome(triangle, mirror)
        components.append(
            {
                "id": f"eye-tri-{index}",
                "type": "BlinkyTriangle",
                "hasCustomPointSize": True,
                "pointSize": BLINKY_TRIANGLE["point_size"],
                **placement,
            }
        )

    outputs = [
        {
            "protocol": "artnet",
            "enabled": True,
            "universe": port,
            "channel": 1,
            "host": "192.168.123.$ip",
            "start": port * TRIANGLES_PER_PORT * LEDS_PER_TRIANGLE,
            "num": TRIANGLES_PER_PORT * LEDS_PER_TRIANGLE,
        }
        for port in range(PORTS)
    ]

    height_m = EDGE_M * math.sqrt(3.0) / 2.0
    return {
        "label": label,
        "tag": "Eye",
        "parameters": {
            "ip": {
                "type": "int",
                "default": 13,
                "min": 11,
                "max": 15,
                "label": "Controller",
                "description": "IP of box should be 192.168.123.n",
            }
        },
        "metadata": {
            "coordinate_system": (
                "Y-up right-handed, origin at dome base centre, units metres; "
                "baked onto the dome surface centred at azimuth 0 (front, +Z)"
            ),
            "mirrored": mirror,
            "dome_radius_m": DOME_RADIUS_M,
            "elevation_degrees": ELEVATION_DEGREES,
            "rows": ROWS,
            "triangles_per_row": TRIANGLES_PER_ROW,
            "triangle_edge_m": EDGE_M,
            "triangle_point_size": BLINKY_TRIANGLE["point_size"],
            "band_width_m": round((TRIANGLES_PER_ROW + ROWS) * EDGE_M / 2.0, 6),
            "band_height_m": round(ROWS * height_m, 6),
        },
        "components": components,
        "outputs": outputs,
    }


def main() -> None:
    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    for side, mirror in (("left", False), ("right", True)):
        path = FIXTURES_DIR / DOME_EYE["output_files"][side]
        fixture = build_fixture(f"DomeEye{side[0].upper()}", mirror)
        path.write_text(json.dumps(fixture, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote {path}")


if __name__ == "__main__":
    main()
