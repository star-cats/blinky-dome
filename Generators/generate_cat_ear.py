#!/usr/bin/env python3
"""Generate the symmetric cat-ear fixture.

The ear is a chevron pointing up +Y, symmetric about the Y axis: two straight
2 m LED segments (one continuous 4 m strip folded at the apex). Each segment
leans spread_degrees / 2 from vertical, so the base tips sit at +/-X and the
apex sits on the Y axis. Origin is the ear base centre, between the two tips.

LED order follows the physical strip: up the -X leg from its base tip to the
apex, then down the +X leg from the apex to its base tip.
"""

from __future__ import annotations

import json
import math

from constants import CAT_EAR, FIXTURES_DIR


OUTPUT_PATH = FIXTURES_DIR / CAT_EAR["output_file"]

SEGMENT_LENGTH_M = CAT_EAR["geometry"]["segment_length_m"]
SPREAD_DEGREES = CAT_EAR["geometry"]["spread_degrees"]
LEDS_PER_STRIP = CAT_EAR["leds"]["leds_per_strip"]
LED_SPACING_M = CAT_EAR["leds"]["led_spacing_m"]


def rounded_coord(value: float) -> float:
    rounded = round(value, 6)
    return 0 if rounded == 0 else rounded


def make_segment(
    label: str,
    color: str,
    start: tuple[float, float],
    end: tuple[float, float],
) -> dict:
    """One straight LED segment from start to end in the XY plane."""
    direction_x = (end[0] - start[0]) / SEGMENT_LENGTH_M
    direction_y = (end[1] - start[1]) / SEGMENT_LENGTH_M

    coords = []
    for led_index in range(LEDS_PER_STRIP):
        distance_m = led_index * LED_SPACING_M
        coords.append(
            {
                "index": led_index,
                "x": rounded_coord(start[0] + direction_x * distance_m),
                "y": rounded_coord(start[1] + direction_y * distance_m),
                "z": 0,
            }
        )

    return {
        "type": "points",
        "label": label,
        "groupColor": color,
        "numPoints": LEDS_PER_STRIP,
        "metadata": {
            "physical_length_m": SEGMENT_LENGTH_M,
            "leds_per_strip": LEDS_PER_STRIP,
            "led_spacing_m": round(LED_SPACING_M, 9),
            "leds_per_cm": round(CAT_EAR["leds"]["leds_per_cm"], 9),
            "start_m": [rounded_coord(axis) for axis in start],
            "end_m": [rounded_coord(axis) for axis in end],
        },
        "coords": coords,
    }


def build_fixture() -> dict:
    half_spread = math.radians(SPREAD_DEGREES / 2.0)
    base_half_width_m = SEGMENT_LENGTH_M * math.sin(half_spread)
    apex_height_m = SEGMENT_LENGTH_M * math.cos(half_spread)

    left_tip = (-base_half_width_m, 0.0)
    right_tip = (base_half_width_m, 0.0)
    apex = (0.0, apex_height_m)

    components = [
        make_segment(
            "Rising (-X to apex)", CAT_EAR["colors"]["rising"], left_tip, apex
        ),
        make_segment(
            "Falling (apex to +X)", CAT_EAR["colors"]["falling"], apex, right_tip
        ),
    ]

    return {
        "label": "Cat Ear",
        "tags": ["cat-ear", "generated", "strips"],
        "metadata": {
            "coordinate_system": CAT_EAR["coordinate_system"],
            "segment_length_m": SEGMENT_LENGTH_M,
            "total_strip_length_m": SEGMENT_LENGTH_M * 2,
            "spread_degrees": SPREAD_DEGREES,
            "apex_height_m": round(apex_height_m, 6),
            "base_width_m": round(base_half_width_m * 2, 6),
            "strip_count": CAT_EAR["leds"]["strip_count"],
            "leds_per_strip": LEDS_PER_STRIP,
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
