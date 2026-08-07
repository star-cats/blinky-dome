#!/usr/bin/env python3
"""Generate the 6x6 Viking bind-rune sprite sheet.

The glyphs are deterministic combinations of Viking-Age Younger Futhark branch
forms. Each bind has one shared vertical stave: the first rune contributes the
branches on its left and the second contributes the branches on its right. This
matches the usual historical ligature construction and prevents the accidental
parallel staves produced by overlaying two complete rune drawings.

Every glyph is rendered on its own supersampled tile, measured from its actual
stroke geometry, and centered before being pasted into the atlas. Tiles are
clipped to their sectors and retain a validated gutter, so antialiasing can
never bleed into an adjacent sprite.

Output: Images/Glyphs/VikingRunes.png (512x512 RGBA)

Run:
    python3 Generators/generate_viking_runes.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


OUTPUT_PATH = (
    Path(__file__).resolve().parents[1] / "Images" / "Glyphs" / "VikingRunes.png"
)

ATLAS_SIZE = 512
COLUMNS = 6
ROWS = 6
SUPERSAMPLE = 4
CELL_GUTTER = 8
# Lanczos can extend faint alpha roughly two pixels beyond the drawn geometry.
FILTER_MARGIN = 2
STROKE_WIDTH = 5
CENTER_TOLERANCE = 1.0
WHITE = (255, 255, 255, 255)
TRANSPARENT = (0, 0, 0, 0)

Point = tuple[float, float]
Segment = tuple[Point, Point]


# Coordinates are normalized, Y-down. These are branch-only forms drawn on the
# right side of a stave. The first component of a bind is mirrored to the left;
# the second stays on the right; SHARED_STAVE is drawn exactly once.
SHARED_STAVE: Segment = ((0.0, -1.0), (0.0, 1.0))
BRANCHES: dict[str, tuple[Segment, ...]] = {
    # ᚠ fé: two high branches.
    "f": (
        ((0.0, -0.82), (0.64, -0.42)),
        ((0.0, -0.35), (0.58, 0.02)),
    ),
    # ᚢ úr: an angular side-bow returning to the stave.
    "u": (
        ((0.0, -0.82), (0.60, -0.48)),
        ((0.60, -0.48), (0.60, 0.42)),
        ((0.60, 0.42), (0.0, 0.82)),
    ),
    # ᚦ þurs: one attached thorn pocket.
    "th": (
        ((0.0, -0.62), (0.62, 0.0)),
        ((0.62, 0.0), (0.0, 0.62)),
    ),
    # ᚮ óss: two downward-facing branches.
    "o": (
        ((0.0, -0.58), (0.62, -0.92)),
        ((0.0, 0.02), (0.58, -0.32)),
    ),
    # ᚱ reið: an upper pocket with a descending leg.
    "r": (
        ((0.0, -0.78), (0.58, -0.38)),
        ((0.58, -0.38), (0.0, 0.0)),
        ((0.0, 0.0), (0.62, 0.72)),
    ),
    # ᚴ kaun: a single high branch.
    "k": (
        ((0.0, 0.08), (0.66, -0.58)),
    ),
    # ᚼ hagall: one diagonal half of the long-branch form.
    "h": (
        ((0.0, 0.30), (0.66, -0.42)),
    ),
    # ᚿ nauð: a shorter mid-height diagonal.
    "n": (
        ((0.0, 0.18), (0.62, -0.28)),
    ),
    # ᛆ ár: one low, downward-facing branch.
    "a": (
        ((0.0, -0.18), (0.62, 0.34)),
    ),
    # ᛋ sól: angular side-zigzag, attached at both ends.
    "s": (
        ((0.0, -0.76), (0.58, -0.28)),
        ((0.58, -0.28), (0.18, 0.12)),
        ((0.18, 0.12), (0.62, 0.58)),
        ((0.62, 0.58), (0.0, 0.84)),
    ),
    # ᛐ Týr: one half of the arrow head.
    "t": (
        ((0.0, -0.92), (0.64, -0.28)),
    ),
    # ᛒ bjarkan: two clean pockets sharing the main stave.
    "b": (
        ((0.0, -0.86), (0.58, -0.48)),
        ((0.58, -0.48), (0.0, -0.04)),
        ((0.0, -0.04), (0.58, 0.38)),
        ((0.58, 0.38), (0.0, 0.82)),
    ),
    # ᛘ maðr: one long diamond-like branch path.
    "m": (
        ((0.0, -0.84), (0.62, -0.12)),
        ((0.62, -0.12), (0.0, 0.64)),
    ),
    # ᛚ lǫgr: one rising branch near the top.
    "l": (
        ((0.0, -0.42), (0.62, -0.88)),
    ),
    # ᛦ ýr: one branch rising from the lower stave.
    "yr": (
        ((0.0, 0.86), (0.62, 0.24)),
    ),
}


# Row-major 6x6 layout. These use Younger Futhark components appropriate to the
# Viking Age. A few doubled pairs (nn, aa, tt) reflect historically attested
# same-rune ligatures; they remain readable because each branch takes one side.
BINDS: tuple[tuple[str, str], ...] = (
    ("f", "th"),
    ("a", "r"),
    ("t", "b"),
    ("h", "b"),
    ("n", "r"),
    ("k", "m"),
    ("u", "a"),
    ("f", "r"),
    ("th", "a"),
    ("o", "k"),
    ("l", "n"),
    ("m", "yr"),
    ("b", "t"),
    ("r", "n"),
    ("a", "l"),
    ("u", "th"),
    ("f", "m"),
    ("s", "t"),
    ("n", "n"),
    ("a", "a"),
    ("t", "t"),
    ("k", "yr"),
    ("o", "r"),
    ("h", "m"),
    ("b", "n"),
    ("r", "a"),
    ("l", "th"),
    ("yr", "f"),
    ("m", "k"),
    ("s", "a"),
    ("th", "r"),
    ("u", "l"),
    ("f", "b"),
    ("o", "m"),
    ("n", "t"),
    ("b", "yr"),
)


def sector_bounds(index: int) -> tuple[int, int, int, int]:
    """Exact non-overlapping atlas sector for a row-major glyph index."""
    row, column = divmod(index, COLUMNS)
    left = round(column * ATLAS_SIZE / COLUMNS)
    top = round(row * ATLAS_SIZE / ROWS)
    right = round((column + 1) * ATLAS_SIZE / COLUMNS)
    bottom = round((row + 1) * ATLAS_SIZE / ROWS)
    return left, top, right, bottom


def canonical(segment: Segment) -> Segment:
    """Make an undirected segment hashable independent of endpoint order."""
    start, end = segment
    return (start, end) if start <= end else (end, start)


def mirror_x(segment: Segment) -> Segment:
    """Mirror a right-side rune branch onto the left of the shared stave."""
    start, end = segment
    return ((-start[0], start[1]), (-end[0], end[1]))


def bind_segments(first: str, second: str) -> tuple[Segment, ...]:
    """Join two rune branch sets around one, and only one, shared stave."""
    segments = (
        (SHARED_STAVE,)
        + tuple(mirror_x(segment) for segment in BRANCHES[first])
        + BRANCHES[second]
    )
    return tuple(dict.fromkeys(canonical(segment) for segment in segments))


def render_glyph(segments: tuple[Segment, ...], width: int, height: int) -> Image.Image:
    """Render one centered, antialiased glyph clipped to a cell-sized tile."""
    scale_factor = SUPERSAMPLE
    tile_size = (width * scale_factor, height * scale_factor)
    tile = Image.new("RGBA", tile_size, TRANSPARENT)
    draw = ImageDraw.Draw(tile)

    all_points = [point for segment in segments for point in segment]
    min_x = min(point[0] for point in all_points)
    max_x = max(point[0] for point in all_points)
    min_y = min(point[1] for point in all_points)
    max_y = max(point[1] for point in all_points)
    source_width = max_x - min_x
    source_height = max_y - min_y
    source_center_x = (min_x + max_x) / 2
    source_center_y = (min_y + max_y) / 2

    stroke = STROKE_WIDTH * scale_factor
    render_gutter = CELL_GUTTER + FILTER_MARGIN
    available_width = (width - 2 * render_gutter) * scale_factor - stroke
    available_height = (height - 2 * render_gutter) * scale_factor - stroke
    glyph_scale = min(available_width / source_width, available_height / source_height)
    center_x = tile_size[0] / 2
    center_y = tile_size[1] / 2

    def transform(point: Point) -> tuple[float, float]:
        return (
            center_x + (point[0] - source_center_x) * glyph_scale,
            center_y + (point[1] - source_center_y) * glyph_scale,
        )

    for start, end in segments:
        draw.line(
            (transform(start), transform(end)),
            fill=WHITE,
            width=round(stroke),
        )

    resampling = getattr(Image, "Resampling", Image).LANCZOS
    return tile.resize((width, height), resampling)


def generate_sheet() -> Image.Image:
    if len(BINDS) != COLUMNS * ROWS:
        raise ValueError(f"Expected {COLUMNS * ROWS} bind runes, found {len(BINDS)}")

    sheet = Image.new("RGBA", (ATLAS_SIZE, ATLAS_SIZE), TRANSPARENT)
    for index, (first, second) in enumerate(BINDS):
        left, top, right, bottom = sector_bounds(index)
        tile = render_glyph(bind_segments(first, second), right - left, bottom - top)
        # A cell-sized tile pasted at exact sector bounds is an explicit clip:
        # no source pixel can ever land in a neighboring sector.
        sheet.alpha_composite(tile, (left, top))
    return sheet


def validate_sheet(sheet: Image.Image) -> None:
    """Fail if a glyph is absent, off-center, or too close to a sector edge."""
    if sheet.size != (ATLAS_SIZE, ATLAS_SIZE) or sheet.mode != "RGBA":
        raise ValueError(f"Expected {ATLAS_SIZE}x{ATLAS_SIZE} RGBA, got {sheet.mode} {sheet.size}")

    alpha = sheet.getchannel("A")
    for index, names in enumerate(BINDS):
        left, top, right, bottom = sector_bounds(index)
        local_box = alpha.crop((left, top, right, bottom)).getbbox()
        if local_box is None:
            raise ValueError(f"Glyph {index} {names} is empty")

        glyph_left, glyph_top, glyph_right, glyph_bottom = local_box
        cell_width = right - left
        cell_height = bottom - top
        gutters = (
            glyph_left,
            glyph_top,
            cell_width - glyph_right,
            cell_height - glyph_bottom,
        )
        if min(gutters) < CELL_GUTTER - 1:
            raise ValueError(
                f"Glyph {index} {names} violates its sector gutter: {gutters}"
            )

        glyph_center = (
            (glyph_left + glyph_right) / 2,
            (glyph_top + glyph_bottom) / 2,
        )
        cell_center = (cell_width / 2, cell_height / 2)
        center_error = max(
            abs(glyph_center[0] - cell_center[0]),
            abs(glyph_center[1] - cell_center[1]),
        )
        if center_error > CENTER_TOLERANCE:
            raise ValueError(
                f"Glyph {index} {names} is off-center by {center_error:.2f}px"
            )


def main() -> None:
    sheet = generate_sheet()
    validate_sheet(sheet)
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(OUTPUT_PATH, "PNG", optimize=True)
    print(f"wrote {OUTPUT_PATH}")
    print(f"validated {len(BINDS)} centered glyphs with isolated sectors")


if __name__ == "__main__":
    main()
