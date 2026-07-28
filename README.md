# blinky-dome

StarCats LED dome control software.

## Setup

1. Install Chromatik from [chromatik.co](https://chromatik.co/download/)

1. `git clone https://github.com/star-cats/blinky-dome.git ~/blinky-dome`

1. `cd ~/blinky-dome && ./link-chromatik.sh`

That's it. You don't need a license to run the simulator.

## Custom patterns

Java patterns live in [`Patterns/`](Patterns/) and build into a Chromatik content
package. Build and install them with `cd Patterns && ./install.sh` — see
[Patterns/README.md](Patterns/README.md) for the tutorial.
