# blinky-dome

StarCats LED dome control software.

Dependencies:
- [LX](https://github.com/heronarts/LX): LED lighting engine ([javadocs](http://heronarts.com/lx/api/index.html))
- [P3LX](https://github.com/heronarts/P3LX): Processing-based UI for LX
- [Processing](https://processing.org/)
  - Note: We do NOT use the Processing IDE or even a Processing sketch layout.  This project is pure Java, only using
    Processing for utilities and since P3LX is built on it.  Welcome to grown-up software.

# Setup for New Developers
Want to contribute?  Checkout the code and fire up your IDE (we use IntelliJ, anything that itegrates Gradle builds
should work.

## Checkout Instructions (Git Submodules)
We depend on LX and P3LX, which do not follow any release/publishing process.  This project is configured to build them
 from source, which means their source needs to be checked out.  These dependencies are linked as
 [git submodules](https://git-scm.com/book/en/v2/Git-Tools-Submodules), which just means there's a few extra steps
 after cloning this repo:

 1. Clone as normal: `git clone https://github.com/star-cats/blinky-dome.git`
 1. `cd blinky-dome`
 1. Init submodules: `git submodule init`
 1. Clone submodules: `git submodule update`

LX and P3LX source will now be available in `lib_submodules/LX` and `lib_submodules/P3LX`.  Our build scripts are
 configured to build from there, and their source files should be browsable from your IDE.

**DEPENDENCY VERSION WARNING**: Since LX and P3LX don't follow any release/publishing process, we just rely on their
latest HEAD checkouts (they don't do any git tags or release branches).  If they change their API, we're SOL.
If you run into weird LX or P3LX compile errors, try checking out a previous version of them.  As of June 18 2017, try:
  - LX: `git checkout ff98f6d54`
  - P3LX: `git checkout 9096d47b7`

## IntelliJ Setup
Project is built with Gradle, so IntelliJ should automagically configure and Just Work(TM).

- Checkout with Git submodule initialization as described above
- File > New > Project From Existing Sources
- Select blinky-dome directory
- Hit "Import project from existing model" > "Gradle"
- Defaults should be fine
- Hit "Finish"
- Navigate to src > blinkydome > com.github.starcats.blinkydome.AppGui
- Right click on AppGui class and hit 'Run AppGui.main()'
- If all worked, you should see the Processing window with the P3LX GUI
