#!/usr/bin/env python3
"""Generate a single cat-ear fixture made from two linear LED strips."""

from __future__ import annotations

import json
import math

from constants import CAT_EAR, FIXTURES_DIR


OUTPUT_PATH = FIXTURES_DIR / CAT_EAR["output_file"]


def rounded_coord(value: float) -> float:
    rounded = round(value, 6)
    return 0 if rounded == 0 else rounded


def make_strip(index: int, strip: dict) -> dict:
    led_count = CAT_EAR["leds"]["leds_per_strip"]
    spacing_m = strip["spacing_m"]
    angle_degrees = strip["angle_degrees"]
    direction = (
        math.cos(math.radians(angle_degrees)),
        math.sin(math.radians(angle_degrees)),
        0.0,
    )
    start = strip["start_m"]

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
        "label": strip["label"],
        "groupColor": strip["color"],
        "numPoints": led_count,
        "metadata": {
            "physical_length_m": round(strip["length_m"], 9),
            "leds_per_strip": led_count,
            "led_spacing_m": round(spacing_m, 9),
            "leds_per_cm": round(strip["leds_per_cm"], 9),
            "angle_degrees": angle_degrees,
            "start_m": [round(value, 9) for value in start],
        },
        "coords": coords,
    }


def build_fixture() -> dict:
    components = [
        make_strip(index, strip) for index, strip in enumerate(CAT_EAR["strips"])
    ]

    return {
        "label": "Cat Ear",
        "tags": ["cat-ear", "generated", "strips"],
        "metadata": {
            "coordinate_system": CAT_EAR["coordinate_system"],
            "strip_count": CAT_EAR["leds"]["strip_count"],
            "leds_per_strip": CAT_EAR["leds"]["leds_per_strip"],
            "derived_from": "Projects/Blinkydome2026.lxp Ear L A/B StripFixtures",
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
