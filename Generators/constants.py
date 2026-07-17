"""Shared generator constants.

All geometry constants in this file are metres unless the name explicitly says
otherwise. LED density is expressed as LEDs per centimetre for quick physical
sanity checks.
"""

from __future__ import annotations

from pathlib import Path


FIXTURES_DIR = Path(__file__).resolve().parents[1] / "Fixtures"

CM_PER_M = 100.0
FT_TO_M = 0.3048
IN_TO_M = 0.0254


def leds_per_cm(led_count: int, length_m: float) -> float:
    return led_count / (length_m * CM_PER_M)


def led_spacing_m(led_count: int, length_m: float) -> float:
    if led_count < 2:
        raise ValueError("led_count must be at least 2")
    return length_m / (led_count - 1)


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
    # Ground-up construction: a 10 ft straight centre span chords the back of
    # the dome's ground circle, with a 4 ft wing chord off each tip (endpoints
    # on the ground circle). The same 4/10/4 pattern repeats at each spine
    # level up the dome surface.
    "span": {
        "center_span_m": 10.0 * FT_TO_M,
        "wing_span_m": 4.0 * FT_TO_M,
    },
    # Chord lengths extending the 4 base points up the dome surface: base ->
    # lower-spine level -> upper-spine level (12 points total).
    "spine": {
        "lower_length_m": 11.0 * FT_TO_M,
        "upper_length_m": 8.0 * FT_TO_M,
    },
    "strips": {
        "count": 40,
        "nominal_spacing_m": 5.0 * IN_TO_M,
        "start_with": "long",
        # LEDs start at the top edge of the sheet; this shifts them downward
        # along each strip's polyline.
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
WATERFALL["span"]["total_span_m"] = (
    WATERFALL["span"]["center_span_m"] + WATERFALL["span"]["wing_span_m"] * 2
)
for profile in ("long", "short"):
    strip = WATERFALL["strips"][profile]
    strip["leds_per_cm"] = leds_per_cm(strip["leds_per_strip"], strip["length_m"])
    strip["led_spacing_m"] = led_spacing_m(strip["leds_per_strip"], strip["length_m"])


STAR = {
    "output_file": "StarEye.lxf",
    "coordinate_system": (
        "Y-up right-handed, origin at star centre, units metres"
    ),
    "geometry": {
        # Derived from Fixtures/Star.lxf: vertex coordinates lie on radius 1,
        # and each strip spans 2 fixture units.
        "outer_radius_m": 1.0,
        "strip_length_m": 2.0,
        "points": 5,
    },
    "leds": {
        # Derived from Projects/Blinkydome2026.lxp Star jsonParameters.
        "leds_per_strip": 60,
        "overlap_leds": 0,
        "strip_count": 5,
    },
}
STAR["leds"]["leds_per_cm"] = leds_per_cm(
    STAR["leds"]["leds_per_strip"], STAR["geometry"]["strip_length_m"]
)
# Derived from Fixtures/Star.lxf: "spacing": "2 / ($NumPoints + 1)".
STAR["leds"]["led_spacing_m"] = (
    STAR["geometry"]["strip_length_m"] / (STAR["leds"]["leds_per_strip"] + 1)
)
# Art-Net output, ported from Fixtures/Star.lxf so StarEye lights real pixels.
# Each universe holds 170 pixels (510 bytes); segments spill to $Universe + 1
# exactly as the original Star.lxf channel math did.
STAR["output"] = {
    "protocol": "artnet",
    "byte_order": "rgb",
    "host_default": "192.168.1.50",
    "universe_default": 1,
    "universe_size_bytes": 510,
}


CAT_EAR = {
    "output_file": "CatEar.lxf",
    "coordinate_system": (
        "Y-up right-handed, origin at single ear base, units metres"
    ),
    "leds": {
        # Derived from the Ear L A/B StripFixtures in Projects/Blinkydome2026.lxp.
        "leds_per_strip": 192,
        "strip_count": 2,
    },
    "strips": [
        {
            "label": "Ear A",
            "start_m": (0.0, 0.0, 0.0),
            "angle_degrees": 42.0,
            "spacing_m": 0.01,
            "color": "#FFB703",
        },
        {
            "label": "Ear B",
            "start_m": (2.8, -1.4, 0.0),
            "angle_degrees": 90.0,
            "spacing_m": 0.02,
            "color": "#FB8500",
        },
    ],
}
for strip in CAT_EAR["strips"]:
    strip["length_m"] = strip["spacing_m"] * (
        CAT_EAR["leds"]["leds_per_strip"] - 1
    )
    strip["leds_per_cm"] = leds_per_cm(
        CAT_EAR["leds"]["leds_per_strip"], strip["length_m"]
    )
