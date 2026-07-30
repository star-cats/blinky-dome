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
with the `--media` flag — but note the caveat about image paths below.

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

## Known wart: image paths are absolute

Chromatik's image pattern resolves its `fileName` with `Paths.get()`, against
the JVM's working directory rather than the media folder — there is no relative
form that works. The image references in the project files are therefore
absolute paths under `~/Chromatik/Images/`, which only resolve if you cloned to
`~/Chromatik` under a home directory of the same name.

The durable fix is to ship images as classpath resources inside the pattern jar,
the way [`Patterns/src/main/java/com/starcats/blinkydome/ImageTest.java`](Patterns/src/main/java/com/starcats/blinkydome/ImageTest.java)
already does. Patterns using bundled images have no path to break.
