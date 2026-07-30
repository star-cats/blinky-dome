#!/usr/bin/env python3
"""Generate a simple OBJ visualization of a 3V 5/9 Kruschke-style dome.

The OBJ is meant for previewing the dome envelope, not fabrication. Every strut
is drawn as a thin square beam; viewers should render the mesh as solid gray.

The dome is a true class I (alternate) geodesic: an icosahedron oriented point
up, each face split into 9 triangles, every hub pushed out to the sphere, then
truncated to the top RINGS_KEPT hub rings. That truncation is what produces the
published figures -- 61 hubs, 165 struts, 105 triangles -- and, importantly, the
six pentagonal hubs: one at the apex and a ring of five at 26.57 degrees
elevation. Every other hub is hexagonal. Only three strut lengths exist.

Deriving the hubs from the icosahedron is the whole point: laying hubs out on
evenly spaced latitude rings looks similar but is not a geodesic, and gives
crossing struts and stray pentagons.

Coordinates are in metres, Y up, the base ring centred on the origin at y=0.
"""

from __future__ import annotations

import argparse
import math
from collections import Counter
from pathlib import Path

from constants import FIXTURES_DIR, IN_TO_M, V3_HARNESS


# Sized by hub-sphere radius, in metres, shared with the LED harness so the two
# describe the same dome. Sizing by a "diameter" instead is what pulled them
# apart: this dome is widest above its base, so a base or width figure implies a
# different sphere than the one the harness places LEDs on.
DEFAULT_DOME_RADIUS_M = V3_HARNESS["dome_radius_m"]
DEFAULT_STRUT_THICKNESS_IN = 1.0
DEFAULT_OUTPUT_PATH = FIXTURES_DIR / "v3_dome_model.obj"

# Class I subdivision frequency. 3V => each icosahedron face becomes 9 faces.
FREQUENCY = 3
# A point-up 3V sphere stacks its 92 hubs on 16 latitude rings. Keeping the top
# ten cuts just below the equator -- the 5/9 Kruschke truncation.
RINGS_KEPT = 10

# What the truncation must produce; asserted after the fact so a bad edit here
# fails loudly instead of quietly shipping a malformed dome.
EXPECTED_HUBS = 61
EXPECTED_STRUTS = 165
EXPECTED_TRIANGLES = 105
EXPECTED_PENTAGONS = 6

Vec = tuple[float, float, float]
Face = tuple[int, int, int, int]
Edge = tuple[int, int]


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


def length(vector: Vec) -> float:
    return math.sqrt(dot(vector, vector))


def normalize(vector: Vec) -> Vec:
    magnitude = length(vector)
    if magnitude == 0:
        raise ValueError("Cannot normalize a zero-length vector")
    return scale(vector, 1.0 / magnitude)


def rounded_coord(value: float) -> float:
    rounded = round(value, 6)
    return 0.0 if rounded == 0 else rounded


def icosahedron() -> tuple[list[Vec], list[tuple[int, int, int]]]:
    """Unit icosahedron with a vertex at the north pole.

    Point-up is what puts a pentagon at the apex; the other orientations bury
    the apex hub in the middle of a face and the dome loses its centre node.
    """
    # The two staggered rings of five sit at +/-atan(1/2) latitude.
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

    faces: list[tuple[int, int, int]] = []
    for index in range(5):
        north, east = 1 + index, 1 + (index + 1) % 5
        south, south_east = 6 + index, 6 + (index + 1) % 5
        faces.append((0, north, east))
        faces.append((north, south, east))
        faces.append((south, south_east, east))
        faces.append((11, south_east, south))
    return vertices, faces


def subdivide(
    vertices: list[Vec], faces: list[tuple[int, int, int]], frequency: int
) -> tuple[list[Vec], list[tuple[int, int, int]]]:
    """Class I (alternate) subdivision: split each face into frequency^2 faces.

    Every generated point is normalized onto the unit sphere, and points shared
    between faces are welded by rounded coordinate so hubs are not duplicated
    along the seams.
    """
    welded: dict[tuple[float, ...], int] = {}
    sphere: list[Vec] = []

    def hub(point: Vec) -> int:
        point = normalize(point)
        key = tuple(round(axis, 9) for axis in point)
        if key not in welded:
            welded[key] = len(sphere)
            sphere.append(point)
        return welded[key]

    triangles: list[tuple[int, int, int]] = []
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
                triangles.append((lattice[(i, j)], lattice[(i + 1, j)], lattice[(i, j + 1)]))
                if i + j < frequency - 1:
                    triangles.append(
                        (lattice[(i + 1, j)], lattice[(i + 1, j + 1)], lattice[(i, j + 1)])
                    )
    return sphere, triangles


def truncate(
    sphere: list[Vec], triangles: list[tuple[int, int, int]], rings_kept: int
) -> tuple[list[Vec], list[tuple[int, int, int]]]:
    """Keep the triangles lying wholly within the top `rings_kept` hub rings.

    Cutting by whole triangles (rather than by hub) is what leaves a clean
    boundary: no strut is left dangling with its far hub removed.
    """
    ring_heights = sorted({round(point[1], 9) for point in sphere}, reverse=True)
    if rings_kept > len(ring_heights):
        raise ValueError(f"Only {len(ring_heights)} rings exist, asked for {rings_kept}")
    cut_height = ring_heights[rings_kept - 1] - 1e-9

    kept = [
        triangle
        for triangle in triangles
        if all(sphere[index][1] >= cut_height for index in triangle)
    ]

    # Re-index so the OBJ carries only the hubs the dome actually uses.
    surviving = sorted({index for triangle in kept for index in triangle})
    remap = {old: new for new, old in enumerate(surviving)}
    return (
        [sphere[index] for index in surviving],
        [tuple(remap[index] for index in triangle) for triangle in kept],
    )


def edges_of(triangles: list[tuple[int, int, int]]) -> list[Edge]:
    edges = {
        (min(a, b), max(a, b))
        for triangle in triangles
        for a, b in ((triangle[0], triangle[1]), (triangle[1], triangle[2]), (triangle[2], triangle[0]))
    }
    return sorted(edges)


def valences(hub_count: int, edges: list[Edge]) -> list[int]:
    counts = [0] * hub_count
    for a, b in edges:
        counts[a] += 1
        counts[b] += 1
    return counts


def centre_height_fraction() -> float:
    """How far the sphere centre sits above the base ring, as a fraction of R.

    The truncation cuts below the equator, so the dome's lowest hubs are under
    its sphere centre. Anything that has to align this mesh against geometry
    built around the sphere centre -- the LED harness, the model assembly --
    needs this number, so it is derived here rather than measured off the .obj.
    """
    sphere, triangles = subdivide(*icosahedron(), FREQUENCY)
    hubs, _ = truncate(sphere, triangles, RINGS_KEPT)
    return -min(y for _, y, _ in hubs)


def centre_height_m(dome_radius_m: float = DEFAULT_DOME_RADIUS_M) -> float:
    """Height of the sphere centre above the base ring, in metres."""
    return centre_height_fraction() * dome_radius_m


def place(hubs: list[Vec], dome_radius_m: float) -> list[Vec]:
    """Scale the unit dome to dome_radius_m and sit its lowest hub on y=0.

    The hubs come off a unit sphere, so the radius is the scale factor. Sizing
    on the sphere -- not on a base or width measurement -- is what keeps this
    mesh and the LED harness on the same shell.
    """
    lowest = min(y for _, y, _ in hubs) * dome_radius_m
    return [
        (x * dome_radius_m, y * dome_radius_m - lowest, z * dome_radius_m)
        for x, y, z in hubs
    ]


def build_dome(dome_radius_m: float) -> tuple[list[Vec], list[Edge], int]:
    """Hubs (in metres, based at y=0), struts, and the triangle count."""
    sphere, triangles = subdivide(*icosahedron(), FREQUENCY)
    hubs, triangles = truncate(sphere, triangles, RINGS_KEPT)
    struts = edges_of(triangles)

    pentagons = sum(1 for valence in valences(len(hubs), struts) if valence == 5)
    actual = (len(hubs), len(struts), len(triangles), pentagons)
    expected = (EXPECTED_HUBS, EXPECTED_STRUTS, EXPECTED_TRIANGLES, EXPECTED_PENTAGONS)
    if actual != expected:
        raise ValueError(
            "Malformed dome: got %d hubs / %d struts / %d triangles / %d pentagons, "
            "expected %d / %d / %d / %d" % (*actual, *expected)
        )

    return place(hubs, dome_radius_m), struts, len(triangles)


def strut_classes(hubs: list[Vec], struts: list[Edge]) -> list[tuple[str, float, int]]:
    """Group struts into the A/B/C length classes, shortest first."""
    lengths = Counter(
        round(length(subtract(hubs[b], hubs[a])), 4) for a, b in struts
    )
    return [
        (chr(ord("A") + index), strut_length, count)
        for index, (strut_length, count) in enumerate(sorted(lengths.items()))
    ]


def beam_mesh(start: Vec, end: Vec, thickness_m: float) -> tuple[list[Vec], list[Face]]:
    direction = normalize(subtract(end, start))
    up = (0.0, 1.0, 0.0)
    if abs(dot(direction, up)) > 0.96:
        up = (1.0, 0.0, 0.0)

    side_a = normalize(cross(direction, up))
    side_b = normalize(cross(direction, side_a))
    half = thickness_m / 2.0
    offsets = [
        add(scale(side_a, half), scale(side_b, half)),
        add(scale(side_a, -half), scale(side_b, half)),
        add(scale(side_a, -half), scale(side_b, -half)),
        add(scale(side_a, half), scale(side_b, -half)),
    ]

    vertices = [add(start, offset) for offset in offsets] + [add(end, offset) for offset in offsets]
    faces: list[Face] = [
        (1, 2, 3, 4),
        (5, 8, 7, 6),
        (1, 5, 6, 2),
        (2, 6, 7, 3),
        (3, 7, 8, 4),
        (4, 8, 5, 1),
    ]
    return vertices, faces


def build_obj(dome_radius_m: float, strut_thickness_in: float) -> str:
    thickness_m = strut_thickness_in * IN_TO_M
    if dome_radius_m <= 0:
        raise ValueError("dome radius must be positive")
    if strut_thickness_in <= 0:
        raise ValueError("strut thickness must be positive")

    hubs, struts, triangle_count = build_dome(dome_radius_m)
    classes = strut_classes(hubs, struts)

    obj_vertices: list[Vec] = []
    obj_faces: list[Face] = []
    for start_index, end_index in struts:
        beam_vertices, beam_faces = beam_mesh(
            hubs[start_index], hubs[end_index], thickness_m
        )
        vertex_offset = len(obj_vertices)
        obj_vertices.extend(beam_vertices)
        obj_faces.extend(
            tuple(vertex_offset + face_index for face_index in face)
            for face in beam_faces
        )

    lines = [
        "# 3V 5/9 Kruschke-style geodesic dome visualization",
        f"# dome_radius_m {dome_radius_m}",
        f"# dome_height_m {round(max(y for _, y, _ in hubs), 6)}",
        f"# sphere_centre_above_base_m {round(centre_height_m(dome_radius_m), 6)}",
        f"# strut_thickness_in {strut_thickness_in}",
        f"# hubs {len(hubs)}",
        f"# struts {len(struts)}",
        f"# triangles {triangle_count}",
        "# strut_counts "
        + " ".join(f"{name}={count}@{value:.4f}m" for name, value, count in classes),
        f"# pentagonal_hubs {EXPECTED_PENTAGONS}",
        "# material solid_gray 0.55 0.55 0.55",
        "o V3_5_9_Kruschke_Dome",
    ]
    for vertex in obj_vertices:
        lines.append(
            "v "
            + " ".join(f"{rounded_coord(axis):.6f}".rstrip("0").rstrip(".") for axis in vertex)
        )
    for face in obj_faces:
        lines.append("f " + " ".join(str(index) for index in face))
    return "\n".join(lines) + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a solid gray OBJ of a 3V 5/9 Kruschke-style dome."
    )
    parser.add_argument(
        "--dome-radius-m",
        "--dome-radius",
        type=float,
        default=DEFAULT_DOME_RADIUS_M,
        help=f"Hub-sphere radius in metres (default: {DEFAULT_DOME_RADIUS_M}).",
    )
    parser.add_argument(
        "--strut-thickness-in",
        "--strut-thickness",
        type=float,
        default=DEFAULT_STRUT_THICKNESS_IN,
        help="Square beam thickness in inches (default: 1).",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_PATH,
        help=f"OBJ output path (default: {DEFAULT_OUTPUT_PATH}).",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    output_path = args.output.resolve()

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        build_obj(args.dome_radius_m, args.strut_thickness_in),
        encoding="utf-8",
    )
    print(f"Wrote {output_path}")


if __name__ == "__main__":
    main()
