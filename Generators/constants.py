"""Configurable constants for generated camp fixtures and layout."""

from __future__ import annotations

import math
import sys
from pathlib import Path


MIN_PYTHON = (3, 9)
FIXTURES_DIR = Path(__file__).resolve().parents[1] / "Fixtures"
MODELS_DIR = Path(__file__).resolve().parents[1] / "Models"

CM_PER_M = 100.0
FT_TO_M = 0.3048
IN_TO_M = 0.0254


# Shared preview size for BlinkyTriangle-based fixtures.
BLINKY_TRIANGLE = {
    "point_size": 2.5,
}


# Back-of-dome waterfall sheet.
WATERFALL = {
    "output_file": "Waterfall.lxf",
    "coordinate_system": (
        "Y-up right-handed, origin at dome base centre, units metres; "
        "the waterfall climbs the back (negative Z) surface of the dome"
    ),
    "dome": {
        "radius_m": 16.0 * FT_TO_M,
        "ground_y_m": 0.0,
    },
    "span": {
        "center_span_m": 10.0 * FT_TO_M,
        "wing_span_m": 4.0 * FT_TO_M,
    },
    "spine": {
        "lower_length_m": 12.0 * FT_TO_M,
        "upper_length_m": 8.0 * FT_TO_M,
    },
    "strips": {
        "count": 40,
        "nominal_spacing_m": 5.0 * IN_TO_M,
        "start_with": "long",
        "start_offset_m": 0.0,
        "long": {
            "length_m": 6.0,
            "leds_per_strip": 360,
            "color": "#4CC9F0",
        },
        "short": {
            "length_m": 5.25,
            "leds_per_strip": 315,
            "color": "#F72585",
        },
    },
}


# Star fixture sizing and model parameters.
STAR = {
    "geometry": {
        "diameter_ft": 4.2,
        "vertex_angles_degrees": [90.0, -54.0, 162.0, 18.0, -126.0],
        "segment_angles_degrees": [-72.0, 144.0, 0.0, 216.0, 72.0],
        "strip_length_ratio": 2.0,
    },
    "leds": {
        "leds_per_strip": 60,
        "overlap_leds": 0,
    },
}


# V3 dome scaffold mesh.
V3_DOME = {
    "radius_m": 3.5,
}


# Full camp model assembly.
MODEL = {
    "output_file": "full_camp_model_2026.lxm",
    "lx_version": "1.2.0",
    "scene_scale": 10.0,
    "stars": {
        "fixture": "Star",
        "host": "192.168.1.50",
        "elevation_degrees": 31.0,
        "azimuth_degrees": 0.0,
        "instances": [
            {"label": "Star 1", "roll_degrees": 142.0, "universe": 7},
            {"label": "Star 2", "roll_degrees": -167.0, "universe": 5},
            {"label": "Star 3", "roll_degrees": -182.0, "universe": 3},
            {"label": "Star 4", "roll_degrees": -197.0, "universe": 1},
        ],
    },
    "ears": {
        "tilt_degrees": 35.0,
    },
    "face": {
        "ear_base": {"x": 28.0, "y": 7.0, "z": -10.0},
        "eyes": {
            "azimuth_degrees": 24,
            "elevation_degrees": 23,
            "standoff": 0.0,
        },
        "trim": {
            "ears": {
                "x": 0.0,
                "y": 0.0,
                "z": 0.0,
                "yaw": 0.0,
                "pitch": 0.0,
                "roll": 0.0,
            },
        },
    },
    "v3_dome": {
        "placement": {
            "x_offset_m": 10.0,
            "y_offset_m": 0.0,
            "z_offset_m": 0.0,
            "yaw_degrees": 18.0,
            "pitch_degrees": 0.0,
            "roll_degrees": 0.0,
        },
        "harness": {
            "fixture_type": "V3DomeHarness1",
            "ips": [11, 12, 13, 14, 15],
        },
        "model": {
            "scale_trim": 1.0,
            "y_trim_m": 0.0,
            "yaw_phase_degrees": -18.0,
        },
    },
    "dome_model": {
        "scale": 0.01,
        "pitch_degrees": 90.0,
        "yaw_degrees": 18.0,
    },
}


def enforce_min_python() -> None:
    if sys.version_info < MIN_PYTHON:
        raise SystemExit(
            f"error: the fixture generators need Python "
            f"{MIN_PYTHON[0]}.{MIN_PYTHON[1]} or newer, but this is "
            f"{'.'.join(str(part) for part in sys.version_info[:3])}.\n"
            f"       interpreter: {sys.executable}\n"
            f"       pyenv reads the pin in .python-version at the repo root."
        )


def leds_per_cm(led_count: int, length_m: float) -> float:
    return led_count / (length_m * CM_PER_M)


def led_spacing_m(led_count: int, length_m: float) -> float:
    if led_count < 2:
        raise ValueError("led_count must be at least 2")
    return length_m / (led_count - 1)


def star_tip_ratio(shape: dict, length_ratio: float) -> float:
    """Distance to the furthest lit LED as a multiple of vertex-circle radius."""
    extent = 1.0
    for vertex_degrees, segment_degrees in zip(
        shape["vertex_angles_degrees"], shape["segment_angles_degrees"]
    ):
        vertex = math.radians(vertex_degrees)
        segment = math.radians(segment_degrees)
        extent = max(
            extent,
            math.hypot(
                math.cos(vertex) + math.cos(segment) * length_ratio,
                math.sin(vertex) + math.sin(segment) * length_ratio,
            ),
        )
    return extent


enforce_min_python()


# Derived waterfall values.
WATERFALL["span"]["total_span_m"] = (
    WATERFALL["span"]["center_span_m"] + WATERFALL["span"]["wing_span_m"] * 2
)
for profile in ("long", "short"):
    strip = WATERFALL["strips"][profile]
    strip["leds_per_cm"] = leds_per_cm(strip["leds_per_strip"], strip["length_m"])
    strip["led_spacing_m"] = led_spacing_m(strip["leds_per_strip"], strip["length_m"])


# Derived star values.
STAR["leds"]["reach_ratio"] = STAR["geometry"]["strip_length_ratio"] * (
    (STAR["leds"]["leds_per_strip"] - 1) / (STAR["leds"]["leds_per_strip"] + 1)
)
STAR["geometry"]["radius_m"] = STAR["geometry"]["diameter_ft"] * FT_TO_M / 2
STAR["geometry"]["outer_radius_m"] = STAR["geometry"]["radius_m"] / star_tip_ratio(
    STAR["geometry"], STAR["leds"]["reach_ratio"]
)
STAR["geometry"]["strip_length_m"] = (
    STAR["geometry"]["outer_radius_m"] * STAR["geometry"]["strip_length_ratio"]
)
STAR["leds"]["leds_per_cm"] = leds_per_cm(
    STAR["leds"]["leds_per_strip"], STAR["geometry"]["strip_length_m"]
)
STAR["leds"]["led_spacing_m"] = (
    STAR["geometry"]["strip_length_m"] / (STAR["leds"]["leds_per_strip"] + 1)
)
