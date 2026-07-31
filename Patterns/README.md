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
    │   ├── BeatTracker.java               shared on-beat clock (a modulator)
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

## Beat Tracker

A **modulator**, not a pattern: it produces a shared, steady on-beat clock that
any number of patterns can hook into, so the whole rig pulses together instead of
each pattern doing its own audio analysis.

Wiring it up, once per project:

1. Add a **Band Gate** modulator and aim it at the bass (that is Chromatik's own
   thresholder — it is the thing that decides a kick just happened).
2. Add a **Beat Tracker** modulator (Blinky Dome category).
3. Map the Band Gate onto the tracker's **Input** knob.
4. Map the tracker onto whatever you want moving in time.

The tracker treats each rising edge on `Input` as a *sighting* of a beat,
averages the gaps between sightings into a tempo, then runs its own clock at that
tempo and emits the beat from the clock rather than from the gate. That
indirection is the whole point: a bass gate fires late on a soft kick, twice on a
sloppy one, and not at all during a breakdown, and anything driven straight off
it inherits all of that. Driven off the averaged clock, the gate only has to be
right *on average*.

Four things come out:

| Output | What it is | Use it for |
| --- | --- | --- |
| the modulator's value | 0-1 ramp resetting each beat | anything continuous — this is what you get using it as a modulation source |
| `Beat` | trigger on each beat | anything discrete |
| `BPM` | tracked tempo | display, or driving other rates |
| `Conf` | how consistent recent intervals are | deciding how much to trust the beat |

And the knobs that matter: **Thresh** is how far the input must rise to count;
**Min BPM** sets the tempo range (intervals fold into Min BPM up to twice Min
BPM, so a gate hitting every eighth note still reports the right tempo); **Avg**
is the moving-average window, higher being steadier but slower to follow; **Lock**
is how hard each sighting pulls the clock back into alignment, 0 free-running and
1 snapping. **Relearn** throws the tempo away and starts over.

### The chart

The device panel draws what the tracker is hearing against what it believes:
gate sightings as circles, predicted beats as vertical lines, scrolling right to
left over a 3-second window (`WINDOW_SECONDS` in `UIBeatTracker.java`). Current
BPM sits top-left, confidence top-right.

Read it while tuning **Thresh**. Circles sitting on the lines means it is
locked. Circles scattered between lines means the gate is firing on things that
are not the beat — raise the threshold. No circles at all means it never fires —
lower it. The circles are logged before any filtering, so hits the tracker
decides to *reject* still show up; watching one sit off the grid while the lines
keep their spacing is the tracker doing its job.

`UIBeatTracker` is a separate class from `BeatTracker` on purpose: it is the only
half that imports glx, so the modulator itself stays loadable and testable
without a UI. Chromatik pairs the two automatically — its class loader registers
anything implementing `UIModulatorControls` and reads the modulator type back off
the generic parameter, which is why that interface has to be declared directly on
the class.

Measured against synthetic gates at 128 BPM over 60s runs — a perfect gate, one
jittering ±20ms, one missing 30% of beats, one firing double-time, one with 25%
spurious extra hits, and one going silent for 12s — the tracker holds the tempo
to within 2% and the output stays steady to about one frame. The one thing it
cannot do is find the downbeat in a gate that fires evenly on every eighth note:
nothing in that signal distinguishes the beat from the off-beat, so it locks
steadily onto whichever it saw first.

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
