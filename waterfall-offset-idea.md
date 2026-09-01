# Waterfall Global Y Offset

## Goal

Shift the presentation of every waterfall pattern upward by a configurable
amount (initially 15% of the normalized Y axis) without modifying each pattern.

## Recommended approach

Create one custom `Y Translate`/`Waterfall Offset` effect and attach it to the
parent `WATERFALL` group. Chromatik runs group effects after compositing the
group's child channels, so this moves the finished image produced by every
waterfall pattern, including built-in patterns, custom patterns, transitions,
and layered channels.

The `WATERFALL` group in `Projects/FullCampLayout2026_Playa.lxp` already uses the
Waterfall view and currently has an empty group-level effect chain, making this
the narrow centralized insertion point.

## Core behavior

The effect should snapshot the input color buffer before writing to it. For
each destination LED, sample the original frame at the same normalized X and a
lower normalized Y:

```java
CompoundParameter yShift =
  new CompoundParameter("Y Shift", .15, -.5, .5);

double sourceY = point.yn - yShift.getValue();
int output = sourceY < 0
  ? LXColor.BLACK
  : sampleOriginalFrame(point.xn, sourceY);
```

Sampling `point.yn - 0.15` makes the rendered content appear 15% higher. The
top 15% is clipped, and the newly exposed bottom 15% should default to black.

Useful optional edge modes:

- `BLACK`: blank the newly exposed area; recommended default.
- `CLAMP`: extend the lowest edge color.
- `WRAP`: wrap content around from the top.

## Reuse existing machinery

`Patterns/src/main/java/com/starcats/blinkydome/NoiseRippleDistortionEffect.java`
already contains the important arbitrary-model resampling machinery:

- Snapshot the frame before modifying the live color buffer.
- Build a normalized XY-to-nearest-model-point lookup.
- Sample the source buffer at arbitrary normalized XY coordinates.
- Rebuild the lookup only when the model changes.

That sampler could be extracted into a shared helper or adapted into a small
dedicated effect. Individual waterfall patterns would remain untouched.

Because the Playa waterfall is a set of straight vertical strips wired
bottom-to-top, a waterfall-specific implementation could alternatively shift
rows within each strip. At the current six-metre full height, 15% is about
0.9 metres or 54 LEDs. The normalized XY sampler is more general and will keep
working if the waterfall geometry changes.

## Control

Expose `Y Shift` as a `CompoundParameter`, with `0.15` as the initial value.
That makes the offset directly controllable through:

- A Chromatik UI knob.
- MIDI or modulation mapping.
- OSC, using the effect parameter path.
- Code such as `effect.yShift.setValue(.15)`.

If desired, a physical console knob can drive this parameter using the same
parameter-binding approach already used elsewhere in `ClickyBinding.java`.

The group effect should be bypassed during wiring/geometry calibration so the
calibration display continues to represent literal pixel positions.

## Why not translate the fixture?

The Waterfall view uses `RELATIVE` normalization. Translating all fixture
points upward generally causes the view to normalize the translated bounds
back into the same 0-1 range, so patterns using `point.yn` do not visibly move.
Changing fixture geometry would also make the model disagree with the physical
installation and calibration.

Likewise, introducing a shared pattern superclass would not cover existing
patterns: the current custom patterns extend `LXPattern` independently, and it
would not affect Chromatik's built-in patterns. A single group postprocess is
the centralized solution.
