# blinky-dome

StarCats LED dome control software.

Dependencies:
- [LX](https://github.com/heronarts/LX): LED lighting engine ([javadocs](http://heronarts.com/lx/api/index.html))
- [P3LX](https://github.com/heronarts/P3LX): Processing-based UI for LX
- [minim](https://github.com/ddf/Minim)@v2.2.2: Audio processing lib for Processing
- [Processing](https://processing.org/)
  - Note: We do NOT use the Processing IDE or even a Processing sketch layout.  This project is pure Java, only using
    Processing for utilities, UI rendering, and since P3LX is built on it.  Congrats, we've graduated to grown-up software. (ish.)

## Checkout Instructions (Git Submodules)
We depend on LX and P3LX, which do not follow any release/publishing process.  This project is configured to build them
 from source, which means their source needs to be checked out.  These dependencies are linked as
 [git submodules](https://git-scm.com/book/en/v2/Git-Tools-Submodules), which just means there's a few extra steps
 after cloning this repo:

 1. Clone as normal: `git clone https://github.com/star-cats/blinky-dome.git`
 1. `cd blinky-dome`
 1. Init submodules: `git submodule init`
 1. Check out submodule source: `git submodule update`

LX and P3LX source will now be available in `lib/lx/git_submodule` and `lib/p3lx/git_submodule`.  Our build scripts are
 configured to build them from there, and their source files should be browsable from your IDE with no external
 project setup.

**DEPENDENCY VERSION WARNING**: Since LX and P3LX don't follow any semver release/publishing process, we just rely on
specific checkouts specified in the `.gitmodules` file and build from source.  If your `git status` says your submodules
are out of date and you can't get back in sync, just `rm -r lib/**/git_submodule` then `git submodule update` to
re-clone the appropriate revision.


# Run / Deploy Instructions

First, make sure you've checked out the submodules as described above.

## To Run
`./run.sh`

You should see the Processing window running the P3LX GUI show up.

On OSX you may be asked to install `wget` and `coreutils` via brew.

```sh
$ /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
$ brew install coreutils wget
```