# Custom Java Patterns

Custom Chromatik patterns for the dome, written in Java.

Chromatik lets you write patterns three ways: the built-in JavaScript scripting
(quick, limited), the Chromatik UI itself, or **Java** — which is what the engine
is actually written in, gets you the full [LX API](https://chromatik.co/api/),
and runs at full speed. This folder is the Java path.

Everything here compiles into a single **content package** — a `.jar` file that
Chromatik loads at startup. That's the officially supported way to ship custom
components; see the [Chromatik developer guide](https://chromatik.co/develop/).

---

## Quick start

After every code change:

```bash
cd Patterns
./build.sh
```

…and restart Chromatik. In the pattern browser you'll find a **Blinky Dome**
category containing **Spatial Rainbow**.

There is no install step. The repo root *is* the Chromatik home directory, so
the build writes straight into the folder Chromatik scans:

```
<repo>/Packages/blinky-dome-patterns.jar
```

A rebuild is live the moment Chromatik restarts. The jar is committed, so a
fresh clone has working patterns without a JDK — you only need to build after
changing the Java.

The jar filename deliberately has no version number in it. A versioned name
would strand the old jar in `Packages/` on every version bump, and Chromatik
would load both, giving you duplicate-class errors.

### Requirements

A **JDK 21 or newer**. That's it — `build.sh` downloads its own copy of Maven
(3.9.9) into `Patterns/.tools/` if you don't already have `mvn` on your PATH.

```bash
brew install openjdk@21
```

That 21 has one source of truth: `<maven.compiler.release>` in
[`pom.xml`](pom.xml), which is what Maven compiles against. `build.sh` reads the
property instead of hardcoding a number, so the version it demands and the
version it targets can't drift apart. To move to a newer Java, change that
property and nothing else.

`build.sh` looks in Homebrew's keg-only location directly, so that's all you need
— no `/Library/Java` symlink, no PATH changes.

> The JVM bundled inside `Chromatik.app` is a stripped-down runtime with no
> compiler in it, so it can't build this. You need a real JDK.

> **`java` already "exists" on macOS and that's a trap.** With no JDK installed,
> macOS still ships placeholder stubs at `/usr/bin/java` and `/usr/bin/javac`
> (one binary, hardlinked under every tool name). They're executable and on your
> PATH, so naive detection concludes Java is installed. Worse, `java -version`
> against the stub prints a tidy error and exits, but a real classpath launch
> *blocks forever* — so a build wired up this way hangs with no output rather
> than failing. `build.sh` rejects the stub by requiring a `release` file at the
> JDK root, which every genuine JDK image has and the stub directory doesn't.

---

## What's in here

```
Patterns/
├── README.md                 you are here
├── pom.xml                   Maven build: deps, Java version, packaging
├── build.sh                  compile -> ../Packages/blinky-dome-patterns.jar
└── src/main/
    ├── java/com/starcats/blinkydome/
    │   ├── SpatialRainbowPattern.java     the demo pattern
    │   ├── ImageTest.java                 projects a jar-bundled image
    │   ├── BeatClock.java                 beat tracking, no Chromatik in it
    │   ├── PrimaryController.java         tempo + intensity + mood (a modulator)
    │   ├── Mood.java                      AMBIENT / BUILDING / DRIVING
    │   ├── MoodState.java                 registry the trackers look it up through
    │   ├── DriveTracker.java              on-beat pulse gated to DRIVING
    │   ├── AmbientTracker.java            on-beat pulse scaled by intensity
    │   ├── DropTracker.java               one-shot ramp on the drop
    │   ├── PortableImagePattern.java      image pattern with portable paths
    │   ├── PortableSlideshowPattern.java  slideshow with portable paths
    │   └── MediaPath.java                 media-relative path translation
    └── resources/
        ├── lx.package        package metadata Chromatik reads
        └── com/starcats/blinkydome/
            └── eye_of_sauron.jpeg         ImageTest's bundled image
```

The built jar lands in the repo's top-level `Packages/` folder and is committed;
`target/` holds intermediate build output and is gitignored.

`src/main/resources/` can also hold `fixtures/`, `models/`, and `projects/`
subfolders. Those get copied into `~/Chromatik/BlinkyDome/` when the package is
imported through the Chromatik UI (the `mediaDir` field in `lx.package` picks the
folder name). We keep fixtures in the repo's top-level `Fixtures/` folder
instead — which Chromatik already reads directly — so bundling them here too
would just duplicate them.

---

## Mood system

Four modulators that between them work out what the music is doing and hand that
conclusion to everything else. **PrimaryController** does the listening; the
other three turn its conclusions into signal. One controller per show.

### Wiring

1. Add three **Band Filter** or **Band Gate** modulators aimed at low, mid and
   high, or any three level sources.
2. Add a **Primary Controller** and map those onto its **Low**, **Mid** and
   **High** knobs.
3. Add whichever of **Drive Tracker**, **Ambient Tracker** and **Drop Tracker**
   you want. They find the controller by themselves — there is nothing to wire
   between them.

Only **Low** drives the beat clock. Mid and High feed intensity.

### What the controller works out

| Output | What it is |
| --- | --- |
| the modulator's value | smoothed intensity, so the controller maps directly |
| `Beat` | trigger on every beat of the tracked tempo |
| `BPM` / `Conf` | tempo, and how consistent recent intervals have been |
| `Intensity` | the same smoothed figure as the value |
| mood | AMBIENT / BUILDING / DRIVING, read by the other three |

Intensity is a weighted mix of the three bands (**Lo W** / **Mid W** / **Hi W**,
normalized) run through a follower with separate **Charge** and **Release** time
constants. Two constants because the directions do different jobs: charge has to
be quick enough that a riser reads as it happens, release slow enough that the
gap between kicks does not look like the energy dropping.

**Min BPM** is a hard floor — an interval implying anything slower is thrown
away, never doubled into range. Set it near the slowest track you will play.

### The moods

- **AMBIENT** — no bass for `Amb Bts` beats (default 6). The floor is empty.
- **BUILDING** — reachable only from AMBIENT, and only after 4s of both silence
  and settled state, when intensity has climbed by **Rise** over **Window** with
  both halves of that window contributing. That last part is what tells a riser
  from a track simply starting, which steps the intensity up in a single frame.
- **DRIVING** — two consecutive high-confidence bass beats, from any state.

A build that sets no new intensity peak for 10 seconds has stalled and falls back
to AMBIENT. A build that resolves into a drive is a **drop**, and only that
transition fires DropTracker.

### The charts

The beat chart spans 3 seconds, scrolling right to left: red dots are registered
beats, dashed lines are where the audio beat actually is, solid lines are where
beats are emitted after **Shift**. Dots on the dashed lines means the tracking is
right; the gap to the solid lines is the offset you dialled in. BPM sits top
left.

Below it, smoothed intensity over 15 seconds with the current mood named. The
mood machine runs off that curve, so a transition that looks wrong can be traced
to the shape that caused it.

### The three trackers

**Drive Tracker** emits `exp(-t*k/10)` on the controller's beat grid, multiplied
by a gate that opens only in DRIVING. The gate is an RC follower, not a switch
(**Gate**, default 2s), so the drive layer arrives under a drop rather than
snapping in on top of it.

**Ambient Tracker** is the same pulse scaled by smoothed intensity, and is never
gated. It goes quiet during a breakdown because the room is quiet, not because a
state machine muted it, and swells back on its own through a build. **Depth**
blends out the intensity scaling if you want a fixed-brightness layer.

**Drop Tracker** fires once on BUILDING → DRIVING: a trigger plus a linear ramp
from 1 to 0 over **Fall**. Linear, not exponential — this is a one-shot sweep
meant to hold the room for a few seconds, and exponential spends most of its life
near zero.

All three output 0 and say "no ctrl" when there is no controller.

### How it's put together

`BeatClock` holds the tracking — edge detection, interval averaging, outlier
rejection, phase lock, shift — with no Chromatik in it at all and no parameters,
so it is testable without an engine or a UI. `PrimaryController` owns one and
adds the parameters and the mood machine. `MoodState` is the static registry the
other three look the controller up through; controllers deregister on dispose so
a project reload cannot leave a stale one published. Children watch monotonic
counters rather than per-frame flags, so they are correct whatever order the
engine runs modulators in.

Measured against synthetic gates at 128 BPM over 60s runs — a perfect gate, one
jittering ±20ms, one missing 30% of beats, and one going silent for 12s — the
tracker holds the tempo exactly and the output stays steady to about one frame,
riding through the whole dropout still in phase. A gate firing on every eighth
note locks just as solidly at 257 BPM, because that genuinely is the rate it is
being told about.

Where it struggles is a gate firing at the right rate *plus* stray hits in
between: with a spurious hit on 25% of beats it drifts to around 230 BPM and
output jitter climbs to ~55ms, because a stray hit splits one beat into two
shorter intervals. `Conf` drops to about 0.6 when this happens — the signal to
raise **Thresh** until the chart shows one red dot per beat.
---

## How the demo pattern works

The whole idea behind spatial animation is one line:

```
hue = f(position) + g(time)
```

Give each LED a color based on **where it is**, then slide that mapping over
time. `SpatialRainbowPattern` maps position to hue and advances a phase offset
every frame.

### The three pieces of any LX pattern

**1. Class declaration + annotations** — this is how Chromatik discovers and
labels your pattern. It imports every public, non-abstract class in the jar
automatically; there's no registration step.

```java
@LXCategory("Blinky Dome")                   // group in the pattern browser
@LXComponent.Name("Spatial Rainbow")         // display name
@LXComponent.Description("...")              // tooltip
public class SpatialRainbowPattern extends LXPattern {
```

**2. Parameters** — declared as fields, then registered in the constructor.
`addParameter()` is what makes a knob appear in the UI, get saved into the
`.lxp` project file, and become available as a modulation / MIDI-mapping target.

```java
public final CompoundParameter speed =
  new CompoundParameter("Speed", .2, -2, 2)
  .setDescription("Rainbow sweeps per second, negative to reverse");

public SpatialRainbowPattern(LX lx) {
  super(lx);
  addParameter("speed", this.speed);   // the string key is the save-file key
}
```

The key you pass to `addParameter` is what ends up in the project file, so
renaming it later breaks saved projects. Rename the *label* freely.

**3. `run(double deltaMs)`** — called once per frame. Write colors into the
`colors[]` buffer, indexed by `point.index`:

```java
@Override
protected void run(double deltaMs) {
  this.phase += deltaMs * .001 * this.speed.getValue();
  this.phase -= Math.floor(this.phase);

  for (LXPoint p : model.points) {
    float hue = 360f * wrap(axis.position(p) * spread - (float) this.phase);
    colors[p.index] = LXColor.hsb(hue, saturation, level);
  }
}
```

### Two things worth internalizing

**Drive animation from `deltaMs`, never a frame counter.** `deltaMs` is the time
since the last frame. Using it keeps motion at the same real-world speed whether
the engine is at 60fps or struggling, and it's what lets Chromatik render
deterministically to a video file.

**Use `p.index`, not the loop counter.** A view may be rendering a subset of the
full model, so the Nth point you iterate is not necessarily slot N in the buffer.

### The spatial coordinates LX gives you

LX precomputes these on every `LXPoint` when the model loads, so reading them in
the render loop costs nothing:

| Field | Meaning |
|---|---|
| `x`, `y`, `z` | raw position in model units |
| `xn`, `yn`, `zn` | **normalized 0-1** across the model's bounding box |
| `rn`, `rcn` | normalized radius from origin / from model center |
| `azimuth` | angle around the Y axis, `0`-`2π` |
| `elevation` | angle above the horizontal plane, `-π/2` to `+π/2` |

Prefer the normalized ones. A pattern written against `xn`/`rcn` looks right on
the dome, on a test strip, and on whatever you build next year, without touching
the code.

For a dome, `elevation` (bands sweeping up the surface) and `azimuth` (bands
rotating around it) are the two that read best from the ground. Those are both
options on the **Axis** knob — the default is elevation.

### Bending the rings: the Radius perturbation

On the **Radius** axis alone the rainbow is perfectly concentric, which reads as
flat. The **Lobes** and **Warp** knobs bend those rings into petals by offsetting
each point's radial position by a sine of its angle:

```java
final float thetaN = p.theta / TWO_PI;                        // 0-1 around
position += warp * (float) Math.sin(lobes * TWO_PI * thetaN); // N cycles per lap
```

Because `thetaN` spans exactly 0-1 for one revolution, sweeping it through
`lobes * 2π` gives exactly `lobes` complete sine cycles and lands back on the
starting value. That's why **Lobes** is an integer parameter rather than a
continuous one: a fractional lobe count wouldn't close, leaving a visible hue
seam where theta wraps from 2π back to 0.

**Warp** is in units of normalized radius, so the resulting hue swing is
`2 × warp × spread` rainbows peak-to-peak. Setting either knob to 0 disables the
perturbation, and it only applies on the Radius axis — the other five are
untouched.

Note this uses `theta` (the angle in the x-y plane), not `azimuth` (the angle
about the vertical Y axis). For a dome standing in the x-z plane those are
different angles; if the petals come out oriented oddly on your model, `azimuth`
is the one to try instead.

---

## Writing your own pattern

1. Drop a new `.java` file next to `SpatialRainbowPattern.java`, in package
   `com.starcats.blinkydome`.
2. Extend `LXPattern`, annotate it, implement `run(double deltaMs)`.
3. `./build.sh` and restart Chromatik.

That's the whole loop. Chromatik picks up any public class extending `LXPattern`,
`LXEffect`, or `LXModulator` — no manifest to edit.

The [LX source](https://github.com/heronarts/LX/tree/master/src/main/java/heronarts/lx/pattern)
is the best reference there is; the built-in patterns are readable and cover
most techniques you'd want.

### Iterating faster

Restarting Chromatik on every change gets old. Two options:

- Use `CONTENT > PACKAGES > reload` in the Chromatik UI instead of a full restart.
- For rapid experimentation, prototype in the built-in JavaScript scripting
  (`Scripts/`) and port to Java once the idea is settled.

---

## Troubleshooting

**Pattern doesn't show up after installing.**
Check `~/Chromatik/Logs/` for the most recent log — class-loading errors on
package import land there. The usual cause is an LX version mismatch: `lx.version`
in `pom.xml` must match the Chromatik you're running (check *Chromatik > About*).

**`NoSuchMethodError` / `ClassCastException` at runtime.**
The LX dependencies in `pom.xml` are scoped `provided` on purpose — compile
against them, don't bundle them. Chromatik already has those classes loaded, and
a second copy inside your jar will collide. Don't change that scope.

**Two versions of the pattern in the browser.**
Two jars are reachable under `Packages/`. `mvn`-driven builds always overwrite
the one stable filename, so this means a stray copy — a hand-placed jar, or a
versioned one left by an older build. Delete everything in `Packages/` except
`blinky-dome-patterns.jar` and rebuild.

**`error: no JDK found`.**
`build.sh` checks, in order: `JAVA_HOME`, `/usr/libexec/java_home -v 21+`,
Homebrew's keg-only openjdk paths, `/usr/lib/jvm/*`, then `javac` on your PATH —
and validates each one is a real JDK of at least version 21. Install a JDK (see
Requirements), or point at it directly with `JAVA_HOME=/path/to/jdk ./build.sh`.

**The build hangs with no output.**
Shouldn't happen now, but if it ever does: check whether a stub JVM is being
launched, with `ps ax | grep /usr/bin/java` while it's stuck. See the macOS stub
note under Requirements.

---

## Notes on this repo

The repo root is the Chromatik home directory, so `Fixtures/`, `Projects/`,
`Models/`, `Packages/` and friends are the real folders Chromatik reads. This
`Patterns/` folder is not one of them — it's Java source, which Chromatik has no
use for, and it sits at the root harmlessly because Chromatik ignores
directories it doesn't recognize. Only the build *output* crosses over, into
`Packages/`.

`build.sh` passes any arguments straight through to Maven:

```bash
./build.sh                 # clean package
./build.sh -X              # verbose Maven output, for debugging
./build.sh clean           # remove target/ and our jar from Packages/
```
