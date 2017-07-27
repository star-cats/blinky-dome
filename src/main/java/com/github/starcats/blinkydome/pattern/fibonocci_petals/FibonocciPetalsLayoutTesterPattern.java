package com.github.starcats.blinkydome.pattern.fibonocci_petals;

import com.github.starcats.blinkydome.model.FibonocciPetalsModel;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.SawLFO;
import heronarts.lx.parameter.DiscreteParameter;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pattern that selects just a single LED.  Useful for geometry checking
 */
public class FibonocciPetalsLayoutTesterPattern extends LXPattern {
  public final int LEDS_PER_PORT = 64; // fadecandy

  public final DiscreteParameter ledSelect;
  public final DiscreteParameter portSelect; // Which port to select

  public final DiscreteParameter spiralSelect;
  public final DiscreteParameter petalSelect;
  public final DiscreteParameter cwSelect;

  private SawLFO hueModulator = new SawLFO(0, 360, 1000);

  public FibonocciPetalsLayoutTesterPattern(LX lx) {
    super(lx);

    FibonocciPetalsModel model = (FibonocciPetalsModel)lx.model;

    addModulator(hueModulator).start();

    // LED AND PORT SELECTORS
    // --------
    // Allows to hone in on a specific LED and FC port.  Set to -1 to disable both.
    ledSelect = new DiscreteParameter("LED", -1, lx.model.points.length);

    portSelect = new DiscreteParameter("Port", -1, (int)Math.floor(lx.model.points.length / LEDS_PER_PORT));
    portSelect.addListener(portSelect -> {
      if (portSelect.getValue() < 0) {
        ledSelect.setRange(-1, lx.model.points.length);
      } else {
        ledSelect.setRange(-1, LEDS_PER_PORT);
      }
      ledSelect.setValue(0);
    });
    portSelect.setValue(-1);


    // PETAL SELECTORS
    // -------------------
    spiralSelect = new DiscreteParameter("spiral", -2, model.cwSpirals.size());
    petalSelect = new DiscreteParameter("petal", -1, model.allPetals.size());
    cwSelect = new DiscreteParameter("side", new String[] {"both", "cw", "ccw"});

    // when you select a spiral, petal select updates with appropriate num petals
    spiralSelect.addListener(parameter -> {
      if (spiralSelect.getValue() < 0) {
        petalSelect.setRange(-1, model.allPetals.size());
      } else {
        petalSelect.setRange(-1, model.cwSpirals.get((spiralSelect.getValuei())).getPetals().size());
      }
    });



    // REGISTER ALL PARAMETERS in order
    // ---------
    addParameter(portSelect);
    addParameter(ledSelect);

    addParameter(spiralSelect);
    addParameter(petalSelect);
    addParameter(cwSelect);
  }

  public void run(double deltaMs) {
    if (portSelect.getValuei() >= 0 || ledSelect.getValuei() >= 0) {
      runPortAndLedSelect();
      return;
    }

    FibonocciPetalsModel model = (FibonocciPetalsModel)lx.model;

    // Set all to black
    for (LXPoint point : model.getPoints()) {
      colors[point.index] = 0;
    }

    List<FibonocciPetalsModel.Petal> petalsToUse;
    if (spiralSelect.getValuei() < -1) {
      if (petalSelect.getValuei() < 0) {
        petalsToUse = model.allPetals;
      } else {
        petalsToUse = new LinkedList<>();
        petalsToUse.add(model.allPetals.get(petalSelect.getValuei()));
      }
    } else {
      // -1 would be the petalSelect-th petal from each spiral, not absolute petal numbers like above
      petalsToUse = model.getPetals(spiralSelect.getValuei(), petalSelect.getValuei());
    }


    List<LXPoint> pointsToIlluminate;
    switch (cwSelect.getValuei()) {
      case 1:
        pointsToIlluminate = petalsToUse.stream()
            .flatMap(petal -> petal.getCwSide().getPoints().stream())
            .collect(Collectors.toList());
        break;
      case 2:
        pointsToIlluminate = petalsToUse.stream()
            .flatMap(petal -> petal.getCcwSide().getPoints().stream())
            .collect(Collectors.toList());
        break;
      case 0:
      default:
        pointsToIlluminate = petalsToUse.stream()
            .flatMap(petal -> petal.getPoints().stream())
            .collect(Collectors.toList());
    }

    for (LXPoint point : pointsToIlluminate) {
      colors[point.index] = LXColor.hsb(hueModulator.getValue(), 100, 100);
    }
  }

  public void runPortAndLedSelect() {
    int selectedLed;
    if (portSelect.getValue() < 1) {
      selectedLed = ledSelect.getValuei();
    } else {
      selectedLed = 64 * portSelect.getValuei() + ledSelect.getValuei();
    }

    for (LXPoint point : model.getPoints()) {
      if (point.index == selectedLed) {
        colors[point.index] = LXColor.hsb(hueModulator.getValue(), 100, 100);
      } else {
        colors[point.index] = 0;
      }
    }
  }
}
