package com.github.starcats.blinkydome.pattern;

import com.github.starcats.blinkydome.color.ColorMappingSourceClan;
import com.github.starcats.blinkydome.pattern.perlin.ColorMappingSourceColorizer;
import com.github.starcats.blinkydome.pattern.perlin.PerlinNoiseExplorer;
import com.github.starcats.blinkydome.util.BooleanParameterImpulse;
import heronarts.lx.LX;
import heronarts.lx.LXModulationEngine;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.LXWaveshape;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXCompoundModulation;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.transform.LXVector;
import processing.core.PApplet;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Breathing pattern colorized with perlin noise
 */
public class PerlinBreathing extends LXPattern {

  public final CompoundParameter perlinNoiseFieldZoom;
  public final CompoundParameter breathingPeriodMs;
  public final EnumParameter<LedFilterType> ledFilteringParam;
  public final CompoundParameter rotateColorProbability;

  /**
   * When breathing, we can also filter LEDs with the breath
   */
  public enum LedFilterType {
    /** No filtering */
    NONE,

    /** Uniformly scale brightness proportional to breathing position */
    BREATH_FADE,

    /** Scale brightness proportional to noise mapping along with breathing position*/
    NOISE_FADE,

    /** When an LED's noise mapping value drops below breathing position, it just turns off */
    CUTOFF_DISCRETE,

    /** Apply the {@link LedFilterType#NOISE_FADE} only when an LED's noise mapping value drops below breathing position */
    CUTOFF_NOISE_FADE
  }


  /**
   * Defines the breathing shape.
   *
   * "Position" of breath is basically how much air you're holding.  Out-breath is you've just exhaled everything,
   * in-breath is you've just inhaled everything.
   *
   * "Speed" of breath is the rate at which you're inhaling air.  A simple breath model might have breath speed
   * as the derivative of breath position -- speed is positive while inhaling and negative while exhaling.
   *
   * See {@link DefaultPositionWaveshape} and {@link DefaultSpeedWaveshape} for example easings
   */
  public interface BreathEasingSupplier {
    /** Supplies position of the breath: 0 is out-breath, 1 is in-breath */
    LXWaveshape getPosition();

    /**
     * Supplies the magnitude of the perlin noise field travel vector.
     * In general, this should be the derivative of position.
     */
    SpeedWaveshape getSpeed();


    // Some static defaults that can be used:

    static BreathEasingSupplier SIN = new DefaultBreathEasingSupplier(
        DefaultPositionWaveshape.SIN, DefaultSpeedWaveshape.DYDX_SIN
    );

    static BreathEasingSupplier EXP_OUT = new DefaultBreathEasingSupplier(
        DefaultPositionWaveshape.EXP_OUT_EXP_OUT, DefaultSpeedWaveshape.DYDX_EXP_OUT_EXP_OUT
    );

    static BreathEasingSupplier EXP_OUT_CUBIC_INOUT = new DefaultBreathEasingSupplier(
        DefaultPositionWaveshape.EXP_OUT_CUBIC_INOUT, DefaultSpeedWaveshape.DYDX_EXP_OUT_CUBIC_INOUT
    );
  }

  /**
   * The speed waveform is used to modulate the *magnitude* of the speed through the perlin noise field.
   *
   * Since we're specifying the *magnitude*, the speed waveform value 0-1 is always interpreted as positive modulation.
   * Implement {@link #isNegative(double)} to indicate the direction.
   *
   * This also means {@link #compute(double)} implementation will often make positive any 'negative' values
   */
  public interface SpeedWaveshape extends LXWaveshape {
    /**
     * compute() must always return a normalized positive magnitude 0-1, but this should return true when the current
     * basis is to be interpreted as a negative (reverse) speed.
     *
     * In other words, {@link #compute} figures out magnitude, this figures out direction
     */
    boolean isNegative(double basis);
  }

  private static final String LABEL_BREATH_POSITION = "breath position";
  private static final String LABEL_BREATH_SPEED = "breath speed";


  private final PerlinNoiseExplorer perlinNoiseField;
  private final ColorMappingSourceClan colorSource;
  private final BooleanParameterImpulse colorSourceRandomSourceTrigger;
  private final List<? extends LXPoint> points;
  private final ColorMappingSourceColorizer colorizer;
  private final double[] positionLfoValueProvider;
  private boolean lastWasNegative = false;
  private SyncedVariableLFO _positionLFO;
  private SyncedVariableLFO _speedLFO;

  private final LXVector up;
  private final LXVector down;


  public PerlinBreathing(
      LX lx,
      PApplet p,
      List<? extends LXPoint> points,
      ColorMappingSourceClan colorSource,
      LXVector upVector,
      LXVector downVector
  ) {
    this(lx, p, points, colorSource, upVector, downVector, BreathEasingSupplier.EXP_OUT_CUBIC_INOUT);
  }

  public PerlinBreathing(
      LX lx,
      PApplet p,
      List<? extends LXPoint> points,
      ColorMappingSourceClan colorSource,
      LXVector upVector,
      LXVector downVector,
      BreathEasingSupplier breathEasingSupplier
  ) {
    super(lx);

    this.points = points;
    this.up = upVector;
    this.down = downVector;

    breathingPeriodMs = new CompoundParameter("period", 10000, 500, 20000);
    breathingPeriodMs
        .setDescription("Period (ms) of one breath")
        .setUnits(LXParameter.Units.MILLISECONDS);
    addParameter(breathingPeriodMs);

    ledFilteringParam = new EnumParameter<>("led filter", LedFilterType.NOISE_FADE);
    ledFilteringParam.setDescription("When breathing, we can also filter LEDs proportional to the breath");
    addParameter(ledFilteringParam);

    ModulatorSyncer syncer = new ModulatorSyncer(breathingPeriodMs);


    List<BreathEasingSupplier> defaultBreathEasings = Arrays.asList(
        BreathEasingSupplier.SIN,
        BreathEasingSupplier.EXP_OUT,
        BreathEasingSupplier.EXP_OUT_CUBIC_INOUT
    );
    if (!defaultBreathEasings.contains(breathEasingSupplier)) {
      defaultBreathEasings.add(breathEasingSupplier);
    }

    LXModulationEngine.LXModulatorFactory<SyncedVariableLFO> modulatorsFactory = (lx2, label) -> {
      SyncedVariableLFO lfo;
      if (label.equals(LABEL_BREATH_POSITION)) {
        lfo = new SyncedVariableLFO(syncer, LABEL_BREATH_POSITION,
            defaultBreathEasings.stream().map(BreathEasingSupplier::getPosition).toArray(LXWaveshape[]::new)
        );
        lfo.waveshape.setValue(breathEasingSupplier.getPosition());

      } else if (label.equals(LABEL_BREATH_SPEED)) {
        lfo = new SyncedVariableLFO(syncer, LABEL_BREATH_SPEED,
            defaultBreathEasings.stream().map(BreathEasingSupplier::getSpeed).toArray(LXWaveshape[]::new)
        );
        lfo.waveshape.setValue(breathEasingSupplier.getSpeed());

      } else {
        throw new RuntimeException("Unknown label to create: " + label);
      }

      lfo.start();
      return lfo;
    };

    // Teach modulation engine how to create these
    this.modulation.getModulatorFactoryRegistry().register(SyncedVariableLFO.class, modulatorsFactory);



    perlinNoiseField = new PerlinNoiseExplorer(p, points, "br", "breathing");
    perlinNoiseFieldZoom = perlinNoiseField.noiseZoom; // expose publicly
    addParameter(perlinNoiseField.noiseZoom);


    // The perlin field's speedLFO should always stay at 0.  Idea is the speedLFO modulates the speed parameter,
    // moving it up and down with the up and down motion. The base value should stay at 0.
    perlinNoiseField.noiseSpeed
        .setDescription("Breathing speed.  Leave at base 0, and just change the modulation amount in the 'breath " +
            "speed' modulator")
        .setValue(0);


    addParameter(perlinNoiseField.noiseSpeed); // adding it to see value of modulation, but not needed.


    // init speed direction
    perlinNoiseField.getTravelVector().set(up);


    // provide a memoized reference to each tick's positionLFO.getValue() so can reference it inside colorizer without
    // recalculating it for every pixel
    positionLfoValueProvider = new double[] {0.};

    this.colorSource = colorSource;
    this.colorSourceRandomSourceTrigger = BooleanParameterImpulse.makeImpulseFor(
        colorSource.getRandomSourceTrigger(), this, "trigger random source"
    );

    this.colorizer = new ColorMappingSourceColorizer(perlinNoiseField, colorSource) {
      @Override
      public int getColor(LXPoint point) {
        float noiseValue = noiseSource.getNoise(point.index);

        double pos = positionLfoValueProvider[0];
        int color = getColorSource().getColor(noiseValue);

        // Modulate which pixels get colored according to position:
        // Everything gets a color mapping only at full pos, below that which pixels get colored is proportional to position
        if ( ledFilteringParam.getEnum() == LedFilterType.NOISE_FADE ||
             (ledFilteringParam.getEnum() == LedFilterType.CUTOFF_NOISE_FADE && noiseValue > pos))
        {
          return LXColor.scaleBrightness(
              color,
              Math.min(1f, (float) (pos * (1 - (noiseValue - pos))))
          );

        } else if (ledFilteringParam.getEnum() == LedFilterType.CUTOFF_DISCRETE && noiseValue > pos) {
          return LXColor.BLACK;

        } else if (ledFilteringParam.getEnum() == LedFilterType.BREATH_FADE) {
          return LXColor.scaleBrightness(color, (float) pos);
        }

        return color;
      }
    };


    rotateColorProbability = new CompoundParameter("rotate color", 0.25, 0., 1.);
    rotateColorProbability.setDescription("Probability that color source will rotate in between breaths (set to 0 to " +
        "turn off color rotation)");
    addParameter(rotateColorProbability);
  }

  /**
   * Initializes the breathing modulators.  To be called on initial construction... construction by JSON deserialization
   * should create them automatically.
   * @return this, for chaining
   */
  public PerlinBreathing initModulators() {
    // Use VariableLFO's to have P3LX give waveform visualization.
    // (add as a user modulator so that shows up in P3LX)
    // modulatorFactory in constructor taught the engine how to construct these classes
    this.modulation.addModulator(SyncedVariableLFO.class, LABEL_BREATH_POSITION);
    SyncedVariableLFO speedLFO = this.modulation.addModulator(SyncedVariableLFO.class, LABEL_BREATH_SPEED);

    LXCompoundModulation speedModulation = new LXCompoundModulation(speedLFO, perlinNoiseField.noiseSpeed);
    speedModulation.label.setValue("speed modulation");
    this.modulation.addModulation(speedModulation); // add it to modulator UI in P3LX
    speedModulation.range.setValue(0.20);

    return this;
  }

  private boolean isSpeedNegative() {
    return ((SpeedWaveshape) getSpeedLFO().waveshape.getObject()).isNegative(getSpeedLFO().getBasis());
  }


  @Override
  protected void run(double deltaMs) {
    positionLfoValueProvider[0] = getPositionLFO().getValue();

    boolean isNegative = isSpeedNegative();
    if (lastWasNegative != isNegative) {
      if (isNegative) {
        perlinNoiseField.getTravelVector().set(down);
      } else {
        perlinNoiseField.getTravelVector().set(up);
      }
    }
    lastWasNegative = isNegative;


    perlinNoiseField.step(deltaMs);

    for (LXPoint point : points) {
      setColor(point.index, colorizer.getColor(point));
    }

    if (getPositionLFO().loop() && Math.random() < rotateColorProbability.getValue()) {
      colorSourceRandomSourceTrigger.trigger();
    }

  }

  public SyncedVariableLFO getPositionLFO() {
    // _positionLFO may be created manually or from deserialization.  Either way, memoize on first access
    if (_positionLFO == null) {
      _positionLFO = (SyncedVariableLFO) this.modulation.getModulator(LABEL_BREATH_POSITION);
    }

    return _positionLFO;
  }

  public SyncedVariableLFO getSpeedLFO() {
    // _speedLFO may be created manually or from deserialization.  Either way, memoize on first access
    if (_speedLFO == null) {
      _speedLFO = (SyncedVariableLFO) this.modulation.getModulator(LABEL_BREATH_SPEED);
    }

    return _speedLFO;
  }

  /**
   * @return the modulation range for the noise speed modulator.  Used to tweak speed per model
   */
  public BoundedParameter getSpeedModulationRange() {
    return perlinNoiseField.noiseSpeed.modulations.get(0).range;
  }


  private enum DefaultPositionWaveshape implements LXWaveshape {
    SIN {
      @Override
      public double compute(double basis) {
        return Math.sin(2 * Math.PI * basis) / 2 + 0.5;
      }
    },

    /**
     * Exponential-out for both in-breaths and out-breaths -- start quickly, long finish.
     * See https://github.com/d3/d3-ease#easeExpOut
     */
    EXP_OUT_EXP_OUT {
      @Override
      public double compute(double basis) {
        if (basis < 0.5) {
          // First half, in-breath:
          //   1 - Math.pow(2, -10 * basis), but scale basis by 2 to get full 0-1 in formula
          return 1 - Math.pow(2, -10 * basis * 2);
        } else {
          // Second half, out-breath:
          //   1 - <inbreath-easing>, but adjust basis so it looks like full 0-1 in formula
          return Math.pow(2, -10 * (basis - 0.5) * 2);
        }
      }

      public String toString() {
        return "EXP_OUT";
      }
    },

    /**
     * Exponential-out for in-breaths (start quickly, long finish),
     * cubic inout for out-breaths (S-shaped breathout)
     * See https://github.com/d3/d3-ease#easeExpOut, https://github.com/d3/d3-ease#easeCubicInOut
     */
    EXP_OUT_CUBIC_INOUT {
      @Override
      public double compute(double basis) {
        if (basis < 0.5) {
          // First half, in-breath:
          //   1 - Math.pow(2, -10 * basis), but scale basis by 2 to get full 0-1 in formula
          return 1 - Math.pow(2, -10 * basis * 2);

        } else {
          // Second half, out-breath: cubic inout
          double t = (basis - 0.5) * 2; // scale basis to get full 0-1

          // https://github.com/d3/d3-ease/blob/master/src/cubic.js#L9
          return 1 - ((t *= 2) <= 1  ?  t * t * t  :  (t -= 2) * t * t + 2) / 2;
        }
      }

      public String toString() {
        return "EXP+CUBIC";
      }
    };

    @Override
    public double invert(double value, double basisHint) {
      // TODO: Don't call setValue() on any of these modulators and we'll be fine
      throw new UnsupportedOperationException("TODO");
    }
  }

  private enum DefaultSpeedWaveshape implements SpeedWaveshape {
    /** derivative of {@link DefaultPositionWaveshape#SIN} */
    DYDX_SIN {
      @Override
      public double compute(double basis) {
        return Math.abs(
            Math.cos(2 * Math.PI * basis)
        );
      }

      @Override
      public boolean isNegative(double basis) {
        // Return true when cosine is negative
        return basis > 0.25 && basis < 0.75;
      }

      public String toString() {
        return "SIN";
      }
    },

    /** Derivative of {@link DefaultPositionWaveshape#EXP_OUT_EXP_OUT} */
    DYDX_EXP_OUT_EXP_OUT {
      @Override
      public double compute(double basis) {
        // Derivative of position (use wolfram alpha)
        // Derivative is the same in both time frames, just negative above 0.5.
        // Since we always return positive magnitude for speed, use same formula for both, just adjust basis
        // so it always scales out to full 0-1

        if (basis < 0.5) {
          basis = basis * 2;
        } else {
          basis = (basis - 0.5) * 2;
        }

        return Math.min(
            1, // clip speed at normalized 1
            Math.log(32) * Math.pow(2, 1 - 10 * basis)
        );
      }

      @Override
      public boolean isNegative(double basis) {
        return basis > 0.5;
      }

      public String toString() {
        return "EXP_OUT";
      }
    },

    /** Derivative of {@link DefaultPositionWaveshape#EXP_OUT_CUBIC_INOUT} */
    DYDX_EXP_OUT_CUBIC_INOUT {
      @Override
      public double compute(double basis) {
        // Derivative of position (use wolfram alpha)

        if (basis < 0.5) {
          basis = basis * 2; // scale basis for full 0-1
          return Math.min(
              1, // clip speed at normalized 1
              Math.log(32) * Math.pow(2, 1 - 10 * basis)
          );

        } else {
          double t = (basis - 0.5) * 2;
          return Math.min(
              1,
              3 * ((t *= 2) <= 1  ?  t*t  :  (t -= 2) * t ) / 2
          );
        }
      }

      @Override
      public boolean isNegative(double basis) {
        return basis > 0.5;
      }

      public String toString() {
        return "EXP+CUBIC";
      }
    };

    @Override
    public double invert(double value, double basisHint) {
      // TODO: Don't call setValue() on any of these modulators and we'll be fine
      throw new UnsupportedOperationException("TODO");
    }
  }


  /** Convenience class to predefine BreathEasingSuppliers */
  private static class DefaultBreathEasingSupplier implements BreathEasingSupplier {
    private final LXWaveshape position;
    private final SpeedWaveshape speed;

    DefaultBreathEasingSupplier(LXWaveshape position, SpeedWaveshape speed) {
      this.position = position;
      this.speed = speed;
    }

    @Override
    public LXWaveshape getPosition() {
      return position;
    }

    @Override
    public SpeedWaveshape getSpeed() {
      return speed;
    }
  }


  /**
   * see {@link ModulatorSyncer}
   */
  private static class SyncedVariableLFO extends VariableLFO {
    private final ModulatorSyncer syncer;

    public SyncedVariableLFO(ModulatorSyncer syncer, String label, LXWaveshape[] waveshapes) {
      super(label, waveshapes, syncer.clonePeriodParam());
      this.syncer = syncer;
      syncer.syncModulator(this);
    }

    @Override
    protected void onReset() {
      super.onReset();
      syncer.onReset(this);
    }
  }
  /**
   * This pattern is driven primarily by two modulators: position and speed.  However, we assume they're in sync
   * with each (ie speed is often the derivative of position).  Especially since we provide a UI for them (and people
   * can start/stop/etc them individually), we need to make sure an action to one happens on all.
   * This is a quick hack to make the two run together.
   */
  private static class ModulatorSyncer {
    /** the periodMs param that lives outside all the modulators */
    private final CompoundParameter commonPeriodMs;

    private boolean resetLock = false;
    private boolean changePeriodLock = false;
    private boolean loopLock = false;
    private boolean changeRunningLock = false;

    private List<SyncedVariableLFO> modulators = new LinkedList<>();

    public ModulatorSyncer(CompoundParameter commonPeriodMs) {
      this.commonPeriodMs = commonPeriodMs;

      this.commonPeriodMs.addListener(parameter -> this.onChangePeriodMs(null, parameter.getValue()));
    }

    /**
     * Clones the commonPeriodMs param.  Useful when attaching to one of the sync'd modulators, since a parameter
     * can belong to only one UI element at a time.  This Syncer will make sure all the instances are synced
     */
    public CompoundParameter clonePeriodParam() {
      return new CompoundParameter(
          commonPeriodMs.getLabel(),
          commonPeriodMs.getValue(),
          commonPeriodMs.range.v0,
          commonPeriodMs.range.v1
      );
    }

    public void onReset(VariableLFO reseter) {
      if (resetLock) return; // avoid infinite recursion

      resetLock = true;

      for (VariableLFO modulator : modulators) {
        if (modulator == reseter) {
          continue;
        }
        modulator.reset();
      }

      resetLock = false;
    }

    public void onChangeRunning(VariableLFO changer, boolean newValue) {
      if (changeRunningLock) return; // avoid infinite recursion

      changeRunningLock = true;

      for (VariableLFO modulator : modulators) {
        if (modulator == changer) {
          continue;
        }
        modulator.running.setValue(newValue);
      }

      changeRunningLock = false;
    }

    public void onChangePeriodMs(VariableLFO changer, double newValue) {
      if (changePeriodLock) return; // avoid infinite recursion

      changePeriodLock = true;

      for (VariableLFO modulator : modulators) {
        if (modulator == changer) {
          continue;
        }
        modulator.period.setValue(newValue);
      }

      if (changer != null) {
        commonPeriodMs.setValue(newValue);
      }

      changePeriodLock = false;
    }

    public void syncModulator(SyncedVariableLFO modulator) {
      this.modulators.add(modulator);

      modulator.period.setValue(commonPeriodMs.getValue());
      modulator.period.addListener(parameter -> this.onChangePeriodMs(modulator, parameter.getValue()));
      modulator.running.addListener(parameter -> this.onChangeRunning(modulator, ((BooleanParameter)parameter).getValueb()));
    }
  }
}
