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

Once, ever:

```bash
cd Patterns
./install.sh
```

Then after every code change:

```bash
./build.sh
```

…and restart Chromatik. In the pattern browser you'll find a **Blinky Dome**
category containing **Spatial Rainbow**.

### Why install is one-time

`install.sh` doesn't copy anything. It namespaces this folder into Chromatik with
a single symlink, the same way `link-chromatik.sh` handles `Fixtures/`,
`Projects/`, and the rest of the repo:

```
~/Chromatik/Packages/blinky-dome  ->  <repo>/Patterns/blinky-dome
```

The build writes `blinky-dome/blinky-dome-patterns.jar`, which is *inside* that
symlinked folder — so a rebuild is live immediately, with no install step to
repeat. Chromatik scans `Packages/` recursively, so it finds the jar one level
down.

The jar filename deliberately has no version number in it. The symlinked folder
should only ever contain one jar; a versioned name would strand the old one there
on every version bump and Chromatik would load both, giving you duplicate-class
errors.

### Requirements

A **JDK 21 or newer**. That's it — `build.sh` downloads its own copy of Maven
into `Patterns/.tools/` if you don't already have `mvn` on your PATH.

```bash
brew install openjdk@21
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
             /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

> The JVM bundled inside `Chromatik.app` is a stripped-down runtime with no
> compiler in it, so it can't build this. You need a real JDK.

---

## What's in here

```
Patterns/
├── README.md                 you are here
├── pom.xml                   Maven build: deps, Java version, packaging
├── build.sh                  compile -> blinky-dome/blinky-dome-patterns.jar
├── install.sh                one-time symlink into ~/Chromatik/Packages
├── blinky-dome/              <- the symlinked folder; holds the built jar
└── src/main/
    ├── java/com/starcats/blinkydome/
    │   └── SpatialRainbowPattern.java     the demo pattern
    └── resources/
        └── lx.package        package metadata Chromatik reads
```

`blinky-dome/` is tracked in git (empty, via `.gitkeep`) so the symlink target
exists on a fresh clone before anything has been built. The jar inside it is
gitignored, as is `target/`, which still holds intermediate build output.

`src/main/resources/` can also hold `fixtures/`, `models/`, and `projects/`
subfolders. Those get copied into `~/Chromatik/BlinkyDome/` when the package is
imported through the Chromatik UI (the `mediaDir` field in `lx.package` picks the
folder name). We keep fixtures in the repo's top-level `Fixtures/` folder instead,
symlinked by `link-chromatik.sh` — bundling them here too would just duplicate them.

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
Two jars are reachable under `~/Chromatik/Packages`. Check both that folder and
`Patterns/blinky-dome/` for a stray jar. `mvn`-driven builds always overwrite the
one stable filename, so this usually means a hand-copied leftover — including one
from before this folder used symlinks, which `./install.sh` clears out for you.

**`error: no JDK found`.**
`build.sh` looks at `JAVA_HOME`, then `/usr/libexec/java_home -v 21+`, then
`javac` on your PATH. Install a JDK (see Requirements) or set `JAVA_HOME`
explicitly.

---

## Notes on this repo

`link-chromatik.sh` symlinks the repo's *content* folders (`Fixtures/`,
`Projects/`, `Models/`, …) into `~/Chromatik/`. `Patterns/` is deliberately not
one of them — it's Java source, which Chromatik has no use for. Only the built
package folder gets linked, and `install.sh` does that with the same namespacing
convention.

`./install.sh` takes the same flags as `link-chromatik.sh` for the same reasons:

```bash
./install.sh --dry-run     # show what would happen
./install.sh -c /path      # non-default Chromatik home ($CHROMATIK_HOME works too)
./install.sh -n my-name    # different namespace folder
./install.sh --force       # replace a symlink pointing somewhere else
./install.sh --unlink      # uninstall
```
