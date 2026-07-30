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
    angles_degrees = STAR["geometry"]["vertex_angles_degrees"]
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
            "physical_length_m": round(STAR["geometry"]["strip_length_m"], 9),
            "leds_per_strip": led_count,
            "led_spacing_m": round(spacing_m, 9),
            "leds_per_cm": round(STAR["leds"]["leds_per_cm"], 9),
            "angle_degrees": angle_degrees,
        },
        "output": strip_output(index, led_count),
        "coords": coords,
    }


def strip_output(index: int, led_count: int) -> dict:
    """Art-Net output for one star segment, matching Star.lxf channel packing."""
    cfg = STAR["output"]
    byte_offset = index * 3 * led_count
    universe_add = byte_offset // cfg["universe_size_bytes"]
    channel = byte_offset - universe_add * cfg["universe_size_bytes"]
    universe = "$Universe" if universe_add == 0 else f"$Universe + {universe_add}"
    return {
        "host": "$ip",
        "byteOrder": cfg["byte_order"],
        "reverse": False,
        "protocol": cfg["protocol"],
        "universe": universe,
        "channel": channel,
        "sequenceEnabled": False,
    }


def build_fixture() -> dict:
    segment_angles_degrees = STAR["geometry"]["segment_angles_degrees"]
    components = [
        make_strip(index, start, angle)
        for index, (start, angle) in enumerate(
            zip(star_vertices(), segment_angles_degrees)
        )
    ]

    return {
        "label": "Generated Star",
        "tags": ["star", "generated", "2d"],
        "parameters": {
            "ip": {
                "label": "String A Host",
                "type": "string",
                "default": STAR["output"]["host_default"],
                "description": "Art-Net output host for this star",
            },
            "Universe": {
                "type": "int",
                "default": STAR["output"]["universe_default"],
                "label": "Universe",
                "description": "Base Art-Net universe (segments 4-5 use +1)",
            },
        },
        "metadata": {
            "coordinate_system": STAR["coordinate_system"],
            "diameter_ft": STAR["geometry"]["diameter_ft"],
            "diameter_m": round(STAR["geometry"]["radius_m"] * 2, 9),
            "outer_radius_m": round(STAR["geometry"]["outer_radius_m"], 9),
            "strip_count": STAR["leds"]["strip_count"],
            "strip_length_m": round(STAR["geometry"]["strip_length_m"], 9),
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
