#!/usr/bin/env python3
"""Generate Fixtures/v3_quintile_module.lxf -- one fifth of the small V3 dome.

The dome is five-fold symmetric, so the whole LED surface is one module placed
five times and yawed 72 degrees apart. This generator emits that single module:
15 triangles, wired as 4 harnesses off one junction box (4/4/4/3 triangles),
each triangle three 11-pixel strips -- 45 strips, 495 pixels, 1485 channels.

Nothing about where a triangle sits is written down. The dome is built here,
from the numbers at the top of the file, in layers:

  icosahedron()        the 20-face solid the geodesic starts from
  subdivide()          each face split FREQUENCY ways and pushed onto the sphere
  truncate()           cut to the top HUB_RINGS_KEPT rings -- the 5/9 dome
  dome_faces()         the surviving faces, numbered 1..75 ring by ring from the
                       apex and clockwise within a ring -- the same faces the
                       dome model draws struts around
  triangle_transform() one panel's whole pose: centre in metres, plus yaw, pitch
                       and roll in degrees, seating it flat in its face
  triangle_corners()   that pose expanded into three corners
  strip_run()          one corner-to-corner edge as an origin and a direction

So triangle_transform(35) is the entire placement of triangle 35, and changing
FREQUENCY or HUB_RINGS_KEPT re-tessellates the whole dome underneath it. The
only thing this file states rather than derives is which triangles hang off
which harness, which is a wiring fact read off the drawing.

A panel is seated in the plane of its face, not on the sphere: a geodesic face
is a flat chord triangle whose normal runs through its circumcentre, which is
not quite the direction of its centroid, so building on the sphere's tangent
plane instead tilts every panel by up to 1.4 degrees and lifts its corners off
the frame. The panel is then spun to the orientation that fits its face best --
the three strut midpoints averaged under the triangle's own 120-degree symmetry
-- rather than aimed at whichever midpoint the subdivision happened to list
first, which is arbitrary and misses by up to 5 degrees on an irregular face.

Triangles are numbered the way you would count them standing over the dome:
looking straight down, start at the triangle at twelve o'clock, work clockwise
around the ring, then drop to the next ring and go again. Ring 1 is the five
around the apex; ring 10 is the five at the base.

The datum is the OBJ's datum. Fixtures/v3_dome_model.obj is the ground truth for
this dome -- 61 hubs on a 3.4999 m sphere, base ring on y=0, apex at 4.1566 m --
and this fixture is emitted in exactly that frame, so it drops in with the same
instance transform the mesh uses. In the camp projects that is x=100, y=0, z=0,
yaw=0, and SCALE 10, because those projects run at ten scene units per metre.
Leave the scale at 1 and the dome comes out a tenth of its size.

The yaw that squares the module up with the dome as built is baked in too, so
the five instances go in at yaw 0, 72, 144, 216 and 288 and nothing else.

Everything the field can get wrong is a fixture parameter rather than a
regenerated file:

  - protocol, controller IP, and a start universe + start channel per harness;
  - per triangle, which corner its run of LEDs starts at (a rotation of 0, 1 or
    2 thirds of a turn) and whether it is fed from the far end instead.

A triangle is one continuous 33-pixel strip that wraps all three edges, so those
two settings are the whole of what can be wired differently: three corners it
can start from, either direction round. Geometry still has to be three straight
runs of 11 -- a strip component cannot bend -- but they are the three edges of
one strip, not three separate strips.

Those parameters move only the *output* mapping. Geometry stays put: the edges
are always emitted in the same physical order, and the Art-Net segments pick
pixel ranges out of them in whatever order the run really reaches them.

Coordinates are metres, Y up, origin at the sphere centre.
"""

from __future__ import annotations

import argparse
import json
import math
from functools import lru_cache
from pathlib import Path


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

OUTPUT_FILENAME = "v3_quintile_module.lxf"
FIXTURES_DIR = Path(__file__).resolve().parents[1] / "Fixtures"
OUTPUT_PATH = FIXTURES_DIR / OUTPUT_FILENAME

FIXTURE_LABEL = "V3 Quintile Module"
FIXTURE_TAGS = ["dome", "geodesic", "v3", "quintile", "module"]
COORDINATE_SYSTEM = (
    "Y-up right-handed, origin at the centre of the base ring, units metres "
    "-- the same frame as Fixtures/v3_dome_model.obj"
)


# ---------------------------------------------------------------------------
# Tessellation -- these numbers define the dome
# ---------------------------------------------------------------------------

# Hub-sphere radius, in metres.
DOME_RADIUS_M = 3.5

# Class I subdivision frequency. 3V => each icosahedron face becomes 9 faces.
FREQUENCY = 3

# A point-up 3V sphere stacks its hubs on 16 latitude rings. Keeping the top ten
# cuts just below the equator -- the 5/9 Kruschke truncation.
HUB_RINGS_KEPT = 10

# Of the face rings that truncation leaves, how many carry an LED triangle.
PANEL_RINGS_LIT = 10

# What that must produce; asserted after the fact so a bad edit above fails
# loudly instead of quietly shipping a malformed dome.
EXPECTED_PANELS = 75

# Yaw the module needs to line up with the dome as it actually stands, in
# Chromatik's sense -- the number you would otherwise type into the fixture's
# yaw field. It is baked into the emitted geometry so the five instances sit at
# plain multiples of MODULE_YAW_DEGREES with nothing else dialled in.
#
# Triangle numbering is deliberately worked out before this is applied: a
# triangle's number belongs to the dome's tessellation, not to how the fixture
# happens to be turned, so changing this does not renumber anything.
FIXTURE_YAW_DEGREES = -35.0

# The icosahedron is five-fold symmetric about its point-up axis, so the dome
# divides into five identical modules.
MODULES_PER_DOME = 5
MODULE_YAW_DEGREES = 360.0 / MODULES_PER_DOME
PANELS_PER_MODULE = EXPECTED_PANELS // MODULES_PER_DOME

# Hubs are welded at this many decimal places, and face rings are grouped by
# equal centroid height within this tolerance. The closest pair of rings differs
# by ~0.028 on the unit sphere, so there is a wide margin either way.
WELD_DIGITS = 9
RING_TOLERANCE = 1e-6

# How near twelve o'clock a triangle has to be to count as sitting on it.
BEARING_TOLERANCE = 1e-6


# ---------------------------------------------------------------------------
# LED geometry
# ---------------------------------------------------------------------------

# Edge of the rigid equilateral LED triangle that sits inset in each face.
TRIANGLE_EDGE_M = 0.575

# Corner radius of an equilateral triangle: edge / sqrt(3).
CORNER_RADIUS_M = TRIANGLE_EDGE_M / math.sqrt(3.0)

# A triangle is one continuous strip of addressable RGB pixels wrapping its
# three edges, 11 to an edge.
LEDS_PER_EDGE = 11
EDGES_PER_TRIANGLE = 3
LEDS_PER_TRIANGLE = LEDS_PER_EDGE * EDGES_PER_TRIANGLE

# Each pixel owns one pitch of strip, so the run of 11 is centred on its edge:
# the first pixel sits half a pitch in from the corner, the last half a pitch
# shy of the next corner.
LED_SPACING_M = TRIANGLE_EDGE_M / LEDS_PER_EDGE

# Edges of a triangle, in physical order: edge 1 runs corner 0 -> 1, edge 2 runs
# 1 -> 2, edge 3 runs 2 -> 0. Corner 0 sits at the pose's roll angle and the
# corners wind counter-clockwise seen from outside the dome.
EDGE_NAMES = ("1", "2", "3")

# One continuous strip round a triangle can only be wired six ways: it starts at
# one of the three corners, and it is fed from one end or the other. That is a
# rotation of 0, 1 or 2 thirds of a turn, plus a reverse -- there is no
# permuting of edges, because they are not separable.
TRIANGLE_ROTATIONS = 3
DEFAULT_ROTATION = 0
DEFAULT_REVERSED = False


# ---------------------------------------------------------------------------
# Wiring
# ---------------------------------------------------------------------------

# Harness (junction box port) -> the triangles it feeds, in daisy-chain order,
# in the dome-wide 1..75 numbering that dome_faces() builds. Which triangles
# share a harness, and the order the chain reaches them, are read off the colour
# coding in Data/v3domeharness-img.jpg. Two things that drawing does not label,
# and that can be rearranged here without touching anything else:
#   - which harness colour is port 1..4. Green/blue/orange/red is assumed, which
#     keeps the short 3-triangle harness on port 4 as on the previous build.
#   - the chain order of the red harness, whose drops are unnumbered. Its doubled
#     trunk reads as data running up to the topmost triangle first, back down,
#     then out along the other two.
HARNESS_TRIANGLES = {
    1: (13, 38, 72, 53),  # green
    2: (12, 23, 63, 52),  # blue
    3: (37, 24, 64, 48),  # orange
    4: (2, 7, 32),        # red
}


# ---------------------------------------------------------------------------
# Output defaults
# ---------------------------------------------------------------------------

# Art-Net and sACN both address as universe + channel, so the protocol can be
# switched without the harness offsets meaning something different. DDP, OPC and
# KiNET read different keys and are deliberately not offered.
PROTOCOL_OPTIONS = ["artnet", "sacn"]
DEFAULT_PROTOCOL = "artnet"
BYTE_ORDER = "rgb"

DEFAULT_IP = "192.168.1.60"

# One harness is 4 triangles = 132 pixels = 396 channels, so it fits inside a
# single universe when it starts on channel 0.
DEFAULT_HARNESS_UNIVERSE = {1: 0, 2: 1, 3: 2, 4: 3}
DEFAULT_HARNESS_CHANNEL = {1: 0, 2: 0, 3: 0, 4: 0}

MAX_UNIVERSE = 32767
MAX_CHANNEL = 511


# ---------------------------------------------------------------------------
# Vector helpers
# ---------------------------------------------------------------------------

Vec = tuple[float, float, float]
Face = tuple[int, int, int]


def rounded(value: float, digits: int) -> float:
    result = round(value, digits)
    return 0.0 if result == 0 else result


def add(a: Vec, b: Vec) -> Vec:
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def subtract(a: Vec, b: Vec) -> Vec:
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def scale(vector: Vec, scalar: float) -> Vec:
    return (vector[0] * scalar, vector[1] * scalar, vector[2] * scalar)


def dot(a: Vec, b: Vec) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def cross(a: Vec, b: Vec) -> Vec:
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def normalize(vector: Vec) -> Vec:
    magnitude = math.sqrt(dot(vector, vector))
    if magnitude == 0:
        raise ValueError("Cannot normalize a zero-length vector")
    return scale(vector, 1.0 / magnitude)


def midpoint(a: Vec, b: Vec) -> Vec:
    return scale(add(a, b), 0.5)


def centroid(points) -> Vec:
    total = (0.0, 0.0, 0.0)
    for point in points:
        total = add(total, point)
    return scale(total, 1.0 / len(points))


def azimuth_degrees(point: Vec) -> float:
    """Angle about the vertical axis, in [0, 360)."""
    return math.degrees(math.atan2(point[2], point[0])) % 360.0


def elevation_degrees(point: Vec) -> float:
    """Angle above the horizontal plane, in [-90, 90]."""
    return math.degrees(math.asin(point[1] / math.sqrt(dot(point, point))))


def clockwise_bearing(point: Vec) -> float:
    """Bird's-eye bearing in [0, 360), clockwise from twelve o'clock.

    Looking straight down at the dome, +X runs right across the view and -Z runs
    up it -- where the camera ends up when it swings over the top from the front.
    Zero is dead top and the count runs clockwise, which is the order the
    triangles are numbered in.

    A triangle sitting exactly at twelve o'clock computes as a hair either side
    of zero, and a hair below would wrap to 359.999... and sort to the end of its
    ring instead of the front, silently rotating the whole ring. Anything inside
    the tolerance is pinned to zero.
    """
    bearing = math.degrees(math.atan2(point[0], -point[2]))
    if bearing < -BEARING_TOLERANCE:
        bearing += 360.0
    return max(bearing, 0.0)


def yaw_rotated(point: Vec, degrees: float) -> Vec:
    """Point spun about the Y axis, increasing its azimuth by the given angle."""
    angle = math.radians(degrees)
    cos, sin = math.cos(angle), math.sin(angle)
    return (
        point[0] * cos - point[2] * sin,
        point[1],
        point[0] * sin + point[2] * cos,
    )


def instance_yawed(point: Vec, degrees: float) -> Vec:
    """Point spun the way Chromatik's fixture yaw field spins it.

    LXMatrix.rotateY turns +X toward -Z, the opposite sense to the azimuth this
    file measures with atan2(z, x), so a Chromatik yaw of d is a rotation of -d
    in these coordinates. Checked against LXMatrix itself -- a yaw of +35 sends
    +X to azimuth -35 -- because taking the sign on faith would leave the module
    twice the angle out rather than on the dome.
    """
    return yaw_rotated(point, -degrees)


# ---------------------------------------------------------------------------
# Geodesic: icosahedron -> subdivided sphere -> truncated dome -> numbered panels
# ---------------------------------------------------------------------------

def icosahedron() -> tuple[list[Vec], list[Face]]:
    """Unit icosahedron with a vertex at the north pole.

    Point-up is what puts a pentagon at the apex; the other orientations bury
    the apex hub in the middle of a face and the dome loses its centre node.
    """
    # The two staggered rings of five sit at +/- atan(1/2) latitude.
    ring_latitude = math.atan(0.5)
    ring_radius = math.cos(ring_latitude)
    ring_height = math.sin(ring_latitude)

    vertices: list[Vec] = [(0.0, 1.0, 0.0)]
    for index in range(5):
        angle = math.radians(72.0 * index)
        vertices.append(
            (ring_radius * math.sin(angle), ring_height, ring_radius * math.cos(angle))
        )
    for index in range(5):
        angle = math.radians(72.0 * index + 36.0)
        vertices.append(
            (ring_radius * math.sin(angle), -ring_height, ring_radius * math.cos(angle))
        )
    vertices.append((0.0, -1.0, 0.0))

    faces: list[Face] = []
    for index in range(5):
        north, east = 1 + index, 1 + (index + 1) % 5
        south, south_east = 6 + index, 6 + (index + 1) % 5
        faces.append((0, north, east))
        faces.append((north, south, east))
        faces.append((south, south_east, east))
        faces.append((11, south_east, south))
    return vertices, faces


def subdivide(
    vertices: list[Vec], faces: list[Face], frequency: int
) -> tuple[list[Vec], list[Face]]:
    """Class I (alternate) subdivision: split each face into frequency^2 faces.

    Every generated point is normalized onto the unit sphere, and points shared
    between faces are welded by rounded coordinate so hubs are not duplicated
    along the seams.
    """
    welded: dict[tuple[float, ...], int] = {}
    sphere: list[Vec] = []

    def hub(point: Vec) -> int:
        point = normalize(point)
        key = tuple(round(axis, WELD_DIGITS) for axis in point)
        if key not in welded:
            welded[key] = len(sphere)
            sphere.append(point)
        return welded[key]

    subdivided: list[Face] = []
    for face in faces:
        a, b, c = (vertices[index] for index in face)
        # Barycentric lattice over the flat face, then projected outward.
        lattice = {
            (i, j): hub(
                tuple(
                    (i * a[axis] + j * b[axis] + (frequency - i - j) * c[axis]) / frequency
                    for axis in range(3)
                )
            )
            for i in range(frequency + 1)
            for j in range(frequency + 1 - i)
        }
        for i in range(frequency):
            for j in range(frequency - i):
                subdivided.append((lattice[(i, j)], lattice[(i + 1, j)], lattice[(i, j + 1)]))
                if i + j < frequency - 1:
                    subdivided.append(
                        (lattice[(i + 1, j)], lattice[(i + 1, j + 1)], lattice[(i, j + 1)])
                    )
    return sphere, subdivided


def truncate(
    sphere: list[Vec], faces: list[Face], rings_kept: int
) -> tuple[list[Vec], list[Face]]:
    """Keep the faces lying wholly within the top `rings_kept` hub rings.

    Cutting by whole faces rather than by hub is what leaves a clean boundary:
    no strut is left dangling with its far hub removed.
    """
    ring_heights = sorted({round(point[1], WELD_DIGITS) for point in sphere}, reverse=True)
    if rings_kept > len(ring_heights):
        raise ValueError(f"Only {len(ring_heights)} rings exist, asked for {rings_kept}")
    cut_height = ring_heights[rings_kept - 1] - 1e-9

    kept = [
        face for face in faces if all(sphere[index][1] >= cut_height for index in face)
    ]
    surviving = sorted({index for face in kept for index in face})
    remap = {old: new for new, old in enumerate(surviving)}
    return (
        [sphere[index] for index in surviving],
        [tuple(remap[index] for index in face) for face in kept],
    )


@lru_cache(maxsize=None)
def dome_faces() -> dict[int, tuple[Vec, Vec, Vec]]:
    """Triangle number -> its face's three hubs, on the unit sphere.

    These are the same faces the dome model puts struts around, so a panel
    seated in one is seated in the frame that is actually built.

    Numbering runs ring by ring from the apex and by azimuth within a ring,
    which is the numbering printed on the wiring drawing.
    """
    hubs, faces = truncate(*subdivide(*icosahedron(), FREQUENCY), HUB_RINGS_KEPT)

    panels = []
    for face in faces:
        corners = tuple(hubs[index] for index in face)
        panels.append((centroid(corners), corners))

    # Ring by ring from the apex, then clockwise inside each ring.
    panels.sort(key=lambda panel: -panel[0][1])
    rings: list[list] = []
    for panel in panels:
        if rings and abs(rings[-1][0][0][1] - panel[0][1]) <= RING_TOLERANCE:
            rings[-1].append(panel)
        else:
            rings.append([panel])

    lit = [
        panel
        for ring in rings[:PANEL_RINGS_LIT]
        for panel in sorted(ring, key=lambda p: clockwise_bearing(p[0]))
    ]
    if len(lit) != EXPECTED_PANELS:
        raise ValueError(
            f"Tessellation produced {len(lit)} panels, expected {EXPECTED_PANELS}"
        )
    return {number: corners for number, (_, corners) in enumerate(lit, start=1)}


@lru_cache(maxsize=None)
def sphere_centre_above_base_m() -> float:
    """How far the sphere centre sits above the dome's base ring, in metres.

    The truncation cuts below the equator, so the dome's lowest hubs are under
    its sphere centre. The geodesic is built around that centre and the fixture
    is emitted around the base ring, to land on the same datum as the dome model
    OBJ, so this is what bridges the two.
    """
    hubs, _ = truncate(*subdivide(*icosahedron(), FREQUENCY), HUB_RINGS_KEPT)
    return -min(hub[1] for hub in hubs) * DOME_RADIUS_M


# ---------------------------------------------------------------------------
# Placement: a panel's pose, and the geometry that hangs off it
# ---------------------------------------------------------------------------

def face_normal(corners: tuple[Vec, Vec, Vec]) -> Vec:
    """Outward unit normal of a face's own flat plane.

    A geodesic face is a chord triangle: its three hubs are on the sphere but
    the plane they span is not tangent to it, and its normal runs through the
    face's circumcentre rather than its centroid. On a 3V dome those directions
    differ by over a degree, which is the difference between a panel lying flat
    in its frame and one propped up off it.
    """
    a, b, c = corners
    normal = normalize(cross(subtract(b, a), subtract(c, a)))
    return normal if dot(normal, centroid(corners)) > 0 else scale(normal, -1.0)


def panel_frame(yaw_degrees: float, pitch_degrees: float) -> tuple[Vec, Vec, Vec]:
    """The frame a panel is built in: outward, uphill, and across.

    Yaw and pitch aim the outward normal; uphill is the in-plane direction that
    climbs toward the apex, and across completes a right-handed set so that
    angles measured from uphill toward across run counter-clockwise seen from
    outside the dome.
    """
    yaw, pitch = math.radians(yaw_degrees), math.radians(pitch_degrees)
    outward = (math.cos(pitch) * math.cos(yaw), math.sin(pitch), math.cos(pitch) * math.sin(yaw))
    uphill = (-math.sin(pitch) * math.cos(yaw), math.cos(pitch), -math.sin(pitch) * math.sin(yaw))
    return outward, uphill, cross(outward, uphill)


def seated_roll(corners: tuple[Vec, Vec, Vec], centre: Vec, uphill: Vec, across: Vec) -> float:
    """The spin that fits an equilateral panel into a face as squarely as it can.

    Each of the panel's corners wants to point at a strut midpoint, but the
    three midpoints of a 3V face are not 120 degrees apart -- the struts differ
    in length by about 18 per cent -- so no orientation hits all three. Aiming at
    any single midpoint is arbitrary and misses the other two; averaging the
    three under the panel's own 120-degree symmetry splits the difference and,
    unlike picking one, does not depend on the order the subdivision listed the
    face's hubs in.

    The averaging is done on tripled angles, which is what makes three bearings
    a third of a turn apart agree instead of cancelling.

    Comes back in [-60, 60). The five-fold symmetric faces average out to exactly
    half a turn, where atan2 answers +180 or -180 depending on which side of zero
    the rounding falls, and a third of that is the difference between corner 0
    landing on one corner of the panel or the next one round. Left alone, a
    regeneration -- or a change of FIXTURE_YAW_DEGREES -- could quietly relabel
    those triangles' A/B/C strips and invalidate their calibration. So the tie is
    pinned to one side.
    """
    sin_total = cos_total = 0.0
    for index in range(3):
        toward = subtract(midpoint(corners[index], corners[(index + 1) % 3]), centre)
        bearing = 3.0 * math.atan2(dot(toward, across), dot(toward, uphill))
        sin_total += math.sin(bearing)
        cos_total += math.cos(bearing)
    roll = math.degrees(math.atan2(sin_total, cos_total)) / 3.0
    return roll - 120.0 if roll >= 60.0 - BEARING_TOLERANCE else roll


def triangle_transform(number: int) -> dict:
    """The complete placement of one LED triangle: where it is and how it lies.

    Centre in metres -- the centroid of the face, which lies in the face's own
    plane -- measured from the centre of the base ring, the datum the dome model
    OBJ uses. Yaw and pitch in degrees aim the panel's outward normal along that
    plane's normal, seating the panel flat in the frame. Roll in degrees is the
    panel's spin about that normal, measured from uphill; it is the only free
    angle once a panel is seated, and it comes back in [-60, 60) -- one third of
    a turn, the triangle's own symmetry.

    The face is worked out around the sphere centre, where the geodesic is
    defined, then turned by FIXTURE_YAW_DEGREES and lifted onto the base datum.
    Turning the face itself, rather than patching the angles afterwards, is what
    keeps pitch and roll honest: a spin about the world's vertical axis leaves
    both untouched, and doing it this way makes that fall out rather than having
    to be argued.
    """
    corners = tuple(
        instance_yawed(scale(hub, DOME_RADIUS_M), FIXTURE_YAW_DEGREES)
        for hub in dome_faces()[number]
    )
    centre = centroid(corners)
    normal = face_normal(corners)
    yaw = azimuth_degrees(normal)
    pitch = elevation_degrees(normal)
    _, uphill, across = panel_frame(yaw, pitch)
    return {
        "x": centre[0],
        "y": centre[1] + sphere_centre_above_base_m(),
        "z": centre[2],
        "yaw_degrees": yaw,
        "pitch_degrees": pitch,
        "roll_degrees": seated_roll(corners, centre, uphill, across),
    }


def triangle_corners(transform: dict) -> list[Vec]:
    """A pose expanded into three corners, in physical strip order.

    Corner 0 sits at the pose's roll angle from uphill and the other two follow
    120 degrees apart, counter-clockwise seen from outside the dome.
    """
    centre = (transform["x"], transform["y"], transform["z"])
    _, uphill, across = panel_frame(transform["yaw_degrees"], transform["pitch_degrees"])
    corners = []
    for step in range(3):
        angle = math.radians(transform["roll_degrees"] + 120.0 * step)
        corners.append(
            add(
                centre,
                scale(
                    add(scale(uphill, math.cos(angle)), scale(across, math.sin(angle))),
                    CORNER_RADIUS_M,
                ),
            )
        )
    return corners


def strip_run(corners: list[Vec], index: int) -> tuple[Vec, Vec]:
    """One edge's first pixel and its direction, from the corners it spans."""
    corner, end = corners[index], corners[(index + 1) % 3]
    direction = normalize(subtract(end, corner))
    return add(corner, scale(direction, LED_SPACING_M / 2.0)), direction


def wiring_order() -> list[tuple[int, int, int]]:
    """(triangle, harness, position) in the order the pixels are laid out."""
    return [
        (triangle, harness, position)
        for harness in sorted(HARNESS_TRIANGLES)
        for position, triangle in enumerate(HARNESS_TRIANGLES[harness], start=1)
    ]


def validate_module() -> None:
    """The wired triangles must be one whole fifth of the dome, tiling it.

    Five copies of the module, yawed MODULE_YAW_DEGREES apart, have to land on
    all EXPECTED_PANELS faces exactly once. That is the guarantee that makes a
    single fixture placed five times cover the dome, and it is cheap enough to
    check every run rather than trust the triangle numbers.
    """
    panels = dome_faces()
    numbers = [triangle for triangle, _, _ in wiring_order()]
    if len(numbers) != len(set(numbers)):
        raise ValueError("A triangle is wired to more than one harness position")
    if len(numbers) != PANELS_PER_MODULE:
        raise ValueError(f"Module wires {len(numbers)} triangles, expected {PANELS_PER_MODULE}")
    unknown = sorted(set(numbers) - set(panels))
    if unknown:
        raise ValueError(f"Triangles are not on the dome: {unknown}")

    centres = {number: centroid(corners) for number, corners in panels.items()}
    covered = set()
    for copy in range(MODULES_PER_DOME):
        for number in numbers:
            spun = yaw_rotated(centres[number], MODULE_YAW_DEGREES * copy)
            landed = min(centres, key=lambda n: math.dist(spun, centres[n]))
            if math.dist(spun, centres[landed]) > RING_TOLERANCE:
                raise ValueError(f"Triangle {number} does not land on a face when yawed")
            covered.add(landed)
    if len(covered) != EXPECTED_PANELS:
        raise ValueError(
            f"{MODULES_PER_DOME} modules cover {len(covered)} of {EXPECTED_PANELS} faces"
        )


# ---------------------------------------------------------------------------
# Fixture parameters
# ---------------------------------------------------------------------------

def rotation_parameter(triangle: int) -> str:
    return f"t{triangle:02d}rot"


def reverse_parameter(triangle: int) -> str:
    return f"t{triangle:02d}rev"


def universe_parameter(harness: int) -> str:
    return f"h{harness}univ"


def channel_parameter(harness: int) -> str:
    return f"h{harness}chan"


def fixture_parameters() -> dict:
    parameters = {
        "proto": {
            "type": "string",
            "default": DEFAULT_PROTOCOL,
            "options": PROTOCOL_OPTIONS,
            "label": "Protocol",
            "description": "Output protocol. Both options address as universe + channel.",
        },
        "ip": {
            "type": "string",
            "default": DEFAULT_IP,
            "label": "Controller IP",
            "description": "Static IP of this module's pixel controller",
        },
    }

    for harness in sorted(HARNESS_TRIANGLES):
        parameters[universe_parameter(harness)] = {
            "type": "int",
            "default": DEFAULT_HARNESS_UNIVERSE[harness],
            "min": 0,
            "max": MAX_UNIVERSE,
            "label": f"H{harness} universe",
            "description": f"Start universe for harness {harness}",
        }
    for harness in sorted(HARNESS_TRIANGLES):
        parameters[channel_parameter(harness)] = {
            "type": "int",
            "default": DEFAULT_HARNESS_CHANNEL[harness],
            "min": 0,
            "max": MAX_CHANNEL,
            "label": f"H{harness} channel",
            "description": f"Start channel for harness {harness}, within its start universe",
        }

    for triangle, harness, position in wiring_order():
        parameters[rotation_parameter(triangle)] = {
            "type": "int",
            "default": DEFAULT_ROTATION,
            "min": 0,
            "max": TRIANGLE_ROTATIONS - 1,
            "label": f"T{triangle} rotation",
            "description": (
                f"H{harness} P{position} triangle {triangle}: which corner the run "
                "of LEDs starts at, in thirds of a turn (0, 1, 2)"
            ),
        }
        parameters[reverse_parameter(triangle)] = {
            "type": "boolean",
            "default": DEFAULT_REVERSED,
            "label": f"T{triangle} reverse",
            "description": (
                f"H{harness} P{position} triangle {triangle}: feed the run from its "
                "far end, so it walks the triangle the other way round"
            ),
        }
    return parameters


# ---------------------------------------------------------------------------
# Components and outputs
# ---------------------------------------------------------------------------

def strip_components() -> list[dict]:
    """Three edges per triangle, walked in wiring order.

    Physical order only -- a strip component cannot bend, so one triangle's
    continuous run of LEDs is emitted as its three straight edges. Where the run
    actually starts, and which way round it goes, are the triangle's rotation
    and reverse parameters, applied in the output segments.
    """
    components = []
    for triangle, harness, position in wiring_order():
        corners = triangle_corners(triangle_transform(triangle))
        for index, name in enumerate(EDGE_NAMES):
            start, direction = strip_run(corners, index)
            components.append(
                {
                    "type": "strip",
                    "label": f"H{harness} P{position} · T{triangle} · edge {name}",
                    "tags": [
                        "triangle",
                        f"harness{harness}",
                        f"triangle{triangle}",
                        f"edge{name}",
                    ],
                    "meta": {
                        "harness": harness,
                        "position": position,
                        "triangle": triangle,
                        "edge": name,
                    },
                    "x": rounded(start[0], 5),
                    "y": rounded(start[1], 5),
                    "z": rounded(start[2], 5),
                    "direction": {
                        "x": rounded(direction[0], 6),
                        "y": rounded(direction[1], 6),
                        "z": rounded(direction[2], 6),
                    },
                    "spacing": round(LED_SPACING_M, 6),
                    "numPoints": LEDS_PER_EDGE,
                }
            )
    return components


def segment_start(triangle: int, base: int, slot: int) -> str:
    """Pixel index the given third of a triangle's run starts on.

    The run enters at corner `rotation` and walks the edges from there, so the
    slot-th third of the data lands on edge (rotation + slot). Fed from the far
    end it walks the same loop backwards, hitting the edges in the opposite
    order, which is (rotation + 2 - slot); each of those thirds is then itself
    reversed, which the segment's own reverse flag handles.

    Returned as an LXF expression, fully parenthesised: the parser matches a
    ternary's '?' to the *last* ':' in the string, so bare nesting binds wrong.
    """
    rotation, reverse = rotation_parameter(triangle), reverse_parameter(triangle)
    forward = [base + LEDS_PER_EDGE * ((turn + slot) % 3) for turn in range(TRIANGLE_ROTATIONS)]
    backward = [base + LEDS_PER_EDGE * ((turn + 2 - slot) % 3) for turn in range(TRIANGLE_ROTATIONS)]

    def by_rotation(starts: list[int]) -> str:
        expression = str(starts[-1])
        for turn in reversed(range(len(starts) - 1)):
            expression = f"((${rotation} == {turn}) ? {starts[turn]} : {expression})"
        return expression

    return f"((${reverse}) ? {by_rotation(backward)} : {by_rotation(forward)})"


def artnet_outputs() -> list[dict]:
    """One output per harness: its pixels, in the order the harness wires them.

    Chromatik walks the segments in order from the harness's start universe and
    channel, rolling into the next universe when one fills, so a harness needs a
    single output no matter where it starts.
    """
    order = wiring_order()
    outputs = []
    for harness in sorted(HARNESS_TRIANGLES):
        segments = []
        for index, (triangle, owner, _) in enumerate(order):
            if owner != harness:
                continue
            base = index * LEDS_PER_TRIANGLE
            for slot in range(EDGES_PER_TRIANGLE):
                segments.append(
                    {
                        "start": segment_start(triangle, base, slot),
                        "num": LEDS_PER_EDGE,
                        "reverse": f"${reverse_parameter(triangle)}",
                    }
                )
        outputs.append(
            {
                "protocol": "$proto",
                "host": "$ip",
                "byteOrder": BYTE_ORDER,
                "universe": f"${universe_parameter(harness)}",
                "channel": f"${channel_parameter(harness)}",
                "segments": segments,
            }
        )
    return outputs


def build_fixture() -> dict:
    validate_module()
    components = strip_components()
    return {
        "label": FIXTURE_LABEL,
        "tags": FIXTURE_TAGS,
        "parameters": fixture_parameters(),
        "meta": {
            "coordinate_system": COORDINATE_SYSTEM,
            "dome_radius_m": DOME_RADIUS_M,
            "frequency": FREQUENCY,
            "hub_rings_kept": HUB_RINGS_KEPT,
            "triangle_edge_m": TRIANGLE_EDGE_M,
            "leds_per_edge": LEDS_PER_EDGE,
            "leds_per_triangle": LEDS_PER_TRIANGLE,
            "triangles": len(wiring_order()),
            "harness_sizes": ", ".join(
                str(len(HARNESS_TRIANGLES[harness])) for harness in sorted(HARNESS_TRIANGLES)
            ),
            "total_edges": len(components),
            "total_pixels": len(components) * LEDS_PER_EDGE,
            "modules_per_dome": MODULES_PER_DOME,
            "module_yaw_degrees": MODULE_YAW_DEGREES,
            "corners_face": "strut-midpoints",
            "wiring": "one continuous strip per triangle; rotation 0-2 thirds of a turn, plus reverse",
            "panels_seated": "in each face's own plane",
            "sphere_centre_above_base_m": round(sphere_centre_above_base_m(), 6),
            "numbering": "ring by ring from the apex, clockwise from twelve o'clock seen from above",
            "datum": "matches Fixtures/v3_dome_model.obj -- place this fixture with the same transform as that mesh",
            "baked_yaw_degrees": FIXTURE_YAW_DEGREES,
        },
        "components": components,
        "outputs": artnet_outputs(),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate the V3 dome quintile module fixture."
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=OUTPUT_PATH,
        help=f"Fixture output path (default: {OUTPUT_PATH}).",
    )
    parser.add_argument(
        "--print-transforms",
        action="store_true",
        help="Print each wired triangle's pose instead of writing the fixture.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.print_transforms:
        validate_module()
        for triangle, harness, position in wiring_order():
            pose = triangle_transform(triangle)
            print(
                f"T{triangle:<3d} H{harness} P{position}  "
                f"x={pose['x']:+.4f} y={pose['y']:+.4f} z={pose['z']:+.4f}  "
                f"yaw={pose['yaw_degrees']:8.3f} pitch={pose['pitch_degrees']:7.3f} "
                f"roll={pose['roll_degrees']:8.3f}"
            )
        return

    fixture = build_fixture()
    output_path = args.output.resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(fixture, indent=2) + "\n", encoding="utf-8")
    print(
        f"Wrote {output_path} "
        f"({fixture['meta']['triangles']} triangles, "
        f"{fixture['meta']['total_edges']} edges, "
        f"{fixture['meta']['total_pixels']} pixels, "
        f"{len(fixture['parameters'])} parameters, "
        f"{len(fixture['outputs'])} outputs)"
    )


if __name__ == "__main__":
    main()
