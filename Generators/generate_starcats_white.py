"""
Images/starcats.jpg -> a white-on-transparent PNG of just the cat.

The source is line art in a saturated purple gradient sitting on a pastel
galaxy. Saturation is what separates them: the strokes run 0.85-0.95 while the
sky, even where it is bright pink, stays under 0.35. Brightness separates
nothing here — the purple is as bright as the clouds.
"""

import numpy as np
from PIL import Image
from scipy import ndimage

SRC = '/Users/arielwexler/Chromatik/Images/starcats.jpg'
OUT = '/Users/arielwexler/Chromatik/Images/starcats_white.png'

# Supersampling factor for the coverage pass.
SS = 4

# Saturation window the stroke edge ramps through.
EDGE_LO, EDGE_HI = 0.55, 0.75

# Smallest shape worth keeping, in source pixels. The artwork is five shapes —
# head with ears, eye, two cheeks, nose — and the smallest of those is the nose
# at about 1100px, so this sits well clear of it.
MIN_SHAPE = 500


def saturation(image):
    a = np.asarray(image).astype(np.float32) / 255
    mx, mn = a.max(2), a.min(2)
    return (mx - mn) / np.maximum(mx, 1e-6)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


src = Image.open(SRC).convert('RGB')
w, h = src.size

# Antialias by coverage rather than by reading the source's own edge gradient.
# Thresholding at native resolution gives an alpha ramp only about a pixel
# wide, which reads as stairsteps; classifying at 4x and averaging each 4x4
# block back down gives seventeen levels of partial coverage along every edge,
# and — unlike simply widening the threshold — it does that without dilating
# the strokes.
big = src.resize((w * SS, h * SS), Image.BICUBIC)
alpha = smoothstep(EDGE_LO, EDGE_HI, saturation(big))
alpha = alpha.reshape(h, SS, w, SS).mean((1, 3))

# Drop specks: JPEG ringing and the odd corner of sky can clear the threshold,
# but never over any area. Judged on the solid core so a speck cannot be
# rescued by the soft edge it brought with it.
core = alpha > 0.5
labels, count = ndimage.label(core)
keep = np.zeros(count + 1, bool)
for i in range(1, count + 1):
    keep[i] = (labels == i).sum() >= MIN_SHAPE
alpha = np.where(keep[labels], alpha, 0)

# White everywhere, including under full transparency: a viewer or a resampler
# that ignores alpha then still sees white, rather than pulling black out of
# the transparent pixels and fringing every edge.
out = np.zeros((h, w, 4), np.uint8)
out[:, :, :3] = 255
out[:, :, 3] = np.clip(alpha * 255 + 0.5, 0, 255).astype(np.uint8)
Image.fromarray(out, 'RGBA').save(OUT)

a8 = out[:, :, 3]
print(f'{OUT}  {w}x{h}')
print(f'  shapes kept {int(keep.sum())}/{count}   ink coverage {(alpha > 0.5).mean():.3f}')
print(f'  alpha: {int((a8 == 0).sum())} clear, '
      f'{int(((a8 > 0) & (a8 < 255)).sum())} partial, {int((a8 == 255).sum())} solid')
