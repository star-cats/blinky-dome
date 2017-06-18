import heronarts.lx.*;
import heronarts.lx.color.*;
import heronarts.lx.modulator.*;
import heronarts.lx.parameter.*;

 /**
  * A basic example lighting the dome in one color
  **/

class StaticRainbowPattern extends Pattern {

  StaticRainbowPattern(LX lx) {
    super(lx);
  }

  public void run(double deltaMs) {
    
    // set everything to teal
    int c;
    for (LED led : leds) {
      c = lx.hsb((float)(led.ledIndex*360.0/105), 100, 100);
      setLEDColor(led, c);
    }
  }
}

class BasicAnimationPattern extends Pattern {
  // Parameters are values you can change from the UI
  // access them in the run method using parameterName.getValue()
  // Which parameter you use depends on the type of value you want to store:
  // BasicParameter=float/double, BooleanParameter=boolean, DiscreteParameter=int
  //
  // BasicParameter(label, initialValue, (optional=1)max) defaultRange=[0-max]
  // BasicParameter(label, initialValue, min, max, (optional)scalingEnum)
  // Usage: double = parameter.getValue(), float = parameter.getValuef()
  //
  // BooleanParameter(label, initialValue)
  // Usage: boolean = parameter.isOn()
  //
  // DiscreteParameter(label, max) initialValue=0, range=[0, max-1]
  // DiscreteParameter(label, min, max) initialValue=min, range=[min, max-1]
  // DiscreteParameter(label, initialValue, min, max) range=[min, max-1]
  // Usage: int = parameter.getValuei()
  //
  BasicParameter parameterName = new BasicParameter("NAME");

  // Modulators are values that automatically change over time,
  // depending on their formula and how you configure them.
  // 
  // For any modulator constructor value, you can supply either a
  // constant, or you can supply another parameter or modulator
  //
  // Usage: double = modulator.getValue(), float = modulator.getValuef()
  //
  // Basic modulators that repeat every x milliseconds:
  // SinLFO(startValue, endValue, periodMs)
  // SawLFO(startValue, endValue, periodMs)
  // SquareLFO(startValue, endValue, periodMs)
  // TriangleLFO(startValue, endValue, periodMs)
  //
  // Goes for x milliseconds then stops:
  // LinearEnvelope(startValue, endValue, periodMs)
  // QuadraticEnvelope(startValue, endValue, periodMs)
  //
  // Goes on forever:
  // Accelerator(initialValue, initialVelocity, acceleration)
  //
  // Returns a value of 1 once every x milliseconds, otherwise returns 0
  // Click(periodMs)
  //
  // This modulator dampens another parameter or modulator, to soften sudden changes
  // DampedParameter(anotherParameter, velocity, (optional=0)acceleration)
  //
  SinLFO modulatorName = new SinLFO(0, 360, 10000);

  // Make sure to change the name here to match your pattern class name above
  BasicAnimationPattern(LX lx) {
    super(lx);

    // Add each parameter here, to add it to the UI
    addParameter(parameterName);

    // Add each modulator here, to add to the engine
    addModulator(modulatorName).start();
  }
  
  // This method gets called once per frame, typically 60 times per second.
  public void run(double deltaMs) {
    // Write your pattern logic here.
    //
    // Define modulators above to simplify things as much as you can,
    // since they will run on their own and are based on time elapsed.
    //
    // You can call modulators and parameters in this method to get their current value
    // Use .getValue() for a double or .getValuef() for a float
    //
    // Use setColor(ledIndex, colorValueInARGB) to set each LED to a certain color
    // If you don't set a color for a certain led this frame, it will
    // use the color from the last frame.

    int c = lx.hsb(modulatorName.getValuef(), 100, 100);
    // Iterate over all LEDs
    for (LED led : leds) {
      setLEDColor(led, c);
    }
  }
}

class RainbowZPattern extends Pattern {

  SinLFO globalFade = new SinLFO(0, 360, 10000);

  RainbowZPattern(LX lx) {
    super(lx);
    this.addModulator(globalFade).start();
  }

  public void run(double deltaMs) {
    int c;
    for (LED led : leds) {
      c = lx.hsb(led.z/196 * 360 + globalFade.getValuef(), 100, 80);
      setLEDColor(led, c);
    }
  }
}

class OrientationTestPattern extends Pattern {

  OrientationTestPattern(LX lx) {
    super(lx);
  }

  public void run(double deltaMs) {
    int c;
    for (LED led : leds) {
      c = lx.hsb(led.pointsUp ? 0 : 180, 100, 80);
      setLEDColor(led, c);
    }
  }
}

class IndexTestPattern extends Pattern {

  IndexTestPattern(LX lx) {
    super(lx);
  }

  public void run(double deltaMs) {
    int c;
    for (LED led : leds) {
      c = lx.hsb(led.triangleIndex * 20, 100, 80);
      setLEDColor(led, c);
    }
  }
}

class SubindexTestPattern extends Pattern {

  SubindexTestPattern(LX lx) {
    super(lx);
  }

  public void run(double deltaMs) {
    int c;
    for (LED led : leds) {
      c = lx.hsb(led.triangleSubindex * 20, 100, 80);
      setLEDColor(led, c);
    }
  }
}

class LayerTestPattern extends Pattern {

  LayerTestPattern(LX lx) {
    super(lx);
  }

  public void run(double deltaMs) {
    int c;
    for (LED led : leds) {
      c = lx.hsb(led.layer * 50, 100, 80);
      setLEDColor(led, c);
    }
  }
}