# what-the-merge.md

How to merge two diverged `.lxp` project files without silently corrupting them.

Written after merging `FullCampLayout2026_Ariel_v4` (my Subject/Background + modulator
work) with `_v5` (the arikdev line) into `_v6`. Tooling lives in `Tools/lxmerge/`.

---

## The one-line version

**Never merge `.lxp` as text, and never reorder anything by hand.** References are stored
as *positional paths*, so a stale path stays well-formed and binds to the wrong pattern
with no error. Merge by **component id**, then regenerate every path from identity, then
prove nothing drifted.

---

## Engine facts this all rests on

All verified against `Chromatik.app/Contents/app/glxstudio-1.2.1-jar-with-dependencies.jar`.
There is no JRE on PATH, so `javap` won't run — read the class constant pool / bytecode
directly (see "Re-deriving the facts" below). Re-check these on a Chromatik upgrade.

### 1. `path` beats `componentId`, and fails silently

`LXParameterModulation.getParameter()`:

```
has("path")        -> LXPath.getParameter(parent, path)   -> RETURN   <-- wins
  (only if absent/unresolved)
has("id")          -> getProjectComponent(id)
has("componentId") -> getProjectComponent(id).getParameter(parameterPath)
```

`path` is **1-based positional**: `/mixer/channel/10/pattern/2/motionSpeed`. Delete a
channel and every path after it still resolves — to the wrong thing. The stable
`componentId` sitting right next to it is never consulted.

### 2. Snapshots have no id fallback at all

`LXSnapshot$ParameterView` and `$ChannelFaderView` resolve purely via
`LXPath.get(lx, parameterPath|channelPath)`. There is no `componentId` anywhere in those
classes. Only `ActivePatternView` carries a `patternId`.

This project has **1382 snapshot views across 3 snapshots**. They are the largest and most
fragile body of positional references in the file, and the easiest to forget.

### 3. Group membership is explicit — but position still matters

`LXChannel` has `KEY_GROUP` holding the group's **id** (top-level `"group"` key on the
channel object, *not* in `parameters`). So regrouping is an id edit, not a move.

But the engine still assumes members **directly follow their group** in the flat
`channels` array. Set both, and assert the invariant.

> I initially assumed grouping was purely positional and told myself the reorg had to be
> done in the UI. It doesn't — but only because of `KEY_GROUP`. Check this before trusting
> any restructure.

### 4. The UI rewrites paths; the file does not

`LXParameterModulation` and `LXSnapshot` both implement `pathChanges`/`fromPath`/`toPath`.
A running Chromatik fixes every stored path when you move something. **Moving things in
the app is always safe.** Editing the file is only safe if you regenerate paths yourself.

### 5. Chromatik writes with Gson

A naive `json.dump` rewrites the whole file and buries the real diff. To round-trip
byte-identically you need HTML-safe escaping (`< > & = '` -> `<` etc.) and Java
`Double.toString` number formatting (plain decimal only for `1e-3 <= |d| < 1e7`, else
`1.234E-6`). `Tools/lxmerge/lxio.py` does this; it round-trips v4 and v5 byte-for-byte.

---

## Noise vs. signal

~85% of the raw diff between two saves of the same show is save-time state. Filter it or
you'll chase ghosts for hours.

| Treat as noise | Why |
|---|---|
| `/basis` on nested modulators | live LFO phase at save (147 instances in this merge) |
| `/audio/meter/band-*`, `/audio/input` | live levels; input device is machine-specific |
| `tempo/bpm`, `tempo/period` | drifts every save |
| `externals/ui/preview/camera/*` | where the camera happened to be |
| `internal/*`, `deviceVersion`, `midiFilter/*` | UI expansion state / defaults |
| `focusedPattern`, `focusedChannel`, `selected`, `autoCycleCursor` | cursor position |
| `activeStep-N` on step sequencers | live sequencer position |
| Colorize `color1/*`, `color2/*` on channel effects | palette-driven, not authored |

| Treat as signal | Why |
|---|---|
| pattern/effect **membership** (by id) | real content |
| device **effect chains** (`Hue Luma Cycle` etc.) | real authoring |
| non-Colorize pattern parameters | real tuning |
| `label`, `view`, `group` | real structure |
| modulation/trigger membership | real wiring |
| `framesPerSecond`, `gridMode`, view `selector`s | real config |

Ambiguous — ask a human: **active palette swatch**, channel `fader`. Could be an
intentional retheme or just where it sat at save time. The file cannot tell you.

---

## The method

### Phase 0 — Establish ground truth

Write down, per area, which file wins. This merge: *"v4 is truth for Subject and
Background; everything else comes from v5."* Without this the rest is unanswerable.

Also check lineage — `git log --follow` on each file. These were **forks**, not a linear
progression, which is why ids were shared but structure had diverged.

### Phase 1 — Prove your writer before you write

Load and re-dump each input; assert byte-identical output. If this fails, stop. Every
later diff is untrustworthy until it passes.

```
python3 -c "import lxio; p='X.lxp'; assert lxio.dumps(lxio.load(p))==open(p).read()"
```

### Phase 2 — Prove your path generator before you use it

Build `{componentId: canonicalPath}` (`lxpath.build_paths`), then verify it reproduces
**every existing path already in the file**. In this merge: 508/508 in v4, 510/510 in v5.
Only then may you generate a new path.

### Phase 3 — Inventory the diff by id, never by array index

Key everything on `(component id, parameter name)`. Report per channel: shared ids,
only-in-A, only-in-B, and — with noise filtered — which shared devices really differ.

This is what collapsed a 3MB scary diff into "Ears, 2 Curtain Info patterns, 1 modulator,
a rename pass." Do it before estimating anything.

### Phase 4 — Decide (the human part)

Surface every genuine conflict and *stop*. Don't guess at: duplicate work done twice on
both sides (v4's `WF-CONTROL` vs v5's `WF-CTRL` — same script, different tuning), content
that exists on only one side, palette changes.

### Phase 5 — Apply additively first

Append-only edits are safe: **appending to the tail of a list shifts no existing index**,
so every pre-existing path stays valid. Do all the additive work in one pass and audit it
before attempting any restructure.

Per item copied across: deep-copy it, assert its **owned** ids don't collide, then
regenerate its `path` fields against the destination tree. Nested modulation engines
inside a pattern store *owner-relative* paths (`/roll`, `/xMotion`) — those travel with
the component unchanged, leave them alone.

### Phase 6 — Restructure, regenerating from identity

Removals and reorders shift indices. That's fine *if* you rebuild every reference:

1. Capture the **old** path map and its reverse (`path -> id`) **before** mutating.
2. Compute the set of doomed components (all ids owned by anything being deleted).
3. Mutate: remove, reorder, fix `group` ids, adopt sections.
4. Drop any modulation/trigger referencing a doomed component.
5. Regenerate every surviving modulation/trigger `path` from its `componentId`.
6. Snapshots — they have no id, so bridge through the old tree:
   `old path -> (component, remainder)` via longest-known-prefix -> `new path`.
   Drop views whose component is gone.
7. Clamp `focusedChannel` / `focusedChannelAux` if they pointed past the end.

### Phase 7 — Audit for semantic invariance

Structural checks are not enough — a wrong-but-well-formed path passes all of them. The
load-bearing check is: **does every reference resolve to the same component it did
before?** Resolve in the old tree and the new tree, compare identity.

```
python3 Tools/lxmerge/lxaudit.py before.lxp after.lxp
```

---

## Audit checklist

| Check | Catches |
|---|---|
| gson round-trip byte-identical | writer bugs polluting the diff |
| all component ids unique | bad copy/paste of a subtree |
| removed set == intended set | collateral deletion |
| modulation `path` matches its `componentId` | stale/ungenerated paths |
| **every modulation resolves to same component as before** | silent rebinding |
| **every snapshot view resolves to same component as before** | the 1382-view trap |
| no dangling snapshot channel paths | deleted targets |
| snapshot view count unchanged (or drops explained) | accidental view loss |
| group members directly follow their group | broken group rendering |
| `focusedChannel` in range | crash / weird UI on load |
| declared-truth channels byte-identical to source | scope creep into Subject/Background |
| all custom classes present in `Packages/` | project won't load |

---

## Traps hit doing this for real

- **Id-collision check descending into `source`/`target`.** Those hold *references* to
  existing components. Walking them makes every copied modulation look like a collision.
  Only count **owned** ids.
- **`/lx/mixer/crossfader` and `/lx/mixer/master/fader`** are engine params, not channels.
  A "must resolve to a channel" check flags them as dangling. They aren't. Scope the check
  to `/lx/mixer/channel/`.
- **The `Packages/` jar is shared between both project lineages.** After merging, verify
  *every* custom class the result references still exists — the jar may have been rebuilt
  from the other branch. (It was here, dated to v5's save minute, and was a superset. Do
  not assume.)
- **Off-by-one in a hand-written "expected changes" regex.** Two legitimate edits got
  flagged as unexpected. When the audit fails, suspect the audit first — but verify, don't
  assume.
- **Snapshot channel indices shift silently between versions.** v5's were exactly v4's
  minus 3 because three channels were removed ahead of them. Nothing in the file says so.

---

## Re-deriving the engine facts

No JRE on PATH, and `strings` chokes on the class files. Parse the constant pool directly:

```python
# read CONSTANT_Utf8 entries from a .class to see keys/messages,
# or walk the Code attribute for ldc/invoke order to get *precedence*.
# Constant-pool order is NOT execution order - disassemble the method
# if you need to know which key is checked first.
```

Extract classes with:

```
unzip -o -d out ~/Downloads/Chromatik.app/Contents/app/glxstudio-*-jar-with-dependencies.jar \
  'heronarts/lx/modulation/*' 'heronarts/lx/snapshot/*' 'heronarts/lx/mixer/*'
```

Classes worth reading: `LXParameterModulation`, `LXModulationEngine`, `LXSnapshot`,
`LXSnapshot$ParameterView`, `LXSnapshot$ChannelFaderView`, `LXChannel`, `LXGroup`,
`LXMixerEngine`.

---

## Tooling

| File | Purpose |
|---|---|
| `Tools/lxmerge/lxio.py` | byte-exact Gson-compatible load/dump |
| `Tools/lxmerge/lxpath.py` | `build_paths(doc) -> {componentId: canonicalPath}` |
| `Tools/lxmerge/lxaudit.py` | generic before/after invariance audit (CLI) |

The per-merge apply scripts are throwaway — write a fresh one each time, keep it
declarative (an explicit list of ids and edits), and have it print a line per change.
That log is your commit message and your rollback map.

## Always

- Work on a copy. Keep the pre-restructure file for diffing.
- Never touch the source files again once the merge starts.
- Open the result in Chromatik and look at it before committing. None of the above proves
  the show looks right — only that it means what it did before.
