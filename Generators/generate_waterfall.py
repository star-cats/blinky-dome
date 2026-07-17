#!/usr/bin/env python3
"""
Generate the Waterfall fixture.

Coordinates are metres in a Y-up right-handed system. The dome base centre is
the origin, Y is height, X is the left/right span, and negative Z is back.
"""

from __future__ import annotations

import json
import math

from constants import FIXTURES_DIR, WATERFALL

# Output
OUTPUT_PATH = FIXTURES_DIR / WATERFALL["output_file"]

# Dome and global coordinate parameters.
DOME_RADIUS_M = WATERFALL["dome"]["radius_m"]
DOME_TOP_Y_M = WATERFALL["dome"]["top_y_m"]
DOME_BACK_Z_M = WATERFALL["dome"]["back_z_m"]
DOME_GROUND_Y_M = WATERFALL["dome"]["ground_y_m"]

# Waterfall span parameters. The fixture spans 18 ft total: a 10 ft centre
# section plus two 4 ft wings, folded down around the centre/wing creases.
CENTER_SPAN_M = WATERFALL["span"]["center_span_m"]
WING_SPAN_M = WATERFALL["span"]["wing_span_m"]
TOTAL_SPAN_M = WATERFALL["span"]["total_span_m"]
WING_FOLD_DEGREES = WATERFALL["span"]["wing_fold_degrees"]

# Strand parameters.
NUM_STRIPS = WATERFALL["strips"]["count"]
NOMINAL_STRIP_SPACING_M = WATERFALL["strips"]["nominal_spacing_m"]
LONG_STRIP_LENGTH_M = WATERFALL["strips"]["long"]["length_m"]
SHORT_STRIP_LENGTH_M = WATERFALL["strips"]["short"]["length_m"]
LONG_STRIP_LEDS = WATERFALL["strips"]["long"]["leds_per_strip"]
SHORT_STRIP_LEDS = WATERFALL["strips"]["short"]["leds_per_strip"]
START_WITH_LONG_STRIP = WATERFALL["strips"]["start_with"] == "long"

# Distance from the top-centre of the dome toward the back of the dome before
# the strands redirect toward the bottom-back ground line.
PIVOT_CHORD_LENGTH_M = WATERFALL["pivot"]["chord_length_m"]

# If true, each strand redirects toward a bottom-back point with the same X as
# its folded top anchor. If false, all strands converge toward X=0 at the back.
PRESERVE_X_TO_BACK = WATERFALL["pivot"]["preserve_x_to_back"]

# Presentation colours alternate with the physical strip lengths.
LONG_STRIP_COLOR = WATERFALL["strips"]["long"]["color"]
SHORT_STRIP_COLOR = WATERFALL["strips"]["short"]["color"]


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


def folded_anchor_x_y(x_flat_m: float) -> tuple[float, float]:
    """Return the folded X/Y anchor for a point on the flat span."""
    half_center_m = CENTER_SPAN_M / 2.0
    fold_radians = math.radians(WING_FOLD_DEGREES)

    if x_flat_m < -half_center_m:
        outward_m = -half_center_m - x_flat_m
        x_m = -half_center_m - outward_m * math.cos(fold_radians)
        y_m = DOME_TOP_Y_M - outward_m * math.sin(fold_radians)
    elif x_flat_m > half_center_m:
        outward_m = x_flat_m - half_center_m
        x_m = half_center_m + outward_m * math.cos(fold_radians)
        y_m = DOME_TOP_Y_M - outward_m * math.sin(fold_radians)
    else:
        x_m = x_flat_m
        y_m = DOME_TOP_Y_M

    return x_m, y_m


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

    x_flat_m = -TOTAL_SPAN_M / 2.0 + (TOTAL_SPAN_M * index / (NUM_STRIPS - 1))
    x_anchor_m, y_anchor_m = folded_anchor_x_y(x_flat_m)

    start = (x_anchor_m, y_anchor_m, 0.0)

    chord_end = (x_anchor_m, DOME_GROUND_Y_M, DOME_BACK_Z_M)
    chord_direction = normalize(subtract(chord_end, start))
    pivot = add(start, scale(chord_direction, PIVOT_CHORD_LENGTH_M))

    back_x_m = x_anchor_m if PRESERVE_X_TO_BACK else 0.0
    back_target = (back_x_m, DOME_GROUND_Y_M, DOME_BACK_Z_M)

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
            "flat_x_m": round(x_flat_m, 6),
            "folded_anchor_x_m": round(x_anchor_m, 6),
            "folded_anchor_y_m": round(y_anchor_m, 6),
            "physical_length_m": strip_length_m,
            "led_spacing_m": round(spacing_m, 9),
            "leds_per_cm": round(strip_length_m and led_count / (strip_length_m * 100), 9),
        },
        "coords": coords,
    }


def build_fixture() -> dict:
    if abs(TOTAL_SPAN_M - (CENTER_SPAN_M + 2 * WING_SPAN_M)) > 1e-9:
        raise ValueError("TOTAL_SPAN_M must equal CENTER_SPAN_M + 2 * WING_SPAN_M")

    strips = [make_strip(index) for index in range(NUM_STRIPS)]
    long_count = sum(1 for index in range(NUM_STRIPS) if strip_profile(index)[0] == "long")
    short_count = NUM_STRIPS - long_count

    return {
        "label": "Waterfall",
        "tags": ["waterfall", "dome", "strips"],
        "metadata": {
            "coordinate_system": WATERFALL["coordinate_system"],
            "dome_radius_m": DOME_RADIUS_M,
            "total_span_m": TOTAL_SPAN_M,
            "center_span_m": CENTER_SPAN_M,
            "wing_span_m": WING_SPAN_M,
            "wing_fold_degrees": WING_FOLD_DEGREES,
            "num_strips": NUM_STRIPS,
            "long_strips": long_count,
            "short_strips": short_count,
            "long_strip_length_m": LONG_STRIP_LENGTH_M,
            "short_strip_length_m": SHORT_STRIP_LENGTH_M,
            "long_strip_leds": LONG_STRIP_LEDS,
            "short_strip_leds": SHORT_STRIP_LEDS,
            "long_strip_leds_per_cm": round(WATERFALL["strips"]["long"]["leds_per_cm"], 9),
            "short_strip_leds_per_cm": round(WATERFALL["strips"]["short"]["leds_per_cm"], 9),
            "actual_flat_strip_spacing_m": round(TOTAL_SPAN_M / (NUM_STRIPS - 1), 9),
            "nominal_strip_spacing_m": round(NOMINAL_STRIP_SPACING_M, 9),
            "pivot_chord_length_m": PIVOT_CHORD_LENGTH_M,
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
