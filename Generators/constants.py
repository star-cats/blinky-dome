"""Shared generator constants.

All geometry constants in this file are metres unless the name explicitly says
otherwise. LED density is expressed as LEDs per centimetre for quick physical
sanity checks.
"""

from __future__ import annotations

from pathlib import Path


FIXTURES_DIR = Path(__file__).resolve().parents[1] / "Fixtures"
MODELS_DIR = Path(__file__).resolve().parents[1] / "Models"

CM_PER_M = 100.0
FT_TO_M = 0.3048
IN_TO_M = 0.0254


BLINKY_TRIANGLE = {
    # Chromatik preview point size for every BlinkyTriangle component, used by
    # both the dome-cover harness fixtures and the generated dome-eye fixtures.
    "point_size": 2.5,
}


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
        "lower_length_m": 12.0 * FT_TO_M,
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
        "Y-up right-handed, origin at ear base centre; the ear points up +Y "
        "and is symmetric about the Y axis, units metres"
    ),
    # A symmetric chevron: two straight 2 m LED segments rise from the base
    # (one continuous 4 m strip folded at the apex on the Y axis), each leaning
    # spread_degrees / 2 from vertical.
    "geometry": {
        "segment_length_m": 2.0,
        "spread_degrees": 45.0,
    },
    "leds": {
        "leds_per_strip": 192,
        "strip_count": 2,
    },
    "colors": {
        "rising": "#FFB703",
        "falling": "#FB8500",
    },
}
CAT_EAR["leds"]["led_spacing_m"] = led_spacing_m(
    CAT_EAR["leds"]["leds_per_strip"], CAT_EAR["geometry"]["segment_length_m"]
)
CAT_EAR["leds"]["leds_per_cm"] = leds_per_cm(
    CAT_EAR["leds"]["leds_per_strip"], CAT_EAR["geometry"]["segment_length_m"]
)


# Dome eyes (generate_dome_eye.py): the triangle patches freed by removing the
# Plana/Undae harnesses, rebuilt as one eye each on the front of the dome. An
# eye is two interlocking rows of 8 BlinkyTriangles following the dome's arc,
# baked onto the dome surface centred at azimuth 0 (like the harness fixtures);
# the model yaws each eye into place. DomeEyeR is the exact x-mirror of
# DomeEyeL so the pair is mirror symmetric.
DOME_EYE = {
    "output_files": {
        "left": "DomeEyeL.lxf",
        "right": "DomeEyeR.lxf",
    },
    # Matches the scale transform inside BlinkyTriangle.lxf.
    "triangle_edge_m": 0.7, #0.5842,
    "triangles_per_row": 8,
    "rows": 2,
    # Height of the eye centre above the horizon, on the dome surface.
    "elevation_degrees": 22.5,
}


# Full-model assembly (generate_full_model.py). Positions are in scene units:
# fixture geometry is metres, and every instance is placed at scene_scale, so
# scene units = metres * scene_scale (matching the existing project files).
MODEL = {
    "output_file": "full_camp_model_2026.lxm",
    "lx_version": "1.2.0",
    "scene_scale": 10.0,
    # Dome-surface harness arcs: identical groups of BlinkyH0-H3 yawed around
    # the dome, one controller IP per group, one port per harness. Plana and
    # Undae were removed from the back of the dome; their triangle patches and
    # controllers (ips 13/14) are repurposed as the dome eyes below.
    "harness_groups": [
        {"name": "Astra", "yaw_degrees": 0.0, "ip": 11},
        {"name": "Dorsa", "yaw_degrees": 72.0, "ip": 12},
        {"name": "Tholi", "yaw_degrees": 288.0, "ip": 15},
    ],
    # Two mirror-symmetric dome eyes on the front, yawed apart so their
    # centres sit eye_distance_m apart measured along the dome's arc at the
    # eyes' elevation. Eye L is on the cat's left (+X, like Ear L).
    "eyes": {
        "eye_distance_m": 5,
        "instances": [
            {"label": "Eye L", "fixture": "blinky-dome/DomeEyeL", "ip": 13, "side": 1},
            {"label": "Eye R", "fixture": "blinky-dome/DomeEyeR", "ip": 14, "side": -1},
        ],
    },
    # The star grouping is four co-located StarEyes rolled ~15 degrees apart
    # (a stacked pinwheel), placed tangent to the dome surface on the front
    # (+Z) at elevation_degrees above the horizon.
    "stars": {
        "host": "192.168.1.50",
        "elevation_degrees": 35.0,
        "azimuth_degrees": 0.0,
        "instances": [
            {"label": "Star 1", "roll_degrees": 142.0, "universe": 7},
            {"label": "Star 2", "roll_degrees": -167.0, "universe": 5},
            {"label": "Star 3", "roll_degrees": -182.0, "universe": 3},
            {"label": "Star 4", "roll_degrees": -197.0, "universe": 1},
        ],
    },
    # Cat ears sit atop the dome, spread left/right (+/-X, cat facing +Z).
    # tilt_degrees leans each ear outward from vertical and slides its base
    # down the dome surface by the same angle.
    "ears": {
        "tilt_degrees": 35.0,
    },
    # The V3 dome is a whole second dome, not part of the cat, so it is parked
    # off to the side on +X far enough that its own 3.5 m radius clears the
    # ears and waterfall. Its LED harness (V3DomeHarness.lxf) and its scaffold
    # mesh (v3_dome_model.lxf) are one physical structure, so they share
    # "placement" and move together; only the mesh's cosmetic knobs differ.
    "v3_dome": {
        "placement": {
            "x_offset_m": 9.0,
            "y_offset_m": 0.0,
            "z_offset_m": 0.0,
            "yaw_degrees": 0.0,
            "pitch_degrees": 0.0,
            "roll_degrees": 0.0,
        },
        # ips are the last octet of each junction box on 192.168.123.x, J1-J5.
        "harness": {
            "ips": [11, 12, 13, 14, 15],
        },
        # v3_dome_model.obj is the same 3V 5/9 dome as the harness (75
        # triangles, 23 ft diameter) but exported in feet with its origin on
        # the base ring, where the harness puts its origin at the sphere
        # centre. These three knobs are visual-alignment only -- they register
        # the mesh struts against the LED triangles and never move an LED:
        #   scale            feet -> scene units (0.3048 m/ft * scene_scale)
        #   y_offset_m       drop the base-ring origin onto the sphere centre,
        #                    which sits 2.190 ft up in the .obj's own frame
        #   yaw_phase_degrees  spin the mesh to line its struts up with the
        #                    LED triangles (the dome is 72-degree symmetric)
        "model": {
            "scale": 3.048,
            "y_offset_m": 0,
            "yaw_phase_degrees": 0.0,
        },
    },
    # Hand-imported dome scaffold mesh (Fixtures/dome_model.obj): raw export
    # units, so it gets its own scale and orientation instead of scene_scale.
    "dome_model": {
        "scale": 0.01,
        "pitch_degrees": 90.0,
        "yaw_degrees": 18.0,
    },
}
