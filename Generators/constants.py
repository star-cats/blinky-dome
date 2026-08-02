"""Shared generator constants.

All geometry constants in this file are metres unless the name explicitly says
otherwise. LED density is expressed as LEDs per centimetre for quick physical
sanity checks.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path


# Several generators use PEP 585 subscripts ("Vec = tuple[float, float, float]")
# as module-level type aliases, which are evaluated at run time rather than
# deferred like annotations are. On 3.8 that means the run gets all the way into
# a generator and then dies with "'type' object is not subscriptable", naming
# whichever module it happened to reach first. Fail here instead, where the
# message can say what is actually wrong. Every generator imports this module
# before its own aliases are evaluated, so this covers standalone runs too.
#
# Verified against 3.9 / 3.10 / 3.11 / 3.12 / 3.13: byte-identical output.
MIN_PYTHON = (3, 9)
if sys.version_info < MIN_PYTHON:
    raise SystemExit(
        f"error: the fixture generators need Python "
        f"{MIN_PYTHON[0]}.{MIN_PYTHON[1]} or newer, but this is "
        f"{'.'.join(str(part) for part in sys.version_info[:3])}.\n"
        f"       interpreter: {sys.executable}\n"
        f"       pyenv reads the pin in .python-version at the repo root."
    )


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


def star_tip_ratio(shape: dict, length_ratio: float) -> float:
    """Distance to the furthest LED, as a multiple of the vertex-circle radius.

    Each strip runs a little past the vertex it aims at -- 2.0 against a chord
    of 1.902 -- so what bounds the star is its arm ends, not the circle the
    vertices sit on. All five overshoot equally, which makes this radius the
    same no matter how the star is rolled; the model relies on that when it
    stands the pinwheel on the dome.

    length_ratio is how far the LEDs themselves reach, which is short of the
    strip end (see reach_ratio below). Measuring to the strip end instead would
    describe a star bigger than the one that lights up, and leave it hanging
    above whatever it is meant to be resting on.
    """
    length = length_ratio
    # The first LED of each arm sits on its vertex, which is on the unit circle
    # by construction, so that is the floor.
    extent = 1.0
    for vertex_degrees, segment_degrees in zip(
        shape["vertex_angles_degrees"], shape["segment_angles_degrees"]
    ):
        vertex = math.radians(vertex_degrees)
        segment = math.radians(segment_degrees)
        # Distance from the centre is convex along a straight run, so the
        # maximum is at one end or the other -- no need to walk the LEDs.
        extent = max(
            extent,
            math.hypot(
                math.cos(vertex) + math.cos(segment) * length,
                math.sin(vertex) + math.sin(segment) * length,
            ),
        )
    return extent


STAR = {
    "output_file": "StarEye.lxf",
    "coordinate_system": (
        "Y-up right-handed, origin at star centre, units metres"
    ),
    "geometry": {
        # The one physical dimension of the star: how far it measures across at
        # its widest, tip to opposite tip. Every other length below is derived
        # from it, so this is the only knob to touch when the star resizes.
        "diameter_ft": 4.2,
        "points": 5,
        # Pentagram construction, ported from Fixtures/Star.lxf. The vertices
        # sit on a unit circle in this order (top, lower-right, upper-left,
        # upper-right, lower-left) and a strip leaves each one on the matching
        # bearing. Unitless -- these describe the shape, diameter_ft the size.
        "vertex_angles_degrees": [90.0, -54.0, 162.0, 18.0, -126.0],
        "segment_angles_degrees": [-72.0, 144.0, 0.0, 216.0, 72.0],
        "strip_length_ratio": 2.0,
    },
    "leds": {
        # Derived from Projects/Blinkydome2026.lxp Star jsonParameters.
        "leds_per_strip": 60,
        "overlap_leds": 0,
        "strip_count": 5,
    },
}
# How far along an arm the lit pixels actually get. Spacing splits the strip
# into leds+1 gaps with the first LED on the vertex, so the last one stops two
# gaps short of the strip end.
STAR["leds"]["reach_ratio"] = STAR["geometry"]["strip_length_ratio"] * (
    (STAR["leds"]["leds_per_strip"] - 1) / (STAR["leds"]["leds_per_strip"] + 1)
)
# radius_m is the half-width the model stands the star on, measured to the
# outermost LED; outer_radius_m is the vertex circle the fixture is drawn from,
# backed out of it so the lit star measures diameter_ft tip to tip.
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
    "elevation_degrees": 10.0,
}


# V3 dome cable harness (generate_v3_dome_harness.py). LED triangles sit on the
# same 3V 5/9 geodesic as generate_v3_dome_model.py, one per face, with their
# corners on the strut midpoints. Numbering runs ring by ring from the apex
# down, and by azimuth within a ring; the top 10 of the dome's 14 face rings
# (5,5,10,10,5,10,5,10,10,5 = 75 faces) carry LEDs and the bottom 4 do not.
#
# Only placement is computed here. The wiring below is physical fact about the
# installed dome -- rewriting it would resequence every pixel and break the
# patterns and Art-Net mapping already built against this fixture.
V3_HARNESS = {
    "output_file": "V3DomeHarness.lxf",
    "coordinate_system": (
        "Y-up right-handed, origin at sphere centre (floor level), units metres"
    ),
    "dome_radius_m": 3.5,
    # A physical LED strip: 33 LEDs over 0.575 m. Its span is therefore a touch
    # shorter than a triangle edge, so each strip starts on its corner and stops
    # half a pixel short of the next one.
    "led_triangle_edge_m": 0.575,
    "leds_per_side": 33,
    # Art-Net: box IP is 192.168.123.<ipJn>, one harness per port, universes
    # allocated port*3-3 upward, 170 pixels per universe.
    "universe_size_px": 170,
    "boxes": ["J1", "J2", "J3", "J4", "J5"],
    # box -> harness (port) -> triangle numbers in position order.
    "wiring": {
        "J1": [[20, 36, 46, 61], [21, 51, 71, 60], [30, 11, 10, 5], [45, 35, 70]],
        "J2": [[12, 38, 47, 63], [23, 53, 72, 52], [22, 13, 6, 1], [37, 31, 62]],
        "J3": [[14, 40, 48, 65], [25, 55, 73, 54], [24, 15, 7, 2], [39, 32, 64]],
        "J4": [[16, 42, 49, 67], [27, 57, 74, 56], [26, 17, 8, 3], [41, 33, 66]],
        "J5": [[18, 44, 50, 69], [29, 59, 75, 58], [28, 19, 9, 4], [43, 34, 68]],
    },
    # Carried through to the fixture's metadata block verbatim.
    "junction_hub": {"el": 46.64, "az": 0},
}


# Full-model assembly (generate_full_model.py). Positions are in scene units:
# fixture geometry is metres, and every instance is placed at scene_scale, so
# scene units = metres * scene_scale (matching the existing project files).
MODEL = {
    "output_file": "full_camp_model_2026.lxm",
    "lx_version": "1.2.0",
    "scene_scale": 10.0,
    # UNUSED as of the 2026 layout: Astra/Dorsa/Tholi were dropped from
    # generate_full_model.py. Kept here so the grouping and controller
    # assignments are not lost if the dome cover comes back.
    "harness_groups": [
        {"name": "Astra", "yaw_degrees": 0.0, "ip": 11},
        {"name": "Dorsa", "yaw_degrees": 72.0, "ip": 12},
        {"name": "Tholi", "yaw_degrees": 288.0, "ip": 15},
    ],
    # UNUSED as of the 2026 layout: Eye L / Eye R were dropped from
    # generate_full_model.py, and the eyes are now Ben's arc pair (see "face"
    # below) rather than triangle patches baked onto the dome. The DomeEyeL/R
    # fixtures are still generated by generate_dome_eye.py, so re-adding them
    # is just a call site.
    "legacy_dome_eyes": {
        "eye_distance_m": 5,
        "instances": [
            {"label": "Eye L", "fixture": "DomeEyeL", "ip": 13, "side": 1},
            {"label": "Eye R", "fixture": "DomeEyeR", "ip": 14, "side": -1},
        ],
    },
    # The star grouping is four co-located StarEyes rolled ~15 degrees apart
    # (a stacked pinwheel). The stars stand upright rather than lying flat on
    # the dome, so they are positioned by where their bottom tip rests against
    # the surface: elevation_degrees is that contact point's angle up from the
    # horizontal, azimuth_degrees its bearing around the dome (0 = front, +Z).
    # The star then hangs vertically from there, facing out along the bearing.
    "stars": {
        # Fixtures/Star.lxf, the hand-written pentagram -- the same file
        # Projects/BenStartingPoint.lxp patches, so the model and Ben's project
        # light the identical fixture. Not StarEye.lxf: generate_star.py still
        # writes that one, and nothing references it while this says "Star".
        #
        # Star.lxf is drawn on a unit circle rather than in metres, so it is the
        # one fixture here whose instance scale is not scene_scale. The model
        # scales it by the vertex circle it was drawn from
        # (STAR["geometry"]["outer_radius_m"]), which keeps the lit star at the
        # diameter_ft the build actually measures. Ben runs it at a flat scale
        # of 10, which makes his stars 6.8 ft across instead of 4.2.
        #
        # NumPoints and Overlap come from STAR["leds"], so the fixture's pixel
        # count stays tied to the same constants generate_star.py reads.
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
    # UNUSED as of the 2026 layout: the two CatEar instances were replaced by
    # Ben's ear pair (see "face" below). generate_cat_ear.py still writes
    # CatEar.lxf, so bringing them back is just a call site. tilt_degrees is
    # still live -- "face" reuses it to decide where the ear bases meet the
    # dome, which is exactly where these stood.
    "ears": {
        "tilt_degrees": 35.0,
    },
    # Ben's cat face: Fixtures/BenEars.lxf and Fixtures/BenEyes.lxf, lifted out
    # of Projects/BenStartingPoint.lxp. Each file holds a left/right pair in one
    # fixture with the spread already baked into its components, so each is
    # instantiated once rather than mirrored per side.
    #
    # Ben drew them at the same 10 units/m this model uses -- his 192-pixel ear
    # strips at 0.1 spacing are the same 1.92 m ears CatEar.lxf described, and
    # his outer ear bases sit at x = +/-28, within 0.03 units of where the
    # CatEar instances stood. So the face is translated into place, never
    # scaled; instance scale stays at 1.0 and scene_scale does not apply.
    #
    # Anchoring: the whole face slides along Y until the ear bases land at
    # ears["tilt_degrees"] up from the horizon, and along Z until the ear plane
    # sits on the dome's z = 0 great circle. The eyes inherit that offset, so
    # Ben's ear-to-eye spacing survives in X and Y. Z is the exception -- his
    # dome was a third the size of this one, so the eyes are pushed out to this
    # dome's front surface instead of keeping his much shorter standoff.
    "face": {
        # Read off Fixtures/BenEars.lxf, components "Ear L B" / "Ear R B": the
        # outer ear bases, and the point the Y/Z anchoring solves for.
        "ear_base": {"x": 28.0, "y": 7.0, "z": -10.0},
        # The eyes do not ride the face offset. Ben drew them against a dome a
        # third of this one's size, so rather than inherit his position they are
        # placed straight onto this dome's surface in spherical terms, the same
        # way the stars are: elevation up from the horizon, azimuth as a bearing
        # about the front (+Z). The pair is symmetric by construction -- the
        # left eye sits at +azimuth and the right at -azimuth, one elevation
        # between them, so they cannot drift out of line with each other.
        #
        # Each eye yaws onto its own bearing and is left vertical; there is
        # deliberately no pitch onto the dome normal, matching the stars.
        # Fixtures/BenEyeL.lxf and BenEyeR.lxf are centred on their own eye, so
        # the placement lands the eye centre exactly on the surface.
        #
        # The defaults reproduce where the eyes sat when they were carried along
        # by Ben's own layout, so this is a re-parameterisation rather than a
        # move. standoff pushes both eyes out along the surface normal.
        "eyes": {
            "azimuth_degrees": 24,
            "elevation_degrees": 23,
            "standoff": 0.0,
        },
        # Visual trim for the ears, applied on top of the derived placement.
        # The derived numbers reproduce Ben's layout against this dome; these
        # are for nudging it once you can see it in the preview. Scene units,
        # degrees. The eyes have no equivalent on purpose: azimuth, elevation
        # and standoff above are their knobs, and a free x/y/z nudge there is
        # exactly what would pull the pair off the surface or out of symmetry.
        "trim": {
            "ears": {"x": 0.0, "y": 0.0, "z": 0.0, "yaw": 0.0, "pitch": 0.0, "roll": 0.0},
        },
    },
    # The V3 dome is a whole second dome, not part of the cat, so it is parked
    # off to the side on +X far enough that its own 3.5 m radius clears the
    # ears and waterfall. Its LED harness (V3DomeHarness.lxf) and its scaffold
    # mesh (v3_dome_model.lxf) are one physical structure, so they share
    # "placement" and move together; only the mesh's cosmetic knobs differ.
    #
    # "placement" locates the dome's base ring, so y_offset_m 0 stands it on
    # the ground and positive values lift the whole dome. The harness measures
    # from the sphere centre instead, which on a 5/9 dome is above the base, so
    # generate_full_model.py raises the LEDs by that much rather than sinking
    # the scaffold below y=0.
    "v3_dome": {
        "placement": {
            "x_offset_m": 10.0,
            "y_offset_m": 0.0,
            "z_offset_m": 0.0,
            "yaw_degrees": 18.0,
            "pitch_degrees": 0.0,
            "roll_degrees": 0.0,
        },
        # ips are the last octet of each junction box on 192.168.123.x, J1-J5.
        #
        # fixture_type is the .lxf the model instantiates, which is deliberately
        # *not* the one generate_v3_dome_harness.py writes. V3DomeHarness1.lxf is
        # the hand-authored fixture from the dome build (upstream
        # blinky-dome-chromatik, branch koa_072926): same origin, radius, wiring,
        # Art-Net outputs and parameter names as the generated V3DomeHarness.lxf,
        # but a different rotational phase for the triangles, and it is what the
        # installed dome is actually wired to. The generator still runs and still
        # writes V3DomeHarness.lxf so the geometry stays reproducible; nothing in
        # the model references it while this points elsewhere.
        "harness": {
            "fixture_type": "V3DomeHarness1",
            "ips": [11, 12, 13, 14, 15],
        },
        # v3_dome_model.obj is the same 3V 5/9 dome as the harness, generated
        # off the same hub sphere and in the same units, so it needs no scale
        # conversion -- only a drop from its base-ring origin onto the sphere
        # centre the harness is built around, which generate_full_model.py
        # takes straight from the dome geometry.
        #
        # The knobs below are visual trim on top of that, applied only to the
        # mesh; they never move an LED. Leave them at their neutral values
        # unless the mesh visibly disagrees with the LED triangles.
        #   scale_trim         multiplies the derived scale (1.0 = exact)
        #   y_trim_m           extra height nudge, metres
        #   yaw_phase_degrees  spins the mesh to line its struts up with the
        #                      LED triangles (the dome is 72-degree symmetric)
        "model": {
            "scale_trim": 1.0,
            "y_trim_m": 0.0,
            "yaw_phase_degrees": -18.0,
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
