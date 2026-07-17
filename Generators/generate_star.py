#!/usr/bin/env python3
"""Generate a metre-based explicit point fixture for the five-point star."""

from __future__ import annotations

import json
import math

from constants import FIXTURES_DIR, STAR


OUTPUT_PATH = FIXTURES_DIR / STAR["output_file"]


def rounded_coord(value: float) -> float:
    rounded = round(value, 6)
    return 0 if rounded == 0 else rounded


def star_vertices() -> list[tuple[float, float, float]]:
    radius_m = STAR["geometry"]["outer_radius_m"]
    # Match the existing Star.lxf vertex order: top, lower-right, upper-left,
    # upper-right, lower-left.
    angles_degrees = [90, -54, 162, 18, -126]
    return [
        (
            radius_m * math.cos(math.radians(angle)),
            radius_m * math.sin(math.radians(angle)),
            0.0,
        )
        for angle in angles_degrees
    ]


def make_strip(
    index: int, start: tuple[float, float, float], angle_degrees: float
) -> dict:
    led_count = STAR["leds"]["leds_per_strip"]
    spacing_m = STAR["leds"]["led_spacing_m"]
    direction = (
        math.cos(math.radians(angle_degrees)),
        math.sin(math.radians(angle_degrees)),
        0.0,
    )

    coords = []
    for led_index in range(led_count):
        distance_m = led_index * spacing_m
        coords.append(
            {
                "index": led_index,
                "x": rounded_coord(start[0] + direction[0] * distance_m),
                "y": rounded_coord(start[1] + direction[1] * distance_m),
                "z": rounded_coord(start[2] + direction[2] * distance_m),
            }
        )

    return {
        "type": "points",
        "label": f"Star Segment {index + 1}",
        "numPoints": led_count,
        "metadata": {
            "physical_length_m": STAR["geometry"]["strip_length_m"],
            "leds_per_strip": led_count,
            "led_spacing_m": round(spacing_m, 9),
            "leds_per_cm": round(STAR["leds"]["leds_per_cm"], 9),
            "angle_degrees": angle_degrees,
        },
        "coords": coords,
    }


def build_fixture() -> dict:
    # Roll values are derived from the existing Fixtures/Star.lxf strip rolls.
    segment_angles_degrees = [-72.0, 144.0, 0.0, 216.0, 72.0]
    components = [
        make_strip(index, start, angle)
        for index, (start, angle) in enumerate(
            zip(star_vertices(), segment_angles_degrees)
        )
    ]

    return {
        "label": "Generated Star",
        "tags": ["star", "generated", "2d"],
        "metadata": {
            "coordinate_system": STAR["coordinate_system"],
            "outer_radius_m": STAR["geometry"]["outer_radius_m"],
            "strip_count": STAR["leds"]["strip_count"],
            "strip_length_m": STAR["geometry"]["strip_length_m"],
            "leds_per_strip": STAR["leds"]["leds_per_strip"],
            "overlap_leds": STAR["leds"]["overlap_leds"],
            "leds_per_cm": round(STAR["leds"]["leds_per_cm"], 9),
        },
        "components": components,
    }


def main() -> None:
    fixture = build_fixture()
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(fixture, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
