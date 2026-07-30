# blinky-dome

StarCats LED dome control software.

## Setup

Run these commands.

### macOS

```bash
# Install Chromatik, then run it once so it creates ~/Chromatik
open https://chromatik.co/download/

# Replace that folder with this repo
git clone https://github.com/star-cats/blinky-dome.git ~/Chromatik
cd ~/Chromatik

# Python, to regenerate the fixtures
brew install python@3.11

# JDK, to build the custom patterns
brew install openjdk@21

# Regenerate the fixtures and the model
python3 Generators/generate_all.py

# Build the pattern jar into Packages/
cd Patterns && ./build.sh
```

Start Chromatik and open `Projects/FullCampLayout2026.lxp`.

### Windows

In PowerShell:

```powershell
# Install Chromatik, then run it once so it creates ~\Chromatik
start https://chromatik.co/download/

# Replace that folder with this repo
git clone https://github.com/star-cats/blinky-dome.git ~\Chromatik
cd ~\Chromatik

# Python, to regenerate the fixtures
winget install Python.Python.3.11

# JDK, to build the custom patterns
winget install EclipseAdoptium.Temurin.21.JDK

# Regenerate the fixtures and the model
python Generators/generate_all.py
```

Then in Git Bash, which comes with Git for Windows, because `build.sh` needs bash:

```bash
# Build the pattern jar into Packages/
cd ~/Chromatik/Patterns && ./build.sh
```

Start Chromatik and open `Projects/FullCampLayout2026.lxp`.

Windows is not yet verified end to end. Everything above is expected to work,
but macOS is the tested path.

### Notes

The last two commands are only needed if you intend to change fixture geometry
or pattern code. The generated fixtures and the built jar are both committed, so
a fresh clone opens and runs without them, and without a license.

**This repo _is_ the Chromatik home directory.** `Fixtures/`, `Models/`,
`Projects/`, `Packages/` and friends are the real folders Chromatik reads and
writes, which is why everything works straight out of a clone. It also means
Chromatik scribbles in the working tree while it runs; `Autosave/`, `Logs/`,
`Presets/` and the auto-restored `Examples/` folders are gitignored for that
reason.

If you keep the repo somewhere other than `~/Chromatik`, point Chromatik at it
with the `--media` flag.

## Toolchain versions

Running Chromatik needs neither of these — the generated fixtures and the built
pattern jar are both committed. You only need them to _change_ things.

| Tool   | Version                    | Pinned in                                                         | Enforced by                                                         |
| ------ | -------------------------- | ----------------------------------------------------------------- | ------------------------------------------------------------------- |
| Python | 3.9+ (tested through 3.13) | [`.python-version`](.python-version) → 3.10.9                     | `Generators/constants.py` refuses to load below 3.9                 |
| JDK    | 21+                        | [`Patterns/pom.xml`](Patterns/pom.xml) → `maven.compiler.release` | `Patterns/build.sh` reads that property and checks the JDK it finds |
| Maven  | 3.9.9                      | `Patterns/build.sh`                                               | downloaded into `Patterns/.tools/` if `mvn` isn't on your PATH      |

Both versions are single-sourced: `build.sh` derives the Java it demands from
`pom.xml` rather than repeating the number, and the Python floor lives in one
constant. Bumping either means editing one place.

Python 3.9 is a real floor, not a guess — the generators use PEP 585 subscripts
(`Vec = tuple[float, float, float]`) as runtime type aliases, which 3.8 cannot
evaluate.

## Layout

| Folder        | What's in it                                                     |
| ------------- | ---------------------------------------------------------------- |
| `Fixtures/`   | `.lxf` fixture definitions, most of them generated               |
| `Models/`     | `.lxm` model files assembling fixtures into the full camp layout |
| `Projects/`   | `.lxp` project files — start with `FullCampLayout2026.lxp`       |
| `Packages/`   | the built pattern jar Chromatik loads                            |
| `Images/`     | image assets referenced by image patterns                        |
| `Data/`       | reference photos and other supporting data                       |
| `Generators/` | Python that generates the fixtures and models                    |
| `Patterns/`   | Java source for the custom pattern package                       |

## Regenerating fixtures

Fixture geometry is generated, not hand-edited. Needs Python 3.9+ (see
[Toolchain versions](#toolchain-versions)). Edit the Python in `Generators/`,
then:

```
python3 Generators/generate_all.py
```

That runs every generator in dependency order in one process. Each one also
stands alone if you are iterating on a single fixture — `python3
Generators/generate_star.py` — though `generate_full_model.py` assembles the
others, so it wants a full run behind it.

Every generator reads `Generators/constants.py` and writes into `Fixtures/` and
`Models/` via absolute paths, so the committed `.lxf`/`.lxm` files should always
be reproducible from a clean run, from any working directory.

## Custom patterns

Java patterns live in [`Patterns/`](Patterns/) and build into a Chromatik
content package:

```
cd Patterns && ./build.sh
```

That writes `Packages/blinky-dome-patterns.jar` — directly into the folder
Chromatik scans — so a restart picks up the new build. Requires a JDK 21+; the
jar is committed, so you only need to build after changing the Java. See
[Patterns/README.md](Patterns/README.md) for the tutorial.

## Image patterns: use the portable ones

Chromatik's stock image patterns load through `new File(fileName)`, resolved
against the JVM's working directory rather than the media folder — so there is
no relative form that works, and a saved project pins itself to one clone
location. Ours used to hold absolute paths under `~/Chromatik/Images/`, which
only resolved on a machine that had cloned to exactly that spot.

The 2026 projects instead use **Image (Portable)** and **Slideshow (Portable)**
from the Blinky Dome package. They subclass the built-ins — same projection,
same GIF and slideshow controls, same device UI — and override only
serialization: a file under the media root is stored as `Images/foo.png` and
resolved against whatever the media root is on the machine loading it. Paths
outside the media root are left absolute, since they were never portable
anyway. See [`MediaPath.java`](Patterns/src/main/java/com/starcats/blinkydome/MediaPath.java).

**When adding an image pattern, reach for the Portable variants**, not the stock
ones — the stock ones will happily save an absolute path that breaks on every
other machine. Keep the image file itself in `Images/` so it travels with the
clone.

A third option, for an image that belongs to a pattern rather than to a show:
ship it as a classpath resource inside the jar, the way
[`ImageTest.java`](Patterns/src/main/java/com/starcats/blinkydome/ImageTest.java)
does. Nothing to install and no path to break, but it only works from a pattern
we wrote — the built-ins need a real file on disk.
