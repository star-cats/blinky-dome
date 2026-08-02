#!/usr/bin/env python3
"""Generate the KylesCurtain fixture."""

from __future__ import annotations

import json
import re

from constants import FIXTURES_DIR, KYLES_CURTAIN


OUTPUT_PATH = FIXTURES_DIR / KYLES_CURTAIN["output_file"]

GRID_WIDTH = KYLES_CURTAIN["assembly"]["pixel_width"]
GRID_HEIGHT = KYLES_CURTAIN["assembly"]["pixel_height"]
WIDTH_M = KYLES_CURTAIN["assembly"]["width_m"]
HEIGHT_M = KYLES_CURTAIN["assembly"]["height_m"]
PITCH_M = KYLES_CURTAIN["assembly"]["pixel_pitch_m"]
SERPENTINE = KYLES_CURTAIN["assembly"]["serpentine"]
START_CORNER = KYLES_CURTAIN["assembly"]["start_corner"]

PROTOCOL = KYLES_CURTAIN["output"]["protocol"]
BYTE_ORDER = KYLES_CURTAIN["output"]["byte_order"]
MAX_PIXELS_PER_UNIVERSE = KYLES_CURTAIN["output"]["max_pixels_per_universe"]


def parameter_key(label: str, prefix: str) -> str:
    suffix = re.sub(r"[^A-Za-z0-9]+", "", label)
    return f"{prefix}{suffix}"


def rounded_coord(value: float) -> float:
    rounded = round(value, 6)
    return 0.0 if rounded == 0 else rounded


def pixel_coord(col: int, row: int) -> dict:
    return {
        "x": rounded_coord((col + 0.5) * PITCH_M - WIDTH_M / 2.0),
        "y": rounded_coord(HEIGHT_M / 2.0 - (row + 0.5) * PITCH_M),
        "z": 0.0,
    }


def panel_pixels(panel: dict) -> list[tuple[int, int]]:
    pixels = []
    for local_row in range(panel["pixel_height"]):
        local_cols = range(panel["pixel_width"])
        if SERPENTINE and local_row % 2 == 1:
            local_cols = reversed(range(panel["pixel_width"]))
        for local_col in local_cols:
            if START_CORNER != "top_left":
                raise ValueError(f"Unsupported start_corner: {START_CORNER}")
            pixels.append(
                (
                    panel["origin_col"] + local_col,
                    panel["origin_row"] + local_row,
                )
            )
    return pixels


def universe_expr(universe_key: str, universe_offset: int) -> str:
    if universe_offset == 0:
        return f"${universe_key}"
    return f"${universe_key} + {universe_offset}"


def make_segment(
    panel: dict,
    segment_index: int,
    pixels: list[tuple[int, int]],
) -> dict:
    ip_key = parameter_key(panel["label"], "ip")
    universe_key = parameter_key(panel["label"], "universe")
    coords = []
    for index, (col, row) in enumerate(pixels):
        coords.append({"index": index, **pixel_coord(col, row)})

    return {
        "type": "points",
        "label": f"KylesCurtain {panel['label']} U{segment_index + 1:02d}",
        "tag": "kyles-curtain",
        "numPoints": len(coords),
        "metadata": {
            "panel": panel["label"],
            "segment_index": segment_index,
            "rotation_degrees": panel["rotation_degrees"],
            "origin_col": panel["origin_col"],
            "origin_row": panel["origin_row"],
        },
        "coords": coords,
        "output": {
            "protocol": PROTOCOL,
            "enabled": True,
            "host": f"${ip_key}",
            "byteOrder": BYTE_ORDER,
            "universe": universe_expr(universe_key, segment_index),
            "channel": 0,
            "sequenceEnabled": False,
        },
    }


def panel_components(panel: dict) -> list[dict]:
    pixels = panel_pixels(panel)
    return [
        make_segment(panel, index, pixels[start : start + MAX_PIXELS_PER_UNIVERSE])
        for index, start in enumerate(range(0, len(pixels), MAX_PIXELS_PER_UNIVERSE))
    ]


def validate_panels() -> None:
    expected_panel_pixels = (
        KYLES_CURTAIN["panel"]["pixel_width"] * KYLES_CURTAIN["panel"]["pixel_height"]
    )
    occupied = set()
    for panel in KYLES_CURTAIN["output"]["panels"]:
        pixel_count = panel["pixel_width"] * panel["pixel_height"]
        if pixel_count != expected_panel_pixels:
            raise ValueError(
                f"{panel['label']} has {pixel_count} pixels, expected "
                f"{expected_panel_pixels}"
            )
        for pixel in panel_pixels(panel):
            col, row = pixel
            if not (0 <= col < GRID_WIDTH and 0 <= row < GRID_HEIGHT):
                raise ValueError(f"{panel['label']} pixel outside assembled grid: {pixel}")
            if pixel in occupied:
                raise ValueError(f"Duplicate assembled-grid pixel: {pixel}")
            occupied.add(pixel)

    expected_total = GRID_WIDTH * GRID_HEIGHT
    if len(occupied) != expected_total:
        raise ValueError(f"Curtain covers {len(occupied)} pixels, expected {expected_total}")


def build_parameters() -> dict:
    parameters = {}
    for panel in KYLES_CURTAIN["output"]["panels"]:
        ip_key = parameter_key(panel["label"], "ip")
        universe_key = parameter_key(panel["label"], "universe")
        parameters[ip_key] = {
            "type": "string",
            "default": panel["ip_default"],
            "label": f"{panel['label']} IP",
            "description": f"Art-Net host for the {panel['label']} curtain panel",
        }
        parameters[universe_key] = {
            "type": "int",
            "default": panel["universe_default"],
            "min": 0,
            "max": 32767,
            "label": f"{panel['label']} Base Universe",
            "description": f"First Art-Net universe for the {panel['label']} panel",
        }
    return parameters


def build_fixture() -> dict:
    validate_panels()
    components = [
        component
        for panel in KYLES_CURTAIN["output"]["panels"]
        for component in panel_components(panel)
    ]
    return {
        "label": "KylesCurtain",
        "tags": ["kyles-curtain", "grid", "curtain"],
        "parameters": build_parameters(),
        "metadata": {
            "coordinate_system": KYLES_CURTAIN["coordinate_system"],
            "panel_pixel_width": KYLES_CURTAIN["panel"]["pixel_width"],
            "panel_pixel_height": KYLES_CURTAIN["panel"]["pixel_height"],
            "panel_width_m": KYLES_CURTAIN["panel"]["width_m"],
            "panel_height_m": KYLES_CURTAIN["panel"]["height_m"],
            "assembly_pixel_width": GRID_WIDTH,
            "assembly_pixel_height": GRID_HEIGHT,
            "assembly_width_m": WIDTH_M,
            "assembly_height_m": HEIGHT_M,
            "pixel_pitch_m": PITCH_M,
            "serpentine": SERPENTINE,
            "max_pixels_per_universe": MAX_PIXELS_PER_UNIVERSE,
        },
        "components": components,
    }


def main() -> None:
    fixture = build_fixture()
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(fixture, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {OUTPUT_PATH} ({len(fixture['components'])} output segments)")


if __name__ == "__main__":
    main()
