# Ethereal Juice Box — audit and idea menu

Two parts. Part 1 is an audit of `Scripts/EtherealJuiceBox.js` as it stands
today, including the things that will get in the way of the ideas. Part 2 is a
deliberately over-long menu — roughly three times what you'd ship — organized so
you can strike whole sections without unpicking anything.

Every idea carries a **Cost** tag:

- **free** — render-tier or a handful of lines, no new sim work
- **cheap** — one new array or one new loop over the grid per substep
- **mid** — a refactor, or a new field that has to be advected
- **heavy** — a second pressure solve, a big buffer, or new hardware

Ideas that depend on another idea say so. The four items marked **★** are the
ones that unlock the largest number of others; if you trim aggressively, keep
those.

---

## Part 1 — Audit

### 1.1 What the fixture actually is

Worth stating because it changes several answers below. This pattern runs on
channel `WF-CTRL`, view 3, `Waterfall`. From `Fixtures/Waterfall.lxf` and
`waterfall-calibration.md`:

- **40 strips** (20 long × 360 LEDs, 20 short × 315 LEDs) = **16,200 pixels**
- Strips are ~0.14 m apart, 5.25–6 m long, total span 5.49 m
- Physically about **5.5 m wide × 6 m tall**, curved over the back of the dome

So the render surface is **40 columns wide and ~340 rows tall.**

### 1.2 Findings

| # | Finding | Where | Severity |
|---|---|---|---|
| A1 | **Output is grayscale only.** `rgb(channel, channel, channel)` — there is no hue anywhere in the pattern. Every color idea in Part 2 needs section C first. | `:713` | Design |
| A2 | **Vertical resolution is being thrown away.** `GRID_SIZE = 40` square. Horizontally that's a perfect 1 cell : 1 strip match. Vertically it's 1 cell : ~8.5 LEDs — the sim can't express any detail finer than a 9-pixel band. `FluidFire.js` already runs a non-square `GRID_W 40 / GRID_H 46`; there's precedent for decoupling. | `:11` | High |
| A3 | **Gamma `pow` is computed per point.** `Math.pow(4, gammaCorrection * 2 - 1)` doesn't depend on the point — that's 16,200 redundant `pow` calls per frame. Hoist to `preRender`. | `:710` | High, trivial fix |
| A4 | **Comment contradicts the code.** "Momentary buttons: true while held and false when released" sits directly above `toggle("b2", ...)`, which latches. B2 behaves differently from its five neighbors and the comment hides that. | `:33–39` | Low |
| A5 | **Six controls are declared and inert.** B5, B6, T3–T6, K5, K6 exist, occupy UI rows and MIDI-mapping slots, and do nothing. On a public panel a control that does nothing is worse than one that isn't there. | `:38–55` | Design |
| A6 | **Particle list is O(n) on both ends.** `particles.shift()` when full is an O(n) move on a 2048-element array, and `particles.splice(i, 1)` inside the reverse loop is O(n) per removal — so a burst that expires together costs ~O(n²), around 2M element moves in one substep. Use a swap-with-last removal or a compaction pass. | `:255`, `:607` | Med |
| A7 | **`applySourceSweep` is the hot loop.** Full 1600-cell scan × up to 97 samples × `sourceContainsWorld`, and `sourceContainsWorld` recomputes `Math.cos`/`Math.sin` on every call (plus two `Math.pow` for shape 2). Worst case ≈150k trig-heavy calls per substep, ×4 substeps. Two fixes: hoist the sin/cos per *sample* rather than per cell, and bound the scan to the swept bounding box instead of the whole grid. | `:342–369` | High |
| A8 | **`stampSolidSource` has the same problem, smaller.** 1600 `sourceContainsWorld` calls per substep with sin/cos recomputed each time. The angle is constant across the whole loop — hoist it once. | `:664–671` | Med |
| A9 | **`advectVelocity` samples the same coordinate twice.** Lines 486–487 call `sampleField` with identical `x`/`y` arguments, so all the clamp/floor/index arithmetic runs twice. Fuse into one call that returns both components. | `:486–487` | Med |
| A10 | **Particles pile up on the walls.** `clampValue(..., 0, 1)` parks them at the boundary, where no-slip means velocity is zero, so they sit there emitting dye for the rest of their lifespan. Produces a bright crust around the edge. Reflect or kill instead. | `:614–615` | Med |
| A11 | **B3 drains inconsistently.** `queuedB1` and `queuedB4` drain fully in a `while`; `queuedB3` decrements one per substep. Mashing B3 stretches its response over frames while the other two don't. | `:682` | Low |
| A12 | **`queuedB1` is unbounded.** Each press is 40 `emitSurfaceParticleAndPulse()` calls, each doing a 3-sample normal plus a 7×7 velocity stamp. Twenty presses landing in one frame is 800 of those — a visible hitch, and button-mashing is exactly what you're planning to encourage. Cap the drain per substep. | `:309–314` | Med |
| A13 | **There is no reset.** Nothing clears velocity, dye and particles. An installation needs a way to calm the box down without reloading the project. | — | Med |
| A14 | **Historical cruft in a constant.** `B4_FORCE_REFERENCE_SECONDS` and its comment preserve a tuning decision from a version that no longer exists. It's load-bearing now, but the comment reads as archaeology. | `:449–452` | Low |
| A15 | **Lifespan knob looks dead.** `lifespan` is snapshotted at emission, so turning the knob does nothing to particles already in flight — up to a 10-second lag before the change is visible. Correct behavior, misleading feel; say so in the tooltip. | `:261` | Low |
| A16 | **`enabledAmount` is ignored** in both `preRender` and `renderPoint`. | `:697`, `:708` | Low |
| A17 | **No seeded RNG.** The show isn't reproducible and B3 is a different shape every press. Arguably a feature; flagging it because several ideas below (ghost replay, attract mode) want determinism. | — | Info |
| A18 | **The dome curvature isn't compensated.** The waterfall's wings are angled back (3.05 m flat center + 1.22 m per wing), so evenly-spaced strips are *not* evenly spaced in world X. With `RELATIVE` normalization, `xn` bunches at the edges — a fluid moving at constant `xn`/s will appear to accelerate through the wings. | — | Low |

### 1.3 Structural read

The sim is currently single-purpose: one solid source, particles, one dye field.
That's a clean design, but it means every idea below has to enter through one of
four doors. Naming them now makes the rest of this document cheap to implement:

1. **Force injectors** — write into `velU`/`velV` *before* `projectVelocity()`
   (the existing comment at `:686` is right about why this ordering matters)
2. **Dye stampers** — write into `dye` after advection
3. **Advected fields** — new `FloatArray`s that ride the same backtrace as `dye`
4. **Render remaps** — anything that changes how `renderPoint` reads the field

### 1.4 Compute headroom

Per substep, roughly: advect (1600×2 samples) + diffuse (16 × 1600 × 2) +
vorticity (2 × 1600) + project (24 × 1600) + sweep + stamp ≈ **130k cell-ops**,
up to 4 substeps at 60 Hz. Practical consequences:

- **Adding one advected scalar field is nearly free** (+1600 samples/substep).
  Color, temperature and age are all cheap.
- **Adding a second pressure solve is not** (+38k/substep, ~30%).
- **Particles are cheap** up to a couple thousand — *once A6 is fixed.*
- **Tripling `GRID_H` triples everything.** Test it before committing to A2's fix.
- **Fixing A3, A7, A8 and A9 probably pays for a good chunk of Part 2.**

---

## Part 2 — Ideas

### Section C — Color foundation

Nothing in the box is colored today (A1). Most of the good ideas below want hue,
so this section comes first.

- **C1 ★ — Advected hue field.** Add `hue`/`hue2` `FloatArray`s and advect them
  with the same backtrace as `dye`. This is the single enabling move for hue
  rotation, per-particle color, two-dye mixing, and most of the multiplayer
  payoffs. *Cost: cheap.*
- **C2 — Per-particle hue.** Each particle carries a hue at birth; `emitParticleDye`
  writes it wherever the particle wins the max test. *Cost: cheap. Needs C1.*
- **C3 — Speed as hue.** No new field at all: `renderPoint` samples `|v|` and maps
  it to color. Fast is hot, still is cool. Teaches people what the knobs do
  within two seconds. *Cost: free.*
- **C4 — Curl as hue.** `curl` is already computed every substep and currently
  thrown away after vorticity confinement. Map clockwise and counter-clockwise
  to opposite hues and every vortex in the box becomes legible. *Cost: free.*
- **C5 — Pressure as hue.** Compression toward blue, rarefaction toward red.
  `pressure` is also already computed. *Cost: free.*
- **C6 — Age as hue.** A second advected field that increments each step, so old
  dye drifts down the palette. Gives comet tails a color gradient. *Cost: cheap.*
- **C7 — Run output through the active LX swatch.** `AlphaColorizer.js:60–75`
  already has the swatch-sampling code. The box then matches whatever palette the
  show is on, and a VJ can restyle it without touching this script. *Cost: cheap.*
- **C8 ★ — Two dye fields that mix.** Two independent colored dyes; where they
  overlap you get a third color that neither can make alone. This is also the
  strongest multiplayer mechanic in the document (see M8). *Cost: mid.*
- **C9 — Bloom.** Sample dye at three radii and sum. Fake glow, very cheap,
  large perceived quality jump on a 16,200-pixel surface. *Cost: free.*
- **C10 — Velocity-driven chromatic aberration.** Sample the dye field at slightly
  offset positions per RGB channel, offset proportional to local velocity. Fast
  motion fringes. Pure juice. *Cost: free.*

### Section T — Background effects on momentary buttons

Your idea #1. The framework matters more than any individual effect here.

- **T0 ★ — The envelope framework.** One shared mechanism: every background effect
  owns a `level` in [0,1]. Held → charges to 1 in ~0.2 s. Released → decays over
  10 s (or over a settings-tier "Hold" knob). Re-press recharges. Every effect
  below is then ~10 lines that reads `level`, and holding two buttons blends two
  effects with no special casing. Build this first; it's ~30 lines and it is the
  highest-leverage item in the document. *Cost: cheap.*

**Field forces**

- **T1 — Gravity.** Constant downward force. On a fixture literally called the
  waterfall this is thematically free money. *Cost: cheap.*
- **T2 — Buoyancy.** Dye density drives an upward force; the dye rises like smoke
  instead of drifting. *Cost: cheap.*
- **T3 — Wind.** Steady lateral force, direction from a knob. *Cost: cheap.*
- **T4 — Global vortex.** Rotational force field centered on the source; everything
  spirals in. *Cost: cheap.*
- **T5 — Shockwave.** An expanding ring of outward impulse. Re-press stacks another
  ring. `level` gates how many can be in flight at once. *Cost: cheap.*
- **T15 — Boil.** Inject a checkerboard of alternating vertical impulses; the whole
  box roils. *Cost: cheap.*
- **T19 — Density inversion.** Buoyancy with the sign flipped — dye becomes heavy
  and falls. *Cost: cheap.*
- **T23 — Interior wall.** A solid bar appears mid-box while held and the fluid has
  to flow around it. This is the effect that most obviously says *this is a real
  simulation, not a video.* Needs a solid mask in the pressure solve. *Cost: mid.*
- **T24 — Porosity.** One wall opens and the box drains. *Cost: mid.*
- **T18 — Drain.** A sink along the bottom edge that removes dye and pulls velocity
  down. Pairs with T16 into a loop. *Cost: cheap.*

**Time**

- **T6 — Freeze.** `level` scales `dt` toward zero. The fluid hangs mid-motion and
  snaps back on release. Absurdly cheap for how dramatic it is. *Cost: free.*
- **T25 — Time dilation.** Same mechanism, but 0.25×–4× rather than →0. Slow-mo and
  fast-forward on one control. *Cost: free.*
- **T7 — Rewind.** Record dye at ~4 Hz into a ring buffer (1600 floats × 40 frames
  ≈ 64k floats — fine) and play it backwards while held. *Cost: heavy.*
- **T9b — Ratchet.** The sim only advances on tap-tempo beats while held; motion
  goes staccato. *Cost: free. Needs H4.*

**Render remaps** (all essentially free, all very legible)

- **T8 — Mirror.** Render mirrors X about the source. Instant symmetry. *Cost: free.*
- **T9 — Kaleidoscope.** N-fold rotational symmetry, N from a knob. There's already
  a `KaleidoscopePostprocess.js` to lift from. *Cost: free.*
- **T10 — Tile.** Sample the dye field modulo, so the picture repeats 2×2 or 3×3.
  *Cost: free.*
- **T11 — Invert.** The fluid becomes a shadow; the room goes from dark-with-light
  to light-with-shadow. One line, enormous visual event. *Cost: free.*
- **T12 — Posterize.** Quantize dye to N levels — the smooth fluid becomes hard
  contour bands, like a topographic map of the flow. *Cost: free.*
- **T13 — Edge detect.** Render `|∇dye|`. The fluid becomes a wireframe of itself.
  *Cost: free.*
- **T14 — Heat shimmer.** Displace the render sample by a noise field. *Cost: free.*
- **T20 — Strobe.** Render gates on and off at a knob rate. Crowd-pleasing and
  genuinely risky — put a hard maximum rate in the settings tier, not on the
  panel (see F5). *Cost: free.*
- **T21 — Scanline.** A bright line travels up the waterfall, re-lighting dye as it
  passes. *Cost: free.*
- **T22 — Pin the field.** Freeze the current velocity field and keep reapplying it,
  so dye keeps flowing along a static pattern. Locks in a groove. *Cost: cheap.*

**Emitters**

- **T16 — Rain.** Spawn particles along the top edge with downward launch velocity.
  On this fixture it reads perfectly. *Cost: cheap.*
- **T17 — Snow.** Same, slow, high drag, wandering. *Cost: cheap.*

### Section P — Multi-agent particles on knob pairs

Your ideas #2 and #3.

- **P0 ★ — The agent refactor.** Generalize the single source into an array of
  agents, each with x, y, angle, size, shape, charge and behavior flags.
  `applySourceSweep`, `stampSolidSource` and `applyAttraction` all loop over
  agents. Not a feature — but every idea in this section depends on it, and it's
  the right time to fix A7 and A8 while you're in there. *Cost: mid.*

**Allocation** (these three are mutually exclusive — pick one)

- **P1 — Three agents, all six knobs.** K1/K2, K3/K4, K5/K6. Rotation and size move
  to the settings tier or onto toggles. Maximum multiplayer, loses the two
  controls that currently give the source its character.
- **P2 — Two agents plus shared shaping.** K1/K2 and K3/K4 are agents, K5 is
  rotation and K6 is size for both. Keeps the current feel, adds a second hand.
  **Recommended** — it's the smallest change that buys multiplayer.
- **P3 — Shift layer.** A toggle flips K1–K6 between "three agents' XY" and
  "rotation / size / charge". Doubles the knob count at the cost of needing
  soft-takeover (F7), without which every mode change is a visible jump.

**What an agent is**

- **P4 — Agents as swarms.** An agent isn't one blob but *n* sub-particles orbiting
  its XY. "3 spinning orbs" and "6 points" both fall out of one parameter.
  *Cost: cheap.*
- **P5 — Orbit geometry.** Ring / Lissajous / figure-eight / cardioid, selected by a
  toggle pair. Two knobs place a shape that is already alive on its own. *Cost: cheap.*
- **P6 — Counter-rotating rings.** Sub-particles split into two rings spinning
  opposite ways; dye piles up where they cross. *Cost: cheap.*
- **P7 — Trailing chain.** Sub-particles follow the leader with a delay, so moving a
  knob whips a tail across the box. Extremely satisfying under the hand. *Cost: cheap.*
- **P8 ★ — Spring-coupled knobs.** The knob sets an *anchor*; the agent is a mass on
  a spring pulled toward it. Overshoot, wobble, momentum. This is about ten lines
  and it makes every knob on the panel feel an order of magnitude better than
  snapping the position directly. If you implement one thing from this section,
  make it this. *Cost: cheap.*
- **P21 — Repulsor agent.** Pushes dye away instead of stamping it, carving a clean
  hole. *Cost: cheap.*
- **P22 — Agent feeds on brightness.** Size grows with the dye it's sitting in. Move
  it somewhere bright and it fattens. *Cost: cheap.*

**Interaction between agents** (your idea #3)

- **P9 — Inter-agent springs.** The agents are connected to *each other*. Move one
  and the others follow. Two people share one web. *Cost: cheap.*
- **P10 — Charge and polarity.** Toggles set each agent's charge sign. Same sign
  pushes the fluid apart; opposite pulls it into a bridge between them. *Cost: cheap.*
- **P11 — Agent collision.** Agents can't overlap and shove each other away. With
  three agents that's three pairs — trivially cheap. *Cost: cheap.*
- **P12 — Three-body gravity.** Genuinely chaotic, genuinely beautiful, and with
  n=3 it's free. Best combined with P8 so the knobs set targets and gravity is
  the perturbation rather than the whole story. *Cost: cheap.*
- **P13 — Merge on contact.** Two agents brought together fuse into one bigger,
  brighter one, then split with a bang. Needs two hands by construction. *Cost: cheap.*
- **P14 — Annihilation.** Opposite charges touching = flash, shockwave, both recoil.
  Mash-friendly and immediately legible. *Cost: cheap.*
- **P15 — Vortex shedding.** Each moving agent sheds alternating vortices behind it
  (a von Kármán street). `applySourceSweep` plus vorticity confinement already
  gets you halfway; a small alternating curl injection at the trailing edge makes
  it explicit. *Cost: cheap.*
- **P23 — Predator and prey.** One agent automatically chases another; the human
  drives the prey. Emergent chase with one line of steering. *Cost: cheap.*

**Coupling agents to the rest**

- **P16 — Motion gates emission.** Agents only emit while moving; stillness is
  darkness. Forces continuous participation. *Cost: free.*
- **P17 — Speed as hue.** Fast agents draw hot, slow agents draw cool. *Cost: free.
  Needs C1.*
- **P18 — Ghost agent.** A fourth agent replays your knob motion from 10 seconds
  ago. You duet with yourself. A 600-entry ring buffer. *Cost: cheap.*
- **P19 ★ — Autopilot on idle.** Any agent whose knob hasn't moved in ~20 s starts
  drifting on a Lissajous of its own, and snaps back to manual the instant the
  knob moves. **This is the most important idea in the document for an unattended
  installation** — it's the difference between a sculpture and a dark box with
  knobs on it. *Cost: cheap.*
- **P20 — Drawn lines as obstacles.** See H3. *Cost: mid.*

### Section H — Hold gestures

Your idea #6.

- **H1 — Hue rotation while held.** A held button adds to a global hue offset; the
  longer you hold, the further round the wheel; release leaves it where it is.
  Exactly as you described it, and once C1 exists it is about five lines.
  *Cost: free. Needs C1.*
- **H11 — Accelerating hue rotation.** Rate ramps with hold duration, so short holds
  nudge and long holds spin wildly. *Cost: free.*
- **H2 ★ — Etch-a-sketch.** While held, the agent's path writes into a persistent
  `chalk` array that is *not* advected, rendered under the fluid at ~40%
  brightness. Decays over ~30 s so the box self-cleans between people. It's a new
  field but a static one — no advection cost. *Cost: cheap.*
- **H3 — Etch-a-sketch that the fluid respects.** The chalk also acts as a solid
  boundary in the pressure solve, so you can draw a funnel and the fluid actually
  goes through it. This is the strongest single idea in the document — it turns
  a pretty effect into a toy with a physics engine behind it — and it is also the
  most expensive, because the pressure solve needs a solid mask. *Cost: heavy.*
- **H4 — Tap tempo.** Presses set a BPM; orbits, strobe, scanline and ambient
  emission all lock to it. **There is already tempo infrastructure in this repo**
  — `PrimaryController$Follower`, used at `BisectingShiftLines.js:277` — so the box
  can either drive the show's tempo or follow it. *Cost: cheap.*
- **H5 — Charge and release.** Holding visibly charges the agent (brighter, swollen);
  releasing dumps it as a proportional shockwave. Rewards patience where the rest
  of the panel rewards mashing. Having both is what gives the box dynamic range.
  *Cost: cheap.*
- **H8 — Hold to exhale.** The longest hold on the panel gradually brings the whole
  sim to a crawl and dims it, then snaps back. A built-in breath. *Cost: free.*
- **H12 — Afterimage.** The frame from just before the press is held as a ghost
  layer while held, cross-faded away over 2 s on release. *Cost: cheap.*
- **H6 — Hold duration selects the effect.** Tap does one thing, 1 s hold another,
  3 s hold a third. Doubles your button count at real cost to discoverability —
  use on at most one button. *Cost: free.*
- **H7 — Double-tap for the big version.** Same trade as H6. *Cost: free.*
- **H10 — Two-button chord.** See M4. *Cost: free.*

### Section S — Push/pull tactility

Your ideas #4 and #5.

**First, a hardware question worth resolving before designing around this.** The
script sees momentary buttons and latching toggles. "Push/pull on the knobs"
means either push-encoders or pull-switch pots, and each needs its own MIDI CC or
note. If the T-row switches *are* the physical push/pull, then T1–T6 already are
your push/pull surface — and their current job, a two-bit shape selector (A5,
`:41–47`), is the least interesting possible use of a control that's fun to flip.

- **S1 ★ — Get shape select off the toggles.** Shape is a set-and-forget choice.
  Move it to the settings tier or onto one toggle, and free the satisfying
  switches for something that pays off being flipped. *Cost: free.*
- **S2 ★ — Sparks out / sparks in.** Exactly as you described: flip up sprays
  particles outward from every agent, flip down sucks them in. The *action* is the
  effect, not a state — so it's mash-friendly by construction and there's no
  "wrong" position to leave it in. About twenty lines on top of the existing
  `emitSurfaceParticleAndPulse()`. This is the best idea in your list. *Cost: cheap.*
- **S3 — Both edges, different effects.** Up-flip and down-flip do genuinely
  different things rather than on/off. Doubles the value of six switches. *Cost: free.*
- **S4 — Ratchet accumulator.** Each flip adds to a decaying counter; crossing a
  threshold (say ten flips in five seconds) triggers something big. Rewards
  mashing without letting a single flip do it. *Cost: free.*
- **S6 — Bellows.** *Alternating* flips pump the fluid; same-direction flips do
  nothing. Forces a rhythm into the gesture. *Cost: free.*
- **S7 — Latch/momentary hybrid.** A quick flip toggles a state; holding the switch
  applies the effect only while held. Same physical control, two behaviors.
  *Cost: cheap.*
- **S8 — Pull to arm.** A switch arms an effect that fires on the next button press.
  Cross-control dependency, naturally two-handed. *Cost: free.*
- **S9 — Flip to commit.** The switch commits whatever you've drawn or built into
  the persistent layer. A deliberate "yes, keep that" gesture. *Cost: free. Needs H2.*
- **S10 — The full-row sweep.** Running a hand down all six switches at once is a
  gesture in itself. Detect it and pay it off enormously. *Cost: free.*
- **S5 — Flip velocity.** If the hardware sends note-on/note-off pairs you can time
  them and make a fast flip a hard flip. Probably not available — worth checking,
  because it's a free expressive axis if it is. *Cost: free, needs hardware.*
- **S11 — Knob push resets that agent to center.** Standard idiom, instantly
  discoverable. *Cost: free, needs push-encoders.*
- **S12 — Knob push holds position.** The agent freezes where it is while the knob
  is re-aimed, which solves "my knob is at the end of its travel and the blob is
  still in the corner". *Cost: cheap, needs push-encoders.*

### Section E — Composable primitives

Your idea #7, and I think the most important framing in the document.

- **E0 ★ — Four buses, not fifty effects.** Emergence comes from a small number of
  *orthogonal* primitives that all read shared state — not from many special-cased
  effects. Concretely: define four buses and make every control on the panel a
  single line saying which bus it writes to and how hard.
  1. **Force** — into the velocity field, before projection
  2. **Dye** — into the scalar field, after
  3. **Warp** — gravity direction, global rotation, time scale
  4. **Remap** — mirror, invert, hue offset, symmetry order

  Then combinations you never designed still work, and adding a control is a
  one-line change rather than a new code path. *Cost: mid (it's the P0/T0 refactor
  done with intent).*

**Shared globals worth defining**

- **E1 — Gravity direction as one shared vector.** Four toggles push it down, left,
  right, up. Two at once gives a diagonal. Four at once cancels. Nobody designs
  the diagonal; it just exists. *Cost: free.*
- **E2 — Time scale as one shared multiplier.** Freeze, slow-mo and ratchet all
  multiply into it and compose without special cases. *Cost: free.*
- **E3 — Symmetry order as one shared integer.** Mirror is n=2, kaleidoscope is n=6
  — same parameter. Any control can bump it. *Cost: free.*
- **E4 — Charge as one signed per-agent scalar.** Attract, repel and off become one
  number in [-1, 1] rather than three modes. *Cost: free.*
- **E5 — Emission as one per-agent gate.** "Only while moving", "only on beat",
  "only while held" are all multipliers into the same gate. *Cost: free.*
- **E6 — Hue offset as one accumulator.** The hue-rotate button, per-agent hue,
  palette drift and beat flashes all just add into it. *Cost: free.*
- **E7 ★ — An engagement scalar.** One global that tracks how much the panel has been
  touched in the last five seconds, and that every effect scales by. Idle reads
  calm; a crowd reads chaotic. Free dynamic range with no per-effect design work,
  and it's the same "last touched" bookkeeping P19 and M11 need. *Cost: cheap.*

**Combinations that fall out for free once the above exist**

- **E8** — Freeze + hue rotate = a frozen still image cycling through the spectrum.
- **E9** — Gravity + drawn obstacles = a Rube Goldberg machine for falling dye.
- **E10** — Opposite charges + wind = a dye bridge bowing in a crosswind.
- **E11** — Mirror + two agents on opposite sides = perfect symmetry that shatters
  the moment one person moves.
- **E12** — Tap tempo + orbits + strobe = the orbits appear to *freeze in place*
  through stroboscopic aliasing. Genuinely magical, and entirely free once those
  three exist independently.
- **E13** — Freeze + shockwave = the ring held mid-expansion as a static sculpture.
- **E14** — Rewind + etch-a-sketch = the drawing un-draws itself.
- **E15** — Rain + drain + wind = an actual weather system that nobody programmed.

### Section M — Multi-person

Your idea #8.

- **M0 ★ — Make the panel too wide for one person.** This does more for multiplayer
  than any software feature in this document, and it is a fabrication decision
  rather than a code one — so it needs deciding early. Everything below is much
  weaker if one person can reach all eighteen controls.
- **M8 ★ — Two dyes, one mixed color.** Each person's agent carries its own color;
  the third color exists *only* where their flows overlap. The reward for
  cooperating is a color you physically cannot make alone, and it needs no
  instructions — people find it in about fifteen seconds. Strongest multiplayer
  idea here. *Cost: mid. Needs C8.*
- **M1 — Convergence bloom.** All three agents within a small radius and the box
  floods. Needs three hands, or one person and a lot of luck. *Cost: free.*
- **M2 — Divergence lock.** The inverse: agents to the three corners and a standing
  wave locks in. *Cost: cheap.*
- **M3 — Tug of war.** Two opposite-charge agents; whichever is held nearer the
  center wins and the dye colors toward the winner. A visible score with no HUD.
  *Cost: cheap.*
- **M4 — Chord unlock.** Three buttons held at once — spaced far enough apart to
  require two people — unlocks a mode that is otherwise unreachable. Make it the
  best-looking thing in the box. *Cost: free.*
- **M5 — Handoff.** One agent only responds while someone else holds a button. One
  person aims, one enables. *Cost: free.*
- **M7 — Sustain meter.** A slow global that only charges while three or more
  controls are moving at once, unlocking progressively richer behavior. Rewards a
  crowd rather than a virtuoso. *Cost: cheap. Needs E7.*
- **M9 — Territory.** The box splits left/right; each half answers to its own
  controls and the boundary is soft and pushable. Push into someone's half and
  their dye gets displaced. *Cost: mid.*
- **M10 — Symmetry lock.** While two people hold their respective buttons, one
  agent is mirrored onto the other's side. Release and it desyncs. *Cost: free.*
- **M11 — Idle invitation.** When only one control has been touched for 60 s, the
  untouched agents start doing something obviously attractive to draw a second
  person in. Pairs with P19. *Cost: free. Needs P19.*
- **M6 — Call and response.** A button lights and must be answered within a window.
  Probably too gamey for a playa install; listed for completeness. *Cost: free,
  needs panel LEDs.*
- **M12 — Anti-hog.** An agent driven by one person for 90 continuous seconds
  gradually loses authority until released. Softly enforces turn-taking. Risky —
  people *will* notice and be annoyed. Listed anyway. *Cost: free.*

### Section R — Cheap render-tier wins

Things that cost almost nothing and look expensive. Several are just the audit
fixes with the upside stated.

- **R1 — Hoist the gamma `pow`** (A3). Free framerate, one line. *Cost: free.*
- **R2 — Bloom** (C9). *Cost: free.*
- **R3 — Temporal smear.** Blend this frame with the last at ~15% to soften the
  coarse grid. One array, one multiply-add. *Cost: free.*
- **R4 ★ — Raise `GRID_H`** (A2). 40 → 120 gives the vertical detail the fixture
  already has the pixels for. Cost is linear in cells, so measure at 120 and fall
  back to 80 if it doesn't hold framerate. *Cost: mid.*
- **R5 — Dither before quantizing.** A tiny per-point noise kills the banding in
  dim gradients, which across 16,200 LEDs is the most visible artifact a low-value
  fluid produces. *Cost: free.*
- **R6 — Static noise multiply.** Multiply sampled dye by a fixed high-frequency
  noise field. One array lookup, and a 40×40 sim starts reading as though it has
  real texture. *Cost: free.*
- **R7 — Soft highlight knee.** Replace the hard `clamp(..., 0, 1)` at `:709` with a
  soft rolloff so bright cores don't flatten into featureless white. *Cost: free.*
- **R8 — Aspect-correct the velocity.** Required if you do R4 — with `GRID_W ≠
  GRID_H`, `u` and `v` need consistent scaling or motion goes anisotropic.
  *Cost: free, but load-bearing.*
- **R9 — Compensate the wing curvature** (A18). A small lookup mapping `xn` through
  the actual strip positions, so motion crosses the box at constant apparent
  speed rather than accelerating through the wings. *Cost: cheap.*

### Section F — Installation-grade concerns

Unglamorous. Matters at 3am.

- **F1 — Panic control** (A13). A settings-tier trigger that zeros velocity, dye and
  particles. *Cost: free.*
- **F2 — Auto-recovery.** If total dye energy or peak velocity exceeds a sane bound
  for more than a second, damp hard. A fluid sim on a public panel *will* get
  driven unstable — probably within the first hour. *Cost: free.*
- **F3 ★ — Idle autopilot** (P19). The piece has to be beautiful with nobody at the
  panel, or nobody ever arrives at the panel.
- **F4 — Attract mode.** After ~3 minutes idle, slowly walk the background effects
  on their own. Doubles as a demonstration of what the buttons do. *Cost: cheap.*
- **F5 — Strobe rate ceiling** (T20). A hard maximum flash rate in the settings tier
  where the public can't reach it. Photosensitivity is a real risk on a 16,200-LED
  surface. *Cost: free.*
- **F6 — Brightness floor.** Never let the box go fully black for more than about a
  second — people read black as broken and walk away. *Cost: free.*
- **F7 — Soft takeover.** Required by any shift layer (P3): ignore an incoming knob
  value until it crosses the stored one, or every mode change is a visible jump.
  *Cost: cheap.*
- **F8 — Silkscreen the panel.** The best interaction design here is labels.
- **F9 — Per-control "last touched" timestamps.** Needed by P19, E7, M11, M12 and F4.
  Free to maintain, so add it once and early. *Cost: free.*
- **F10 — No per-frame logging.** A `System.err.println` inside a 60 Hz loop over
  16,200 points will take the show down.

---

## Trimming guide

### The dependency spine

Almost everything good routes through five items. If you build these, most of the
rest becomes a short afternoon each:

1. **T0** — the hold/decay envelope framework
2. **P0** — the agent refactor (fixes A7 and A8 on the way through)
3. **C1** — the advected hue field
4. **E0** — the four-bus discipline, applied while doing 1 and 2
5. **F9** — last-touched timestamps

### If you ship five features

**S2** (sparks in/out on the switches) · **P8** (spring-coupled knobs) · **H1**
(hue rotate on hold) · **P19/F3** (idle autopilot) · **M8** (two dyes that mix).

That set covers tactility, feel, your favorite gesture, unattended survival, and
multiplayer — with no single item costing more than a day.

### The one expensive thing worth considering

**H3** — etch-a-sketch lines that the fluid actually flows around. It needs a
solid mask threaded through the pressure solve, which is the most invasive change
in this document. It's also the only idea here that makes people stay for twenty
minutes instead of two.

### Where your ideas conflict

Worth deciding consciously rather than discovering later:

- **Three-agent XY (P1) eats all six knobs**, which removes rotation and size —
  currently two of the four things that give the source its character. P2 (two
  agents plus shared shaping) is the smaller, safer version; P3 (shift layer)
  keeps everything but needs soft takeover (F7) to not feel broken.
- **Backgrounds on buttons (T-section) and push/pull flair (S-section) compete for
  the same slots.** I'd give the buttons the sustained effects, since holding maps
  naturally to a sustained state, and give the switches the instantaneous ones
  (S2, S4, S6), since flipping maps naturally to an event. That split is also the
  reason the same six-slot row can carry both.
- **Etch-a-sketch (H2/H3) wants a button held for a long time**, which is in direct
  tension with a panel otherwise designed around mashing. It probably deserves its
  own dedicated button rather than sharing one via H6.
