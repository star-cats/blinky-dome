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

**DEPENDENCY VERSION WARNING**: Since LX and P3LX don't follow any release/publishing process, we just rely on their
latest HEAD checkouts (they don't do any git tags or release branches).  If they change their API, we're SOL.
If you run into weird LX or P3LX compile errors, try checking out a previous version of them.  As of June 18 2017, try:
  - lx: `git checkout ff98f6d54`
  - p3lx: `git checkout 9096d47b7`

# Run / Deploy Instructions

First, make sure you've checked out the submodules as described above.

## To Run
`./gradlew run`

You should see the Processing window running the P3LX GUI show up.

## To Deploy Locally
Gradle build scripts make a 'fatjar', meaning all dependencies are contained within a single jar.  Neat.

`./gradlew shadowJar`

Now you have an executable fatjar (shadowJar). Which means, you can simply do:

`java -jar build/libs/blinky-dome-all.jar`

## Headless/Embedded (raspi, odroid)

We also have a headless mode that doesn't run P3LX, suited for small single-board computers like a Raspi or ODroid.
Not running P3LX saves some CPU work (eg no OpenGL rendering) so your limited CPU can just crunch your animations.

### To Run (/Debug) Headless
`./gradlew headlessRun`

You won't see a window, but you should see a new processing app start up in your OS's task manager.

You can also add an IntelliJ run configuration against this gradle task to allow you to debug your headless runs.

### To Deploy Headless
Build a fatjar (shadowjar):

`./gradlew headlessShadowJar`

Now you have an executable fatjar (shadowJar). Which means, you can simply do:

`java -jar build/libs/blinky-dome-headless-all.jar`

Copy it onto your embdedded device of choice.

**Note: Not True Headless!**  Although LX doesn't have a dependency on Processing, blinky-dome does since we rely on a
lot of Processing utils.  This means it's not *true* headless (just a 1x1 px window), but this also means you'll have to
jump through a few hoops to get Processing running on a headless device like a Raspi or ODroid.

TODO: detailed instructions on how to get it running on raspi/odroid. For now, google "raspi processing headless"...
basically you'll have to setup a virtual frame buffer `Xvfb` and run the fatjar like
`sudo DISPLAY=:1 java -jar blinky-dome-headless-all.jar`


# Setup for New Developers
Want to contribute?  Checkout the code and fire up your IDE (we use IntelliJ, anything that integrates Gradle builds
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
