"""
Placeholder art for the PlayingCard.js pattern.

Writes every PNG that Scripts/PlayingCard.js looks for into Images/Cards/, so
the pattern renders a real deck out of the box. The output is deliberately
plain — flat shapes on transparency — because it exists to be replaced. What
matters is the contract: the file names, the canvas sizes, and the fact that
everything outside the ink is alpha 0.

Three kinds of file, and that is the whole set:

  <suit>.png    one pip: heart, diamond, club, spade
  <rank>.png    one rank glyph: A, 1-10, J, Q, K
  back.png      the reverse of the card, drawn edge to edge, opaque

The card body itself (white stock, rounded corners) is drawn by the pattern,
not loaded, so nothing here needs a background. The pattern composites a suit
with its rank underneath into the top-left corner and repeats it rotated in the
bottom-right — there is no center artwork.

Run:  python3 Generators/generate_card_images.py
"""

import math
import os

from PIL import Image, ImageChops, ImageDraw, ImageFont

# Everything is drawn at SS times the final size and downsampled, which is the
# cheapest way to get clean curves out of PIL's aliased polygon fill.
SS = 4

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "Images", "Cards")

PIP_SIZE = (256, 256)
RANK_SIZE = (200, 260)
BACK_SIZE = (700, 980)

# One hue per suit rather than the usual red/black pair — on LEDs four
# distinguishable colors carry further than two.
SUITS = {
    "heart": (220, 36, 44, 255),
    "diamond": (36, 96, 220, 255),
    "club": (32, 168, 72, 255),
    "spade": (150, 56, 200, 255),
}

# Rank glyphs are drawn in neutral ink. The pattern tints them to the suit
# color by default, so the placeholder does not have to guess.
RANK_INK = (24, 24, 28, 255)

BACK_INK = (28, 58, 140, 255)
BACK_PAPER = (240, 240, 244, 255)

# "1" is written out alongside "A" so both spellings resolve to a file; the
# pattern's default rank list uses A.
RANKS = ["A", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"]

FONT_CANDIDATES = [
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def load_font(size):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                pass
    return ImageFont.load_default()


# ---------------------------------------------------------------- suit shapes
#
# Each shape function fills a suit into `draw` inside the box (cx, cy, w, h),
# in the coordinate space of whatever image it was handed.


def heart_points(cx, cy, w, h):
    # The classic 16sin^3 curve, normalized to the box. Sampled densely enough
    # that the polygon reads as a curve after downsampling.
    pts = []
    for i in range(240):
        t = 2 * math.pi * i / 240
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pts.append((cx + x * w / 32.0, cy - y * h / 32.0))
    return pts


def draw_heart(draw, cx, cy, w, h, color):
    draw.polygon(heart_points(cx, cy, w, h), fill=color)


def draw_diamond(draw, cx, cy, w, h, color):
    draw.polygon([(cx, cy - h / 2), (cx + w / 2, cy), (cx, cy + h / 2), (cx - w / 2, cy)], fill=color)


def draw_spade(draw, cx, cy, w, h, color):
    # An upside-down heart with a flared stem under it. The stem starts well
    # inside the body so the two fills read as one silhouette.
    body_h = h * 0.80
    body_cy = cy - h * 0.06
    pts = [(x, 2 * body_cy - y) for (x, y) in heart_points(cx, body_cy, w, body_h)]
    draw.polygon(pts, fill=color)
    draw.polygon([
        (cx - w * 0.05, body_cy),
        (cx + w * 0.05, body_cy),
        (cx + w * 0.21, cy + h * 0.5),
        (cx - w * 0.21, cy + h * 0.5),
    ], fill=color)


def draw_club(draw, cx, cy, w, h, color):
    # Three lobes plus a stem. The radius and offsets are sized so the lobes
    # touch rather than overlap into a blob, and stay inside the box.
    r = w * 0.25
    top_cy = cy - h * 0.19
    side_cy = cy + h * 0.06
    side_dx = w * 0.24
    for (bx, by) in [(cx, top_cy), (cx - side_dx, side_cy), (cx + side_dx, side_cy)]:
        draw.ellipse([bx - r, by - r, bx + r, by + r], fill=color)
    draw.polygon([
        (cx - w * 0.05, cy - h * 0.10),
        (cx + w * 0.05, cy - h * 0.10),
        (cx + w * 0.21, cy + h * 0.5),
        (cx - w * 0.21, cy + h * 0.5),
    ], fill=color)


SUIT_DRAW = {
    "heart": draw_heart,
    "diamond": draw_diamond,
    "spade": draw_spade,
    "club": draw_club,
}


def new_canvas(size):
    """A transparent RGBA canvas at supersampled resolution, plus its draw."""
    big = (size[0] * SS, size[1] * SS)
    img = Image.new("RGBA", big, (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def finish(img, size, path):
    img.resize(size, Image.LANCZOS).save(path)
    print("wrote", os.path.relpath(path, os.path.dirname(OUT_DIR)))


# -------------------------------------------------------------------- outputs


def write_pips():
    for suit, color in SUITS.items():
        img, draw = new_canvas(PIP_SIZE)
        w, h = img.size
        # Inset a little so the shape never touches the edge of its own tile.
        SUIT_DRAW[suit](draw, w / 2, h / 2, w * 0.86, h * 0.86, color)
        finish(img, PIP_SIZE, os.path.join(OUT_DIR, suit + ".png"))


def write_ranks():
    for rank in RANKS:
        img, draw = new_canvas(RANK_SIZE)
        w, h = img.size
        # "10" is two glyphs wide, so it gets a smaller point size to keep every
        # rank the same visual weight in the corner index.
        font = load_font(int(h * (0.82 if len(rank) == 1 else 0.62)))
        draw.text((w / 2, h / 2), rank, font=font, fill=RANK_INK, anchor="mm")
        finish(img, RANK_SIZE, os.path.join(OUT_DIR, rank + ".png"))


def write_back():
    img, draw = new_canvas(BACK_SIZE)
    w, h = img.size
    # Opaque edge to edge: the back covers the whole card, border included.
    draw.rectangle([0, 0, w, h], fill=BACK_PAPER)
    margin = w * 0.05
    draw.rounded_rectangle([margin, margin, w - margin, h - margin],
                           radius=w * 0.05, fill=BACK_INK)
    # A diagonal lattice over the whole canvas, then clipped to an inner panel
    # by multiplying its alpha with a rounded-rectangle mask.
    lattice = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ld = ImageDraw.Draw(lattice)
    step = w * 0.06
    line = int(w * 0.009)
    for i in range(int((w + h) / step) + 1):
        d = i * step
        ld.line([(d, 0), (d - h, h)], fill=BACK_PAPER, width=line)
        ld.line([(d - h, 0), (d, h)], fill=BACK_PAPER, width=line)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [margin * 1.7, margin * 1.7, w - margin * 1.7, h - margin * 1.7],
        radius=w * 0.04, fill=255)
    lattice.putalpha(ImageChops.multiply(lattice.getchannel("A"), mask))
    img.alpha_composite(lattice)
    finish(img, BACK_SIZE, os.path.join(OUT_DIR, "back.png"))


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    write_pips()
    write_ranks()
    write_back()


if __name__ == "__main__":
    main()
