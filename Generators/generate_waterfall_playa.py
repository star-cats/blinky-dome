#!/usr/bin/env python3
"""
Generate the Playa waterfall fixture.

A flat curtain of vertical LED strips, nothing like the old back-of-dome
Waterfall sheet: 9 groups of 4 strips, each group repeating the physical
pattern 315 / 360 / 315 / 360 pixels. Both strip lengths run at the same
60 px/m pitch, so 315 px is 5.25 m and 360 px is 6 m of actual LED.

Coordinates are metres in a Y-up right-handed system, like every other camp
fixture (the projects place them at scale 10). The 36 strips are spaced 5
inches apart along X and centred on x = 0, so the fixture's origin sits in
the middle of the curtain and moving it is a single X translate. Strips are
built from the bottom upward, which is how they are wired in real life, so
pixel 0 of every strip is its lowest LED.

Addressing follows Chromatik's universe roll-over: 3 channels per pixel, 170
pixels (510 channels) per universe, one continuous channel space walking the
ports in order from universe 1 channel 0. That reproduces waterfall-calibration.md
exactly -- see check_calibration(). The generated numbers are only the
defaults; every strip's universe and channel is a parameter, so the whole map
can be re-cut in the UI without regenerating.

Per-strip vertical trim moves the strip's geometry up or down, and one
top-level reverse flips the data direction of the entire waterfall.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

OUTPUT_FILENAME = "waterfall_playa.lxf"
FIXTURES_DIR = Path(__file__).resolve().parents[1] / "Fixtures"
OUTPUT_PATH = FIXTURES_DIR / OUTPUT_FILENAME

FIXTURE_LABEL = "Waterfall Playa"
FIXTURE_TAGS = ["waterfall", "playa", "curtain"]

IN_TO_M = 0.0254

# Physical build
GROUPS = 9
GROUP_PATTERN = (315, 360, 315, 360)  # pixels per strip, in port order
PIXELS_PER_METRE = 60.0
DEFAULT_STRIP_SPACING_IN = 5.0
MAX_STRIP_SPACING_IN = 120.0

# Addressing
CHANNELS_PER_PIXEL = 3
CHANNELS_PER_UNIVERSE = 510  # Chromatik never straddles a universe mid-pixel
PIXELS_PER_UNIVERSE = CHANNELS_PER_UNIVERSE // CHANNELS_PER_PIXEL  # 170
FIRST_UNIVERSE = 1

DEFAULT_PROTOCOL = "artnet"
PROTOCOL_OPTIONS = ["artnet", "sacn"]
DEFAULT_IP = "192.168.44.50"
BYTE_ORDER = "rgb"
MAX_UNIVERSE = 32767
MAX_CHANNEL = 511

# Trim range, metres of travel either way on a single strip
MAX_TRIM_M = 5.0

ALIGNMENTS = ("bottom", "top", "centre")
DEFAULT_ALIGNMENT = "top"


# ---------------------------------------------------------------------------
# Layout
# ---------------------------------------------------------------------------

def strip_pixels(port: int) -> int:
    """Pixel count for a 1-based port, cycling the 315/360/315/360 pattern."""
    return GROUP_PATTERN[(port - 1) % len(GROUP_PATTERN)]


def ports() -> list[int]:
    return list(range(1, GROUPS * len(GROUP_PATTERN) + 1))


def strip_length_m(port: int) -> float:
    """Length of tape. 315 px -> 5.25 m, 360 px -> 6 m, at 60 px/m."""
    return strip_pixels(port) / PIXELS_PER_METRE


def led_span_m(port: int) -> float:
    """First LED centre to last. One pitch shorter than the tape itself."""
    return (strip_pixels(port) - 1) / PIXELS_PER_METRE


def group_of(port: int) -> int:
    return (port - 1) // len(GROUP_PATTERN) + 1


def position_in_group(port: int) -> int:
    return (port - 1) % len(GROUP_PATTERN) + 1


def strip_offset(port: int) -> float:
    """Strip's position in spacing-units from the centre of the curtain.

    36 strips give half-unit offsets, -17.5 to +17.5, so the curtain stays
    centred on x = 0 at any spacing.
    """
    return (port - 1) - (len(ports()) - 1) / 2.0


def strip_x_expression(port: int) -> str:
    """X as an LXF expression, so the spacing parameter re-lays the curtain live."""
    return f"({round(strip_offset(port) * IN_TO_M, 6)} * $spacing)"


def strip_x_m(port: int, spacing_in: float = DEFAULT_STRIP_SPACING_IN) -> float:
    return strip_offset(port) * spacing_in * IN_TO_M


def strip_base_y_m(port: int, alignment: str) -> float:
    """Y of a strip's lowest LED before its trim is applied.

    The two strip lengths differ by 0.75 m, so they can only be flush at one
    end. Bottom-aligned puts every strip's first LED on y = 0.
    """
    longest = max(strip_length_m(p) for p in ports())
    slack = longest - strip_length_m(port)
    if alignment == "bottom":
        return 0.0
    if alignment == "top":
        return slack
    if alignment == "centre":
        return slack / 2.0
    raise ValueError(f"Unknown alignment: {alignment}")


def calibration() -> list[dict]:
    """Default universe/channel per port, walking one continuous channel space."""
    rows = []
    pixels_so_far = 0
    for port in ports():
        pixels = strip_pixels(port)
        start_channel = pixels_so_far * CHANNELS_PER_PIXEL
        rows.append(
            {
                "port": port,
                "pixels": pixels,
                "universe": start_channel // CHANNELS_PER_UNIVERSE + FIRST_UNIVERSE,
                "channel": start_channel % CHANNELS_PER_UNIVERSE,
                "point_index": pixels_so_far,
            }
        )
        pixels_so_far += pixels
    return rows


def check_calibration() -> None:
    """The pitch and the addressing rule both have to hold before we emit."""
    for port in ports():
        pixels, length = strip_pixels(port), strip_length_m(port)
        expected = {315: 5.25, 360: 6.0}[pixels]
        if abs(length - expected) > 1e-9:
            raise SystemExit(
                f"Port {port}: {pixels} px at {PIXELS_PER_METRE} px/m is "
                f"{length} m, expected {expected} m"
            )

    for row in calibration():
        channel = (row["universe"] - FIRST_UNIVERSE) * CHANNELS_PER_UNIVERSE + row["channel"]
        if channel != row["point_index"] * CHANNELS_PER_PIXEL:
            raise SystemExit(f"Port {row['port']}: universe/channel is not continuous")
        if row["channel"] > MAX_CHANNEL:
            raise SystemExit(f"Port {row['port']}: channel {row['channel']} out of range")


# ---------------------------------------------------------------------------
# Fixture parameters
# ---------------------------------------------------------------------------

def group_letter(port: int) -> str:
    """Groups are lettered A, B, C ... in port order."""
    return chr(ord("A") + group_of(port) - 1)


def strip_name(port: int) -> str:
    """A1 .. I4 -- group letter then position within the group."""
    return f"{group_letter(port)}{position_in_group(port)}"


def universe_parameter(port: int) -> str:
    return f"{strip_name(port).lower()}univ"


def channel_parameter(port: int) -> str:
    return f"{strip_name(port).lower()}chan"


def trim_parameter(port: int) -> str:
    return f"{strip_name(port).lower()}trim"


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
            "description": "Static IP of the waterfall's pixel controller -- one for all 36 strips",
        },
        "spacing": {
            "type": "float",
            "default": DEFAULT_STRIP_SPACING_IN,
            "min": 0.0,
            "max": MAX_STRIP_SPACING_IN,
            "label": "Strip spacing",
            "description": (
                "Horizontal distance between adjacent strips, in inches. The curtain "
                "stays centred on the fixture's origin as this opens and closes"
            ),
        },
        "rev": {
            "type": "boolean",
            "default": False,
            "label": "Reverse waterfall",
            "description": (
                "Flip the data direction of every strip at once. The strips are wired "
                "from the bottom upward, so leave this off unless the whole curtain "
                "runs the wrong way"
            ),
        },
    }

    rows = {row["port"]: row for row in calibration()}
    all_ports = ports()
    per_group = len(GROUP_PATTERN)

    # Grouped and banded: a whole group's universes, then its channels, then its
    # trims, before moving on to the next group. That is the order they get read
    # off the controller and the order they get dialled in.
    for first in range(0, len(all_ports), per_group):
        group = all_ports[first:first + per_group]
        for name, build in (
            (universe_parameter, universe_definition),
            (channel_parameter, channel_definition),
            (trim_parameter, trim_definition),
        ):
            for port in group:
                parameters[name(port)] = build(port, rows[port])
    return parameters


def _where(port: int) -> str:
    return f"{strip_name(port)}, port {port}, {strip_pixels(port)} px"


def universe_definition(port: int, row: dict) -> dict:
    return {
        "type": "int",
        "default": row["universe"],
        "min": 0,
        "max": MAX_UNIVERSE,
        "label": f"{strip_name(port)} univ",
        "description": f"Start universe for {_where(port)}",
    }


def channel_definition(port: int, row: dict) -> dict:
    return {
        "type": "int",
        "default": row["channel"],
        "min": 0,
        "max": MAX_CHANNEL,
        "label": f"{strip_name(port)} chan",
        "description": f"Start channel for {_where(port)}, within its start universe",
    }


def trim_definition(port: int, row: dict) -> dict:
    return {
        "type": "float",
        "default": 0.0,
        "min": -MAX_TRIM_M,
        "max": MAX_TRIM_M,
        "label": f"{strip_name(port)} trim",
        "description": (
            f"Vertical trim for {_where(port)}, in metres. Moves the strip's geometry "
            "up (+) or down (-); does not touch its addressing"
        ),
    }


# ---------------------------------------------------------------------------
# Components and outputs
# ---------------------------------------------------------------------------

def strip_components(alignment: str) -> list[dict]:
    """One vertical strip per port, built bottom-up the way it is wired."""
    components = []
    for port in ports():
        base_y = strip_base_y_m(port, alignment)
        components.append(
            {
                "type": "strip",
                "label": f"{strip_name(port)} · port {port} · {strip_pixels(port)}px",
                "tags": [
                    "waterfall",
                    f"group{group_letter(port)}",
                    f"strip{strip_name(port)}",
                    f"px{strip_pixels(port)}",
                ],
                "meta": {
                    "port": port,
                    "group": group_letter(port),
                    "position": position_in_group(port),
                    "pixels": strip_pixels(port),
                    "length_m": round(strip_length_m(port), 6),
                    "led_span_m": round(led_span_m(port), 6),
                },
                "x": strip_x_expression(port),
                # Base height plus the strip's own trim, so the parameter moves geometry.
                "y": f"({round(base_y, 6)} + ${trim_parameter(port)})",
                "z": 0.0,
                "direction": {"x": 0.0, "y": 1.0, "z": 0.0},
                "spacing": round(1.0 / PIXELS_PER_METRE, 6),
                "numPoints": strip_pixels(port),
            }
        )
    return components


def artnet_outputs() -> list[dict]:
    """One output per strip, so every strip's universe and channel stays editable.

    Chromatik rolls a run into the next universe when one fills, so a 360 px
    strip crossing three universes still needs only its own start address.
    """
    outputs = []
    for row in calibration():
        port = row["port"]
        outputs.append(
            {
                "protocol": "$proto",
                "host": "$ip",
                "byteOrder": BYTE_ORDER,
                "universe": f"${universe_parameter(port)}",
                "channel": f"${channel_parameter(port)}",
                "segments": [
                    {
                        "start": row["point_index"],
                        "num": row["pixels"],
                        "reverse": "$rev",
                    }
                ],
            }
        )
    return outputs


def build_fixture(alignment: str) -> dict:
    check_calibration()
    components = strip_components(alignment)
    rows = calibration()
    total_pixels = sum(row["pixels"] for row in rows)
    return {
        "label": FIXTURE_LABEL,
        "tags": FIXTURE_TAGS,
        "parameters": fixture_parameters(),
        "meta": {
            "coordinate_system": (
                "Y-up right-handed, metres, origin at the centre of the curtain on "
                "the ground line; the projects place this at scale 10"
            ),
            "groups": GROUPS,
            "group_letters": ", ".join(group_letter(p) for p in ports()[::len(GROUP_PATTERN)]),
            "strips_per_group": len(GROUP_PATTERN),
            "group_pattern_px": ", ".join(str(n) for n in GROUP_PATTERN),
            "strips": len(components),
            "total_pixels": total_pixels,
            "pixels_per_metre": PIXELS_PER_METRE,
            "strip_lengths_m": "315 px = 5.25 m, 360 px = 6 m",
            "strip_spacing_in": DEFAULT_STRIP_SPACING_IN,
            "strip_spacing": "the $spacing parameter, in inches; x is an expression so it re-lays live",
            "curtain_width_m": round(
                (len(components) - 1) * DEFAULT_STRIP_SPACING_IN * IN_TO_M, 6
            ),
            "alignment": f"{alignment}-aligned; the two strip lengths differ by 0.75 m",
            "wiring": "each strip runs bottom to top; pixel 0 is the lowest LED",
            "reverse": "the single $rev parameter flips the data direction of all 36 strips",
            "trim": "each strip has its own vertical trim in metres, applied to geometry only",
            "addressing": (
                f"{CHANNELS_PER_PIXEL} channels per pixel, {PIXELS_PER_UNIVERSE} pixels "
                f"({CHANNELS_PER_UNIVERSE} channels) per universe, one continuous channel "
                f"space from universe {FIRST_UNIVERSE} channel 0"
            ),
            "calibration_source": "waterfall-calibration.md, ports 1-36",
            "universes_used": f"{rows[0]['universe']}-{rows[-1]['universe'] + (rows[-1]['pixels'] + rows[-1]['channel'] // CHANNELS_PER_PIXEL) // PIXELS_PER_UNIVERSE}",
        },
        "components": components,
        "outputs": artnet_outputs(),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate the Playa waterfall fixture.")
    parser.add_argument(
        "--output",
        type=Path,
        default=OUTPUT_PATH,
        help=f"Fixture output path (default: {OUTPUT_PATH}).",
    )
    parser.add_argument(
        "--align",
        choices=ALIGNMENTS,
        default=DEFAULT_ALIGNMENT,
        help=(
            "How the 5.25 m and 6 m strips line up vertically before trim "
            f"(default: {DEFAULT_ALIGNMENT})."
        ),
    )
    parser.add_argument(
        "--print-calibration",
        action="store_true",
        help="Print the default universe/channel map instead of writing the fixture.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    check_calibration()

    if args.print_calibration:
        print(f"{'Port':>4} {'Name':>5} {'Px':>4} {'Start ch':>9} {'Universe':>9} {'Channel':>8}")
        for row in calibration():
            print(
                f"{row['port']:>4} {strip_name(row['port']):>5} {row['pixels']:>4} "
                f"{row['point_index'] * CHANNELS_PER_PIXEL + 1:>9} "
                f"{row['universe']:>9} {row['channel']:>8}"
            )
        return

    fixture = build_fixture(args.align)
    output_path = args.output.resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(fixture, indent=2) + "\n", encoding="utf-8")
    print(
        f"Wrote {output_path} "
        f"({fixture['meta']['strips']} strips, "
        f"{fixture['meta']['total_pixels']} pixels, "
        f"{fixture['meta']['curtain_width_m']} m wide, "
        f"{args.align}-aligned)"
    )


if __name__ == "__main__":
    main()
