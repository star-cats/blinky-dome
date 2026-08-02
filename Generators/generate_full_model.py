#!/usr/bin/env python3
"""
Generate the full Blinkydome model (.lxm) from the generated fixtures.

Assembles every fixture into one Chromatik model, positioned in scene units
(metres * MODEL["scene_scale"], matching the existing project files):

  - The Waterfall carries the dome in its own geometry, so its instance sits
    at the origin.
  - The StarEye grouping (four co-located stars rolled into a pinwheel) stands
    upright against the dome, resting on its bottom tip at a configurable
    bearing and angle above the horizon.
  - Ben's ear pair sits atop the dome and his eye pair on its front surface.
    Both come from Projects/BenStartingPoint.lxp and carry his absolute
    coordinates, so they are translated into place at scale 1.0 rather than
    scaled up from metres like everything else here.
  - The V3 dome cable harness is a separate dome of its own, so it is parked
    beside the cat on the +X axis rather than overlapping it. Its scaffold
    mesh shares that placement (one physical structure) and adds only a
    cosmetic scale and yaw phase of its own.

All placement knobs live in constants.MODEL.
"""

from __future__ import annotations

import json
import math

from constants import MODEL, MODELS_DIR, STAR, WATERFALL
from generate_v3_dome_model import centre_height_m as v3_dome_centre_height_m

OUTPUT_PATH = MODELS_DIR / MODEL["output_file"]

DOME_RADIUS_M = WATERFALL["dome"]["radius_m"]
SCENE_SCALE = MODEL["scene_scale"]
DOME_RADIUS_SCENE = DOME_RADIUS_M * SCENE_SCALE

# Deterministic fixture ids so regeneration produces stable diffs.
FIRST_FIXTURE_ID = 1001
VIEW_ENGINE_ID = 2000
FIRST_VIEW_ID = 2001

# Selectors match fixture tags, so a view is only worth keeping while something
# still carries its tag. "dome-cover" was dropped along with the Astra/Dorsa/
# Tholi harnesses -- the Dome Model mesh still wears the tag but has no LEDs.
MODEL_VIEWS = [
    {"label": "Third Eye", "selector": "star", "modulationColor": 7},
    {"label": "Ears", "selector": "ears", "modulationColor": 3},
    {"label": "Eyes", "selector": "eyes", "modulationColor": 9},
    {"label": "Waterfall", "selector": "waterfall", "modulationColor": 5},
    {"label": "Kyle's Curtain", "selector": "kyles-curtain", "modulationColor": 2},
    {"label": "Small Dome", "selector": "v3-harness", "modulationColor": 1},
]


def rounded(value: float) -> float:
    result = round(value, 6)
    return 0.0 if result == 0 else result


def make_fixture(
    fixture_id: int,
    label: str,
    fixture_type: str,
    *,
    x: float = 0.0,
    y: float = 0.0,
    z: float = 0.0,
    yaw: float = 0.0,
    pitch: float = 0.0,
    roll: float = 0.0,
    scale: float = SCENE_SCALE,
    tags: str = "",
    json_parameters: dict | None = None,
) -> dict:
    """One JsonFixture instance entry, serialized the way Chromatik saves them."""
    return {
        "jsonFixtureType": fixture_type,
        "jsonParameters": json_parameters or {},
        "id": fixture_id,
        "class": "heronarts.lx.structure.JsonFixture",
        "internal": {
            "modulationColor": 0,
            "modulationControlsExpanded": True,
            "modulationsExpanded": True,
        },
        "parameters": {
            "label": label,
            "x": rounded(x),
            "y": rounded(y),
            "z": rounded(z),
            "yaw": rounded(yaw),
            "pitch": rounded(pitch),
            "roll": rounded(roll),
            "scale": scale,
            "selected": False,
            "deactivate": False,
            "enabled": True,
            "brightness": 1.0,
            "identify": False,
            "mute": False,
            "solo": False,
            "tags": tags,
            "fixtureType": fixture_type,
        },
        "children": {},
    }


def make_view_definition(view_id: int, view: dict) -> dict:
    return {
        "id": view_id,
        "class": "heronarts.lx.structure.view.LXViewDefinition",
        "internal": {
            "modulationColor": view["modulationColor"],
            "modulationControlsExpanded": True,
            "modulationsExpanded": True,
        },
        "parameters": {
            "label": view["label"],
            "enabled": True,
            "selector": view["selector"],
            "normalization": 0,
            "normalization/name": "RELATIVE",
            "orientation": 0,
            "orientation/name": "GLOBAL",
            "priority": True,
            "cueActive": False,
        },
        "children": {},
    }


def make_view_engine() -> dict:
    return {
        "id": VIEW_ENGINE_ID,
        "class": "heronarts.lx.structure.view.LXViewEngine",
        "internal": {
            "modulationColor": 0,
            "modulationControlsExpanded": True,
            "modulationsExpanded": True,
        },
        "parameters": {
            "label": "LX",
        },
        "children": {},
        "views": [
            make_view_definition(FIRST_VIEW_ID + index, view)
            for index, view in enumerate(MODEL_VIEWS)
        ],
    }


def star_fixtures(next_id) -> list[dict]:
    """The co-located StarEye pinwheel, standing upright against the dome."""
    stars = MODEL["stars"]
    elevation = math.radians(stars["elevation_degrees"])
    azimuth = math.radians(stars["azimuth_degrees"])

    # Standing upright, the star meets the dome at its bottom tip rather than
    # at its centre, so the configured angles locate that contact point and the
    # centre rides one radius above it. The radius is the star's roll-invariant
    # bounding radius (see constants.star_tip_ratio), which is what keeps the
    # four differently-rolled stars co-located and all of them clear of the
    # surface instead of only the one whose arm happens to point down.
    star_radius_scene = STAR["geometry"]["radius_m"] * SCENE_SCALE
    x = DOME_RADIUS_SCENE * math.sin(azimuth) * math.cos(elevation)
    y = DOME_RADIUS_SCENE * math.sin(elevation) + star_radius_scene
    z = DOME_RADIUS_SCENE * math.cos(azimuth) * math.cos(elevation)

    # Star.lxf is drawn on a unit circle, not in metres, so it is scaled by the
    # vertex circle rather than by scene_scale. outer_radius_m is exactly that
    # circle -- backed out of radius_m so the lit star still measures
    # diameter_ft tip to tip -- which keeps the star's real size pinned to the
    # same constant whichever fixture file is patched.
    star_scale = STAR["geometry"]["outer_radius_m"] * SCENE_SCALE

    return [
        make_fixture(
            next_id(),
            instance["label"],
            stars["fixture"],
            x=x,
            y=y,
            z=z,
            # Yaw alone: the star turns to face out along its bearing and stays
            # vertical. Pitching it onto the dome normal is exactly what we no
            # longer want, so there is deliberately no pitch here.
            yaw=stars["azimuth_degrees"],
            roll=instance["roll_degrees"],
            scale=star_scale,
            tags="star",
            json_parameters={
                "NumPoints": STAR["leds"]["leds_per_strip"],
                "Overlap": STAR["leds"]["overlap_leds"],
                "ip": stars["host"],
                "Universe": instance["universe"],
            },
        )
        for instance in stars["instances"]
    ]


def face_offset() -> tuple[float, float]:
    """Where Ben's project frame lands in this model, as a (y, z) translation.

    BenEars.lxf and BenEyes.lxf carry absolute coordinates from
    Projects/BenStartingPoint.lxp, drawn at the same 10 units/m this model
    uses, so they are translated rather than scaled (see constants.MODEL
    "face"). X needs no offset: Ben's pairs are already centred on x = 0.

    Y slides the ear bases up to ears["tilt_degrees"] on the dome, the spot the
    two CatEar instances used to occupy. Z slides the ear plane onto the dome's
    z = 0 great circle, so the ears stand on top rather than in front.
    """
    tilt = math.radians(MODEL["ears"]["tilt_degrees"])
    base = MODEL["face"]["ear_base"]
    return (
        DOME_RADIUS_SCENE * math.cos(tilt) - base["y"],
        -base["z"],
    )


def ear_fixtures(next_id) -> list[dict]:
    """Ben's ear pair, one fixture holding both ears, atop the dome."""
    offset_y, offset_z = face_offset()
    trim = MODEL["face"]["trim"]["ears"]
    return [
        make_fixture(
            next_id(),
            "Ben Ears",
            "BenEars",
            x=trim["x"],
            y=offset_y + trim["y"],
            z=offset_z + trim["z"],
            yaw=trim["yaw"],
            pitch=trim["pitch"],
            roll=trim["roll"],
            # Ben's units are already scene units, so this fixture is placed
            # 1:1. Handing it scene_scale like the metre-based fixtures would
            # inflate the ears tenfold.
            scale=1.0,
            tags="ears",
        )
    ]


def eye_fixtures(next_id) -> list[dict]:
    """Ben's eyes, one per side, sitting on the dome surface.

    Placed spherically rather than inherited from Ben's frame: elevation is the
    angle up from the horizon, azimuth the bearing about the front (+Z). The
    two eyes take +azimuth and -azimuth at the same elevation, so the pair is
    symmetric and level however the knobs are turned. Both fixtures are centred
    on their own eye, so this lands the eye centres exactly one dome radius out.

    Each eye yaws to face along its own bearing and stays vertical -- no pitch
    onto the dome normal, same as the stars.
    """
    eyes = MODEL["face"]["eyes"]
    elevation = math.radians(eyes["elevation_degrees"])
    azimuth = math.radians(eyes["azimuth_degrees"])
    radius = DOME_RADIUS_SCENE + eyes["standoff"]

    y = radius * math.sin(elevation)
    # Horizontal distance from the dome axis at this height; the bearing then
    # splits it into x and z.
    ring = radius * math.cos(elevation)

    return [
        make_fixture(
            next_id(),
            label,
            fixture_type,
            x=side * ring * math.sin(azimuth),
            y=y,
            z=ring * math.cos(azimuth),
            yaw=side * eyes["azimuth_degrees"],
            # Ben's units are already scene units -- see ear_fixtures.
            scale=1.0,
            tags="eyes",
        )
        for label, fixture_type, side in (
            ("Ben Eye L", "BenEyeL", 1.0),
            ("Ben Eye R", "BenEyeR", -1.0),
        )
    ]


def waterfall_fixture(next_id) -> dict:
    """The waterfall's geometry already places it on the dome back."""
    return make_fixture(
        next_id(), "Waterfall", "Waterfall", tags="waterfall"
    )


def kyles_curtain_fixture(next_id) -> dict:
    """Kyle's curtain, placed as a flat metre-based grid."""
    curtain = MODEL["kyles_curtain"]
    placement = curtain["placement"]
    return make_fixture(
        next_id(),
        "Kyle's Curtain",
        curtain["fixture_type"],
        x=placement["x_offset_m"] * SCENE_SCALE,
        y=placement["y_offset_m"] * SCENE_SCALE,
        z=placement["z_offset_m"] * SCENE_SCALE,
        yaw=placement["yaw_degrees"],
        pitch=placement["pitch_degrees"],
        roll=placement["roll_degrees"],
        tags="kyles-curtain",
    )


def v3_dome_placement() -> dict:
    """Shared transform for the V3 dome's LED harness and its scaffold mesh.

    They are one physical structure, so both fixtures are placed from this
    single set of knobs and translate/rotate together. The transform locates
    the dome's base ring, so a y offset of zero stands it on the ground; the
    two fixtures then each correct for where their own origin sits.
    """
    placement = MODEL["v3_dome"]["placement"]
    return {
        "x": placement["x_offset_m"] * SCENE_SCALE,
        "y": placement["y_offset_m"] * SCENE_SCALE,
        "z": placement["z_offset_m"] * SCENE_SCALE,
        "yaw": placement["yaw_degrees"],
        "pitch": placement["pitch_degrees"],
        "roll": placement["roll_degrees"],
    }


def v3_harness_fixture(next_id) -> dict:
    """The V3 cable harness dome, parked beside the cat on the +X axis.

    Its geometry is a full 3.5 m dome centred on its own origin, so it is
    offset far enough out that nothing on the cat overlaps it.

    The harness measures from the dome's sphere centre, which on a 5/9 dome
    sits above the base ring, so the LEDs are lifted by that much to stand on
    the shared placement. Raising the lights is what keeps the scaffold out of
    the ground -- dropping the mesh instead would bury its lower ring.

    Which .lxf this points at is a knob (harness["fixture_type"]) and currently
    names the hand-authored V3DomeHarness1.lxf rather than the one
    generate_v3_dome_harness.py writes -- see the note in constants.MODEL. Both
    share this origin and radius, so the lift above is the same either way.
    """
    harness = MODEL["v3_dome"]["harness"]
    placement = v3_dome_placement()
    placement["y"] += v3_dome_centre_height_m() * SCENE_SCALE
    return make_fixture(
        next_id(),
        "V3 Dome Cable Harness",
        harness["fixture_type"],
        **placement,
        tags="v3-harness",
        json_parameters={
            f"ipJ{index + 1}": ip for index, ip in enumerate(harness["ips"])
        },
    )


def v3_dome_model_fixture(next_id) -> dict:
    """LED-less UI mesh of the V3 dome scaffold, riding the harness placement.

    Takes the shared V3 placement so it stays locked to the harness. The mesh
    is generated in metres off the same hub sphere as the LED triangles, and
    its origin is already the base ring, so it sits on the placement as-is --
    the harness is the one that lifts to meet it. LXFixture translates before
    it scales, so any trim below is in scene units and survives a scale trim.
    """
    mesh = MODEL["v3_dome"]["model"]
    placement = v3_dome_placement()
    placement["y"] += mesh["y_trim_m"] * SCENE_SCALE
    placement["yaw"] += mesh["yaw_phase_degrees"]
    return make_fixture(
        next_id(),
        "V3 Dome Model",
        "v3_dome_model",
        **placement,
        scale=SCENE_SCALE * mesh["scale_trim"],
        tags="v3-harness",
    )


def dome_model_fixture(next_id) -> dict:
    """LED-less UI mesh of the dome scaffold (hand-imported .obj).

    The mesh is a raw export, so it carries its own scale and orientation
    from MODEL["dome_model"] rather than the shared scene placement.
    """
    dome = MODEL["dome_model"]
    return make_fixture(
        next_id(),
        "Dome Model",
        "dome_model",
        yaw=dome["yaw_degrees"],
        pitch=dome["pitch_degrees"],
        scale=dome["scale"],
        tags="dome-cover",
    )


def build_model() -> dict:
    counter = iter(range(FIRST_FIXTURE_ID, FIRST_FIXTURE_ID + 1000))

    def next_id() -> int:
        return next(counter)

    fixtures = [
        *star_fixtures(next_id),
        *ear_fixtures(next_id),
        *eye_fixtures(next_id),
        waterfall_fixture(next_id),
        kyles_curtain_fixture(next_id),
        dome_model_fixture(next_id),
        v3_harness_fixture(next_id),
        v3_dome_model_fixture(next_id),
    ]

    return {
        "version": MODEL["lx_version"],
        # Fixed timestamp keeps regeneration deterministic for clean diffs.
        "timestamp": 0,
        "children": {
            "views": make_view_engine(),
        },
        "fixtures": fixtures,
        "normalization": {
            "normalizationMode": 0,
            "normalizationX": 0.0,
            "normalizationY": 0.0,
            "normalizationZ": 0.0,
            "normalizationWidth": 1000.0,
            "normalizationHeight": 1000.0,
            "normalizationDepth": 1000.0,
        },
    }


def main() -> None:
    model = build_model()
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {OUTPUT_PATH} ({len(model['fixtures'])} fixtures)")


if __name__ == "__main__":
    main()
