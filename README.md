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

See [this wiki page](https://github.com/star-cats/blinky-dome/wiki/Running-blinky-dome-on-Headless-Embedded-(raspi,-odroid))
for how to provision a raspi or ODroid with all the utilities and stuff to make it run.

**Note: Not True Headless!**  Although LX doesn't have a dependency on Processing, blinky-dome does since we rely on a
lot of Processing utils.  This means it's not *true* headless (just a 1x1 px window), but this also means you'll have to
jump through a few hoops to get Processing running on a headless device like a Raspi or ODroid.  See
[the wiki](https://github.com/star-cats/blinky-dome/wiki/Running-blinky-dome-on-Headless-Embedded-(raspi,-odroid))
for details.

# Setup for New Developers
Want to contribute?  Checkout the code and fire up your IDE (we use IntelliJ, anything that integrates Gradle builds
should work.

## Install Java

```
brew tap AdoptOpenJDK/openjdk
brew cask install adoptopenjdk8
```

macOS has a neat utility called java_home, where you can list all of the javas you have. It's like a not very helpful nvm. List all of your javas like this

```
/usr/libexec/java_home -V
```

Then in your `.bash_profile` or `.zprofile`, set a JAVA_HOME like this

```
export JAVA_HOME=`/usr/libexec/java_home -v 1.8`
```

## IntelliJ Setup

Project is built with Gradle, so IntelliJ should automagically configure and Just Work(TM) lol really?

- From the home IntelliJ IDEA screen, select "Import Project"
- Select blinky-dome directory
- Hit "Import project from existing model" > "Gradle"
- Ignore the warning that Gradle home will be missing. Select "Use Gradle 'wrapper' task configuration". Also, make sure your JVM is the 1.8 we just set before.
- Mash "Finish".

You should now see IntelliJ start indexing all of the build scripts and stuff.

- Create run configuration from Gradle scripts:
  - Run > Edit Configurations...
  - Hit the "+" and add a "Gradle" configuration
  - On the right, mash the folder button with the blue square for registered projects
  - Select the "blinky-dome" project (not :lib, or :lib:lx, or any of those)
  - Type "run" in the task name.
  - Hit "OK"

- Try running
  - Run > "Run blinky-dome[run]"
  - P3LX window should appear
  - You now have an IntelliJ run configuration.  You can hit the green Run triangle or green Debug bug to start it up.

- Optional: Create run configuration for headless mode
  - TODO: fix this...?
  - The headless run config is useful for attaching debugger to headless (non-P3LX) runs (see "Headless/Embedded" section above)
  - Same steps as above, but in the Gradle tool window, buried in blinky-dome > Tasks > other > headlessRun
  - Since it's a headless run, you won't see any window appear, but you should see application output in run console.
