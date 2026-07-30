# blinky-dome

StarCats LED dome control software.

## Setup

1. Install Chromatik from [chromatik.co](https://chromatik.co/download/) and run it
   once, so it creates `~/Chromatik`.

1. Replace that folder with this repo:

   ```
   mv ~/Chromatik ~/Chromatik.bak
   git clone https://github.com/star-cats/blinky-dome.git ~/Chromatik
   ```

1. Start Chromatik and open `Projects/FullCampLayout2026.lxp`.

That's it — no install script, no symlinks. You don't need a license to run the
simulator.

**This repo *is* the Chromatik home directory.** `Fixtures/`, `Models/`,
`Projects/`, `Packages/` and friends are the real folders Chromatik reads and
writes, which is why everything works straight out of a clone. It also means
Chromatik scribbles in the working tree while it runs; `Autosave/`, `Logs/`,
`Presets/` and the auto-restored `Examples/` folders are gitignored for that
reason.

If you keep the repo somewhere other than `~/Chromatik`, point Chromatik at it
with the `--media` flag.

## Layout

| Folder | What's in it |
| --- | --- |
| `Fixtures/` | `.lxf` fixture definitions, most of them generated |
| `Models/` | `.lxm` model files assembling fixtures into the full camp layout |
| `Projects/` | `.lxp` project files — start with `FullCampLayout2026.lxp` |
| `Packages/` | the built pattern jar Chromatik loads |
| `Images/` | image assets referenced by image patterns |
| `Data/` | reference photos and other supporting data |
| `Generators/` | Python that generates the fixtures and models |
| `Patterns/` | Java source for the custom pattern package |

## Regenerating fixtures

Fixture geometry is generated, not hand-edited. Edit the Python in
`Generators/`, then:

```
./generate_all.sh
```

Every generator reads `Generators/constants.py` and writes into `Fixtures/` and
`Models/`, so the committed `.lxf`/`.lxm` files should always be reproducible
from a clean run.

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
