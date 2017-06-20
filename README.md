# blinky-dome

StarCats LED dome control software.

Dependencies:
- [LX](https://github.com/heronarts/LX): LED lighting engine ([javadocs](http://heronarts.com/lx/api/index.html))
- [P3LX](https://github.com/heronarts/P3LX): Processing-based UI for LX
- [Processing](https://processing.org/)
  - Note: We do NOT use the Processing IDE or even a Processing sketch layout.  This project is pure Java, only using
    Processing for utilities and since P3LX is built on it.  Welcome to grown-up software.

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

# Run / Deploy Instructions

First, make sure you've checked out the submodules as described above.

## To Run
`./gradlew run`

You should see the Processing window running the P3LX GUI show up.

## To Deploy Locally
Gradle build scripts make a 'fatjar', meaning all dependencies are contained within a single jar.  Neat.

`./gradlew shadowJar`

Now you have an executable fatjar (synonym of shadowJar), eg:

`java -jar build/libs/blinky-dome-all.jar`

## To Deploy Embedded (raspi, odroid)
TODO: The fatjar created above should run on embedded systems, but you usually need to go through some dependency hell
 to make Processing run on embedded devices.  Has to do with jogl native jars (need the right native for the platform).
 Build scripts include the jogl-all-main runtime group, which SHOULD include the natives for the embedded platform and
 they SHOULD automatically get picked out, but, well, it's a TODO to make sure everything works.

# Setup for New Developers
Want to contribute?  Checkout the code and fire up your IDE (we use IntelliJ, anything that itegrates Gradle builds
should work.

## IntelliJ Setup
Project is built with Gradle, so IntelliJ should automagically configure and Just Work(TM).

Note: use latest version of IntelliJ.  2016.1 was a bit glitchy with this newer version of Gradle, try at least 2017.1

- Checkout repo with Git submodule initialization as described above
- File > New > Project From Existing Sources
- Select blinky-dome directory
- Hit "Import project from existing model" > "Gradle"
- Defaults should be fine
- Hit "Finish".
- Let Gradle init scripts load everything, index, do initial linking build, etc.
- Create run configuration from Gradle scripts:
  - View > Tool Windows > Gradle
  - Open up blinky-dome > Tasks > application
  - Right click on "run"
  - Hit "Run 'blinky-dome [run]'"
  - P3LX window should appear
  - You now have an IntelliJ run configuration.  You can hit the green Run triangle or green Debug bug to start it up.
