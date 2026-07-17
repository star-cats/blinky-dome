#!/usr/bin/env python3
"""
Generate the Waterfall fixture.

Coordinates are metres in a Y-up right-handed system. The dome base centre is
the origin, Y is height, X is the left/right span, and negative Z is back.
"""

from __future__ import annotations

import json
import math
from pathlib import Path


FT_TO_M = 0.3048
IN_TO_M = 0.0254

# Output
OUTPUT_PATH = Path(__file__).resolve().parents[1] / "Fixtures" / "Waterfall.lxf"

# Dome and global coordinate parameters.
DOME_RADIUS_FT = 16.0
DOME_TOP_Y_FT = DOME_RADIUS_FT
DOME_BACK_Z_FT = -DOME_RADIUS_FT
DOME_GROUND_Y_FT = 0.0

# Waterfall span parameters. The fixture spans 18 ft total: a 10 ft centre
# section plus two 4 ft wings, folded down around the centre/wing creases.
CENTER_SPAN_FT = 10.0
WING_SPAN_FT = 4.0
TOTAL_SPAN_FT = CENTER_SPAN_FT + WING_SPAN_FT * 2
WING_FOLD_DEGREES = 35.0

# Strand parameters.
NUM_STRIPS = 40
NOMINAL_STRIP_SPACING_IN = 5.0
LONG_STRIP_LENGTH_M = 6.0
SHORT_STRIP_LENGTH_M = 5.25
LONG_STRIP_LEDS = 360
SHORT_STRIP_LEDS = 315
START_WITH_LONG_STRIP = True

# Distance from the top-centre of the dome toward the back of the dome before
# the strands redirect toward the bottom-back ground line.
PIVOT_CHORD_LENGTH_FT = 12.0

# If true, each strand redirects toward a bottom-back point with the same X as
# its folded top anchor. If false, all strands converge toward X=0 at the back.
PRESERVE_X_TO_BACK = True

# Presentation colours alternate with the physical strip lengths.
LONG_STRIP_COLOR = "#4CC9F0"
SHORT_STRIP_COLOR = "#F72585"


def ft(value: float) -> float:
    return value * FT_TO_M


def inches(value: float) -> float:
    return value * IN_TO_M


def point_ft(x: float, y: float, z: float) -> tuple[float, float, float]:
    return (ft(x), ft(y), ft(z))


def normalize(vector: tuple[float, float, float]) -> tuple[float, float, float]:
    length = math.sqrt(sum(axis * axis for axis in vector))
    if length == 0:
        raise ValueError("Cannot normalize a zero-length vector")
    return tuple(axis / length for axis in vector)


def add(
    a: tuple[float, float, float], b: tuple[float, float, float]
) -> tuple[float, float, float]:
    return tuple(a_axis + b_axis for a_axis, b_axis in zip(a, b))


def subtract(
    a: tuple[float, float, float], b: tuple[float, float, float]
) -> tuple[float, float, float]:
    return tuple(a_axis - b_axis for a_axis, b_axis in zip(a, b))


def scale(
    vector: tuple[float, float, float], scalar: float
) -> tuple[float, float, float]:
    return tuple(axis * scalar for axis in vector)


def distance(a: tuple[float, float, float], b: tuple[float, float, float]) -> float:
    return math.sqrt(sum((a_axis - b_axis) ** 2 for a_axis, b_axis in zip(a, b)))


def rounded_coord(value: float) -> float:
    rounded = round(value, 6)
    return 0 if rounded == 0 else rounded


def folded_anchor_x_y(x_flat_ft: float) -> tuple[float, float]:
    """Return the folded X/Y anchor for a point on the flat 18 ft span."""
    half_center_ft = CENTER_SPAN_FT / 2.0
    fold_radians = math.radians(WING_FOLD_DEGREES)

    if x_flat_ft < -half_center_ft:
        outward_ft = -half_center_ft - x_flat_ft
        x_ft = -half_center_ft - outward_ft * math.cos(fold_radians)
        y_ft = DOME_TOP_Y_FT - outward_ft * math.sin(fold_radians)
    elif x_flat_ft > half_center_ft:
        outward_ft = x_flat_ft - half_center_ft
        x_ft = half_center_ft + outward_ft * math.cos(fold_radians)
        y_ft = DOME_TOP_Y_FT - outward_ft * math.sin(fold_radians)
    else:
        x_ft = x_flat_ft
        y_ft = DOME_TOP_Y_FT

    return x_ft, y_ft


def strip_profile(index: int) -> tuple[str, float, int, str]:
    long_strip = index % 2 == 0 if START_WITH_LONG_STRIP else index % 2 == 1
    if long_strip:
        return "long", LONG_STRIP_LENGTH_M, LONG_STRIP_LEDS, LONG_STRIP_COLOR
    return "short", SHORT_STRIP_LENGTH_M, SHORT_STRIP_LEDS, SHORT_STRIP_COLOR


def interpolate_on_path(
    start: tuple[float, float, float],
    pivot: tuple[float, float, float],
    back_target: tuple[float, float, float],
    distance_along_path: float,
) -> tuple[float, float, float]:
    first_segment_length = distance(start, pivot)

    if distance_along_path <= first_segment_length:
        direction = normalize(subtract(pivot, start))
        return add(start, scale(direction, distance_along_path))

    second_distance = distance_along_path - first_segment_length
    second_direction = normalize(subtract(back_target, pivot))
    return add(pivot, scale(second_direction, second_distance))


def make_strip(index: int) -> dict:
    if NUM_STRIPS < 2:
        raise ValueError("NUM_STRIPS must be at least 2")

    x_flat_ft = -TOTAL_SPAN_FT / 2.0 + (TOTAL_SPAN_FT * index / (NUM_STRIPS - 1))
    x_anchor_ft, y_anchor_ft = folded_anchor_x_y(x_flat_ft)

    start = point_ft(x_anchor_ft, y_anchor_ft, 0.0)

    chord_end = point_ft(x_anchor_ft, DOME_GROUND_Y_FT, DOME_BACK_Z_FT)
    chord_direction = normalize(subtract(chord_end, start))
    pivot = add(start, scale(chord_direction, ft(PIVOT_CHORD_LENGTH_FT)))

    back_x_ft = x_anchor_ft if PRESERVE_X_TO_BACK else 0.0
    back_target = point_ft(back_x_ft, DOME_GROUND_Y_FT, DOME_BACK_Z_FT)

    length_label, strip_length_m, led_count, color = strip_profile(index)
    spacing_m = strip_length_m / (led_count - 1)

    coords = []
    for led_index in range(led_count):
        x, y, z = interpolate_on_path(
            start, pivot, back_target, led_index * spacing_m
        )
        coords.append(
            {
                "index": led_index,
                "x": rounded_coord(x),
                "y": rounded_coord(y),
                "z": rounded_coord(z),
            }
        )

    return {
        "type": "points",
        "label": (
            f"Waterfall Strip {index + 1:02d} "
            f"({length_label}, {strip_length_m:g}m, {led_count} LEDs)"
        ),
        "groupColor": color,
        "numPoints": led_count,
        "metadata": {
            "flat_x_ft": round(x_flat_ft, 6),
            "folded_anchor_x_ft": round(x_anchor_ft, 6),
            "folded_anchor_y_ft": round(y_anchor_ft, 6),
            "physical_length_m": strip_length_m,
            "led_spacing_m": round(spacing_m, 9),
        },
        "coords": coords,
    }


def build_fixture() -> dict:
    if abs(TOTAL_SPAN_FT - (CENTER_SPAN_FT + 2 * WING_SPAN_FT)) > 1e-9:
        raise ValueError("TOTAL_SPAN_FT must equal CENTER_SPAN_FT + 2 * WING_SPAN_FT")

    strips = [make_strip(index) for index in range(NUM_STRIPS)]
    long_count = sum(1 for index in range(NUM_STRIPS) if strip_profile(index)[0] == "long")
    short_count = NUM_STRIPS - long_count

    return {
        "label": "Waterfall",
        "tags": ["waterfall", "dome", "strips"],
        "metadata": {
            "coordinate_system": (
                "Y-up right-handed, origin at dome base centre, units metres"
            ),
            "dome_radius_ft": DOME_RADIUS_FT,
            "total_span_ft": TOTAL_SPAN_FT,
            "center_span_ft": CENTER_SPAN_FT,
            "wing_span_ft": WING_SPAN_FT,
            "wing_fold_degrees": WING_FOLD_DEGREES,
            "num_strips": NUM_STRIPS,
            "long_strips": long_count,
            "short_strips": short_count,
            "long_strip_length_m": LONG_STRIP_LENGTH_M,
            "short_strip_length_m": SHORT_STRIP_LENGTH_M,
            "long_strip_leds": LONG_STRIP_LEDS,
            "short_strip_leds": SHORT_STRIP_LEDS,
            "nominal_strip_spacing_in": NOMINAL_STRIP_SPACING_IN,
            "actual_flat_strip_spacing_in": round(
                (TOTAL_SPAN_FT * 12.0) / (NUM_STRIPS - 1), 6
            ),
            "nominal_strip_spacing_m": round(inches(NOMINAL_STRIP_SPACING_IN), 9),
            "pivot_chord_length_ft": PIVOT_CHORD_LENGTH_FT,
            "preserve_x_to_back": PRESERVE_X_TO_BACK,
        },
        "components": strips,
    }


def main() -> None:
    fixture = build_fixture()
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(fixture, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
