#!/usr/bin/env python3
"""
Generate the Waterfall fixture.

Coordinates are metres in a Y-up right-handed system. The dome base centre is
the origin, Y is height, and negative Z is back. The waterfall is a sheet of
LED strips lying on the back surface of the dome, constructed from the ground
(y = 0) up:

  1. Base: the centre span is a straight 10 ft chord across the back of the
     dome's ground circle; a 4 ft wing chord hangs off each tip, its far end
     also mating with the ground circle. That yields 4 base points.
  2. Lower spine: each base point extends an 11 ft chord up the dome surface.
     The 4 resulting points repeat the 4/10/4 ft pattern of the base.
  3. Upper spine: the same extension again with an 8 ft chord produces the
     final 4 points at the top edge of the sheet.

The 12 points define three levels. Each strip interpolates a position along
the span (arc length across the 4/10/4 polyline, identical at every level)
and runs as a polyline from its top-edge point, through the lower-spine
level, down to the base. LEDs start at the TOP and extend downward; a
start-offset shifts them further down the polyline (default 0).
"""

from __future__ import annotations

import json
import math

from constants import FIXTURES_DIR, WATERFALL

# Output
OUTPUT_PATH = FIXTURES_DIR / WATERFALL["output_file"]

# Dome parameters.
DOME_RADIUS_M = WATERFALL["dome"]["radius_m"]
DOME_GROUND_Y_M = WATERFALL["dome"]["ground_y_m"]

# Span pattern: 4 ft wing / 10 ft centre / 4 ft wing at every level.
CENTER_SPAN_M = WATERFALL["span"]["center_span_m"]
WING_SPAN_M = WATERFALL["span"]["wing_span_m"]
TOTAL_SPAN_M = WATERFALL["span"]["total_span_m"]

# Chord lengths climbing the dome surface: base -> lower level -> upper level.
LOWER_SPINE_LENGTH_M = WATERFALL["spine"]["lower_length_m"]
UPPER_SPINE_LENGTH_M = WATERFALL["spine"]["upper_length_m"]

# Strand parameters.
NUM_STRIPS = WATERFALL["strips"]["count"]
NOMINAL_STRIP_SPACING_M = WATERFALL["strips"]["nominal_spacing_m"]
START_OFFSET_M = WATERFALL["strips"]["start_offset_m"]
LONG_STRIP_LENGTH_M = WATERFALL["strips"]["long"]["length_m"]
SHORT_STRIP_LENGTH_M = WATERFALL["strips"]["short"]["length_m"]
LONG_STRIP_LEDS = WATERFALL["strips"]["long"]["leds_per_strip"]
SHORT_STRIP_LEDS = WATERFALL["strips"]["short"]["leds_per_strip"]
START_WITH_LONG_STRIP = WATERFALL["strips"]["start_with"] == "long"

# Presentation colours alternate with the physical strip lengths.
LONG_STRIP_COLOR = WATERFALL["strips"]["long"]["color"]
SHORT_STRIP_COLOR = WATERFALL["strips"]["short"]["color"]

ORIGIN = (0.0, 0.0, 0.0)
PATTERN_TOLERANCE_M = 1e-6

Vec = tuple[float, float, float]


def normalize(vector: Vec) -> Vec:
    length = math.sqrt(sum(axis * axis for axis in vector))
    if length == 0:
        raise ValueError("Cannot normalize a zero-length vector")
    return tuple(axis / length for axis in vector)


def add(a: Vec, b: Vec) -> Vec:
    return tuple(a_axis + b_axis for a_axis, b_axis in zip(a, b))


def subtract(a: Vec, b: Vec) -> Vec:
    return tuple(a_axis - b_axis for a_axis, b_axis in zip(a, b))


def scale(vector: Vec, scalar: float) -> Vec:
    return tuple(axis * scalar for axis in vector)


def dot(a: Vec, b: Vec) -> float:
    return sum(a_axis * b_axis for a_axis, b_axis in zip(a, b))


def cross(a: Vec, b: Vec) -> Vec:
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def distance(a: Vec, b: Vec) -> float:
    return math.sqrt(sum((a_axis - b_axis) ** 2 for a_axis, b_axis in zip(a, b)))


def mirror_x(point: Vec) -> Vec:
    return (-point[0], point[1], point[2])


def rounded_coord(value: float) -> float:
    rounded = round(value, 6)
    return 0 if rounded == 0 else rounded


def base_points_right() -> tuple[Vec, Vec]:
    """Centre-span tip and wing tip on the ground circle, +X (right) side.

    Angles are measured around the ground circle from the back (-Z) axis; a
    chord of length c on a circle of radius R subtends 2*asin(c / (2R)).
    """
    center_angle = math.asin((CENTER_SPAN_M / 2.0) / DOME_RADIUS_M)
    wing_angle = center_angle + 2.0 * math.asin(WING_SPAN_M / (2.0 * DOME_RADIUS_M))

    def on_ground_circle(angle: float) -> Vec:
        return (
            DOME_RADIUS_M * math.sin(angle),
            DOME_GROUND_Y_M,
            -DOME_RADIUS_M * math.cos(angle),
        )

    return on_ground_circle(center_angle), on_ground_circle(wing_angle)


def extend_center(previous: Vec, spine_length_m: float) -> Vec:
    """Extend a centre-rail point up the dome by a chord of spine_length_m.

    Keeping the centre span at 10 ft pins the new point to x = CENTER_SPAN / 2,
    so it lies on a circle in that plane; intersect with the chord-distance
    circle around the previous point and take the upward solution.
    """
    x = CENTER_SPAN_M / 2.0
    radius_sq = DOME_RADIUS_M * DOME_RADIUS_M - x * x
    y0, z0 = previous[1], previous[2]

    # Previous centre point shares the same x, so (y0, z0) also has squared
    # length radius_sq; the intersection chord line is n . p = k with n=(y0,z0).
    k = radius_sq - spine_length_m * spine_length_m / 2.0
    foot_y = y0 * k / radius_sq
    foot_z = z0 * k / radius_sq
    height = math.sqrt(radius_sq - k * k / radius_sq)
    radius = math.sqrt(radius_sq)
    tangent_y, tangent_z = -z0 / radius, y0 / radius

    candidates = [
        (x, foot_y + tangent_y * height, foot_z + tangent_z * height),
        (x, foot_y - tangent_y * height, foot_z - tangent_z * height),
    ]
    return max(candidates, key=lambda point: point[1])


def trilaterate(
    c1: Vec, r1: float, c2: Vec, r2: float, c3: Vec, r3: float
) -> tuple[Vec, Vec]:
    """Both intersection points of three spheres."""
    ex = normalize(subtract(c2, c1))
    to3 = subtract(c3, c1)
    i = dot(ex, to3)
    ey = normalize(subtract(to3, scale(ex, i)))
    ez = cross(ex, ey)
    d = distance(c1, c2)
    j = dot(ey, to3)

    x = (r1 * r1 - r2 * r2 + d * d) / (2.0 * d)
    y = (r1 * r1 - r3 * r3 + i * i + j * j) / (2.0 * j) - (i / j) * x
    z_sq = r1 * r1 - x * x - y * y
    if z_sq < 0:
        if z_sq < -1e-9:
            raise ValueError("Spheres do not intersect; geometry is infeasible")
        z_sq = 0.0
    z = math.sqrt(z_sq)

    in_plane = add(c1, add(scale(ex, x), scale(ey, y)))
    return add(in_plane, scale(ez, z)), subtract(in_plane, scale(ez, z))


def extend_wing(previous_wing: Vec, new_center: Vec, spine_length_m: float) -> Vec:
    """Extend a wing-rail point up the dome by a chord of spine_length_m.

    The new point lies on the dome surface, a spine chord from the previous
    wing point, and a wing span from the new centre point. Of the two sphere
    intersections take the outward (+X) one; the inward one tucks behind the
    centre rail.
    """
    solutions = trilaterate(
        ORIGIN, DOME_RADIUS_M,
        previous_wing, spine_length_m,
        new_center, WING_SPAN_M,
    )
    return max(solutions, key=lambda point: point[0])


def level_polyline(center_right: Vec, wing_right: Vec) -> list[Vec]:
    """Left-to-right 4-point polyline for one level: wing, centre pair, wing."""
    return [
        mirror_x(wing_right),
        mirror_x(center_right),
        center_right,
        wing_right,
    ]


def check_pattern(polyline: list[Vec], level_name: str) -> None:
    expected = (WING_SPAN_M, CENTER_SPAN_M, WING_SPAN_M)
    for (start, end), expected_m in zip(zip(polyline, polyline[1:]), expected):
        actual_m = distance(start, end)
        if abs(actual_m - expected_m) > PATTERN_TOLERANCE_M:
            raise ValueError(
                f"{level_name} level breaks the span pattern: expected "
                f"{expected_m:.6f} m, got {actual_m:.6f} m"
            )
    for point in polyline:
        radius_m = distance(ORIGIN, point)
        if abs(radius_m - DOME_RADIUS_M) > PATTERN_TOLERANCE_M:
            raise ValueError(
                f"{level_name} level point {point} is off the dome surface "
                f"(radius {radius_m:.6f} m)"
            )


def build_levels() -> tuple[list[Vec], list[Vec], list[Vec]]:
    """Return the base, lower-spine, and upper-spine polylines (12 points)."""
    base_center, base_wing = base_points_right()
    lower_center = extend_center(base_center, LOWER_SPINE_LENGTH_M)
    lower_wing = extend_wing(base_wing, lower_center, LOWER_SPINE_LENGTH_M)
    upper_center = extend_center(lower_center, UPPER_SPINE_LENGTH_M)
    upper_wing = extend_wing(lower_wing, upper_center, UPPER_SPINE_LENGTH_M)

    base = level_polyline(base_center, base_wing)
    lower = level_polyline(lower_center, lower_wing)
    upper = level_polyline(upper_center, upper_wing)

    check_pattern(base, "base")
    check_pattern(lower, "lower-spine")
    check_pattern(upper, "upper-spine")
    return base, lower, upper


def point_along_polyline(points: list[Vec], distance_along_m: float) -> Vec:
    """Walk distance_along_m from the start, extrapolating past either end."""
    remaining_m = distance_along_m
    for start, end in zip(points, points[1:]):
        segment_m = distance(start, end)
        if remaining_m <= segment_m:
            direction = normalize(subtract(end, start))
            return add(start, scale(direction, remaining_m))
        remaining_m -= segment_m
    # Past the end: continue along the final segment's direction.
    direction = normalize(subtract(points[-1], points[-2]))
    return add(points[-1], scale(direction, remaining_m))


def strip_profile(index: int) -> tuple[str, float, int, str]:
    long_strip = index % 2 == 0 if START_WITH_LONG_STRIP else index % 2 == 1
    if long_strip:
        return "long", LONG_STRIP_LENGTH_M, LONG_STRIP_LEDS, LONG_STRIP_COLOR
    return "short", SHORT_STRIP_LENGTH_M, SHORT_STRIP_LEDS, SHORT_STRIP_COLOR


def make_strip(
    index: int, base: list[Vec], lower: list[Vec], upper: list[Vec]
) -> dict:
    if NUM_STRIPS < 2:
        raise ValueError("NUM_STRIPS must be at least 2")

    # Arc length across the sheet; the 4/10/4 pattern holds at every level, so
    # the same span distance lines up vertically on all three polylines.
    span_m = TOTAL_SPAN_M * index / (NUM_STRIPS - 1)
    rail = [
        point_along_polyline(upper, span_m),
        point_along_polyline(lower, span_m),
        point_along_polyline(base, span_m),
    ]
    rail_length_m = sum(distance(a, b) for a, b in zip(rail, rail[1:]))

    length_label, strip_length_m, led_count, color = strip_profile(index)
    spacing_m = strip_length_m / (led_count - 1)

    coords = []
    for led_index in range(led_count):
        x, y, z = point_along_polyline(
            rail, START_OFFSET_M + led_index * spacing_m
        )
        coords.append(
            {
                "index": led_index,
                "x": rounded_coord(x),
                "y": rounded_coord(y),
                "z": rounded_coord(z),
            }
        )

    return {
        "type": "points",
        "label": (
            f"Waterfall Strip {index + 1:02d} "
            f"({length_label}, {strip_length_m:g}m, {led_count} LEDs)"
        ),
        "groupColor": color,
        "numPoints": led_count,
        "metadata": {
            "span_arclength_m": round(span_m, 6),
            "rail_length_m": round(rail_length_m, 6),
            "top_anchor_m": [rounded_coord(axis) for axis in rail[0]],
            "physical_length_m": strip_length_m,
            "led_spacing_m": round(spacing_m, 9),
            "leds_per_cm": round(led_count / (strip_length_m * 100), 9),
            "start_offset_m": START_OFFSET_M,
        },
        "coords": coords,
    }


def build_fixture() -> dict:
    base, lower, upper = build_levels()
    strips = [make_strip(index, base, lower, upper) for index in range(NUM_STRIPS)]
    long_count = sum(
        1 for index in range(NUM_STRIPS) if strip_profile(index)[0] == "long"
    )
    short_count = NUM_STRIPS - long_count

    def rounded_level(polyline: list[Vec]) -> list[list[float]]:
        return [[rounded_coord(axis) for axis in point] for point in polyline]

    return {
        "label": "Waterfall",
        "tags": ["waterfall", "dome", "strips"],
        "metadata": {
            "coordinate_system": WATERFALL["coordinate_system"],
            "dome_radius_m": DOME_RADIUS_M,
            "total_span_m": TOTAL_SPAN_M,
            "center_span_m": CENTER_SPAN_M,
            "wing_span_m": WING_SPAN_M,
            "lower_spine_length_m": LOWER_SPINE_LENGTH_M,
            "upper_spine_length_m": UPPER_SPINE_LENGTH_M,
            "start_offset_m": START_OFFSET_M,
            "num_strips": NUM_STRIPS,
            "long_strips": long_count,
            "short_strips": short_count,
            "long_strip_length_m": LONG_STRIP_LENGTH_M,
            "short_strip_length_m": SHORT_STRIP_LENGTH_M,
            "long_strip_leds": LONG_STRIP_LEDS,
            "short_strip_leds": SHORT_STRIP_LEDS,
            "long_strip_leds_per_cm": round(
                WATERFALL["strips"]["long"]["leds_per_cm"], 9
            ),
            "short_strip_leds_per_cm": round(
                WATERFALL["strips"]["short"]["leds_per_cm"], 9
            ),
            "actual_flat_strip_spacing_m": round(TOTAL_SPAN_M / (NUM_STRIPS - 1), 9),
            "nominal_strip_spacing_m": round(NOMINAL_STRIP_SPACING_M, 9),
            "level_points_m": {
                "base": rounded_level(base),
                "lower_spine": rounded_level(lower),
                "upper_spine": rounded_level(upper),
            },
        },
        "components": strips,
    }


def main() -> None:
    fixture = build_fixture()
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(fixture, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
