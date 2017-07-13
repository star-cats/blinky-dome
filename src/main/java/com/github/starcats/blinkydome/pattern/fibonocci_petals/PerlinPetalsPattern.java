package com.github.starcats.blinkydome.pattern.fibonocci_petals;

import com.github.starcats.blinkydome.color.ColorMappingSourceGroup;
import com.github.starcats.blinkydome.model.FibonocciPetalsModel;
import com.github.starcats.blinkydome.pattern.perlin.ColorMappingSourceColorizer;
import com.github.starcats.blinkydome.pattern.perlin.LXPerlinNoiseExplorer;
import com.github.starcats.blinkydome.pattern.perlin.PerlinNoiseColorizer;
import com.github.starcats.blinkydome.pattern.perlin.RotatingHueColorizer;
import com.github.starcats.blinkydome.util.StarCatFFT;
import ddf.minim.analysis.BeatDetect;
import heronarts.lx.LX;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.QuadraticEnvelope;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameter;
import processing.core.PApplet;
import processing.core.PVector;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pattern like PerlinNoisePattern, but just for the FibonocciPetalsModel.  Uses petals to flash noise field
 */
public class PerlinPetalsPattern extends LXPattern {
  private static int PULSE_DECAY_MS_DEFAULT = 500;
  private static int MIN_TIME_BETWEEN_BEATS_MS = 150;

  public final DiscreteParameter beatLimitMs = new DiscreteParameter("bt lim", MIN_TIME_BETWEEN_BEATS_MS, -1, 500);


  private double timeSinceLastBeat = 0;

  private BeatDetect beat;

  private LXPerlinNoiseExplorer hueNoise;
  private Map<String, PerlinNoiseColorizer> allColorizers;

  public DiscreteParameter colorizerSelect;
  public BooleanParameter rotateColorizer = new BooleanParameter("col rotate", true);

  public final LXParameter hueSpeed;
  public final LXParameter hueXForm;

  /** Brightness of pixels that don't have a flash */
  public final CompoundParameter fadedBrightness = new CompoundParameter("min brt", 0.4, 0., 1.);

  /** Brightness of pixels that are being flashed */
  public final CompoundParameter flashBrightness = new CompoundParameter("max brt", 1., 0., 1.);


  public final DiscreteParameter pulseDecayMs = new DiscreteParameter("puls decay", PULSE_DECAY_MS_DEFAULT, 100, 1500);
  public final BooleanParameter useWhiteFlash = new BooleanParameter("puls white", true);

  private List<PetalPulse> pulses = new LinkedList<>();
  private Set<FibonocciPetalsModel.PetalSpiral> activePulsingSpirals = new HashSet<>();

  private int[] decayedColorField;
  private int[] origColorField;

  private float[] origValueField;

  private class PetalPulse {
    final FibonocciPetalsModel.PetalSpiral spiral;
    final QuadraticEnvelope decay;

    private PetalPulse(FibonocciPetalsModel.PetalSpiral spiral) {
      this.spiral = spiral;
      this.decay = new QuadraticEnvelope(1.0, 0, pulseDecayMs.getValuei())
          .setEase(QuadraticEnvelope.Ease.OUT);

      if (activePulsingSpirals.contains(spiral)) {
        this.decay.stop(); // kill me on next loop -- ignore this pulse so we don't duplicate spirals
      } else {
        addModulator(this.decay).start();
        activePulsingSpirals.add(spiral);
      }
    }

    private void destroy() {
      removeModulator(this.decay);
      activePulsingSpirals.remove(spiral);
    }

    /**
     * Calculates the value of a gaussian curve at spiral radius r, given the center gaussian is moving outwards to
     * spiral.maxR per the decay modulator.
     *
     * @param r Position in the (implicitly moving) gaussian
     * @return 0-1
     */
    public double gaussianAtR(float r) {
      // b is the value at which the guassian is centered at.  Make it move out the spiral with the pulse.
      float b = (1f - this.decay.getValuef()) * this.spiral.maxR;

      // width of the gaussian -- ie b-c and b+c are the inflection points of the gaussian
      float c = (this.spiral.maxR / 10f);

      return Math.exp( -Math.pow(r-b, 2) / (2f * c * c));
    }

    /**
     * Decay modulates the center of a gaussian that travels through the petals
     * @param i Which petal
     * @return 0-1 value of gaussian for the petal
     */
    public double gaussianForPetal(float i) {
      int numPetals = this.spiral.getPetals().size();

      // b is the value at which the guassian is centered at.  Make it move out the spiral with the pulse.
      float b = (1f - this.decay.getValuef()) * numPetals;

      // width of the gaussian -- ie b-c and b+c are the inflection points of the gaussian
      float c = 1;

      return Math.exp( -Math.pow(i-b, 2) / (2f * c * c));
    }
  }


  public PerlinPetalsPattern(LX lx, PApplet p, StarCatFFT fft, ColorMappingSourceGroup colorMappingSourceGroup) {
    super(lx);

    this.beat = fft.beat;

    // Make Hue Noise
    // -----------------
    List<PVector> leds = this.model.getPoints().stream()
        .map(pt -> new PVector(pt.x, pt.y, pt.z))
        .collect(Collectors.toList());

    this.hueNoise = new LXPerlinNoiseExplorer(p, this.model.getPoints(), "h ", "hue");
    addParameter(this.hueSpeed = hueNoise.noiseSpeed);
    addParameter(this.hueXForm = hueNoise.noiseZoom);


    // Colorizers
    // ------------------
    allColorizers = new HashMap<>();

    RotatingHueColorizer rotatingHueColorizer = new RotatingHueColorizer(hueNoise) {
      @Override
      public RotatingHueColorizer activate() {
        hueNoise.noiseSpeed.setValue(0.020);
        return this;
      }
    };
    allColorizers.put("rotatingHue", rotatingHueColorizer);
    addParameter(rotatingHueColorizer.huePeriodMs);

    // Colorizer: colorMappingSourceGroup
    ColorMappingSourceColorizer gradientColorizer = new ColorMappingSourceColorizer(hueNoise, colorMappingSourceGroup) {
      @Override
      public ColorMappingSourceColorizer activate() {
        hueNoise.noiseSpeed.setValue(0.020);
        return this;
      }
    };
    allColorizers.put("gradient", gradientColorizer);


    // Register all colorizers
    for (PerlinNoiseColorizer colorizer : allColorizers.values()) {
      for (LXModulator modulator : colorizer.getModulators()) {
        addModulator(modulator);
      }
    }
    colorizerSelect = new DiscreteParameter(
        "clrzr sel",
        allColorizers.keySet().toArray(new String[allColorizers.keySet().size()])
    );
    colorizerSelect.addListener(param -> allColorizers.get(colorizerSelect.getOption()).activate());
    // And do first activation:
    allColorizers.get(colorizerSelect.getOption()).activate();

    addParameter(colorizerSelect);
    addParameter(rotateColorizer);


    // Misc
    // ---------
    origColorField = new int[colors.length];
    decayedColorField = new int[colors.length];
    origValueField = new float[colors.length];

    addParameter(fadedBrightness);
    addParameter(flashBrightness);

    addParameter(beatLimitMs);

    addParameter(pulseDecayMs);
    addParameter(useWhiteFlash);

  }

  public void run(double deltaMs) {
    hueNoise.step(deltaMs);

    FibonocciPetalsModel model = (FibonocciPetalsModel) this.model;

    if (beat.isKick() && (beatLimitMs.getValuei() <= 0 || timeSinceLastBeat > MIN_TIME_BETWEEN_BEATS_MS)) {

      // Try to find an unused spiral
      FibonocciPetalsModel.PetalSpiral spiral;
      int ttl = 4;
      do {
        ttl--;
        spiral = model.cwSpirals.get((int)(Math.random() * model.cwSpirals.size()));
      } while (ttl > 0 && activePulsingSpirals.contains(spiral));

      if (activePulsingSpirals.contains(spiral)) {
        //System.out.println("Gave up on finding a spiral to pulse");
      } else {
        pulses.add(new PetalPulse(spiral));
      }
    }
    timeSinceLastBeat += deltaMs;


    // Rotate colorizer on strong beats
    if (rotateColorizer.getValueb() && beat.isSnare() && beat.isKick()) {
      hueNoise.randomizeDirection();
      randomColorizer().rotate();
    }

    PerlinNoiseColorizer colorizer = allColorizers.get(colorizerSelect.getOption());

    for (LXPoint p : model.points) {
      // Start with colorizer's color
      int color = colorizer.getColor(p);

      // Reduce base brightness if specified
      if (flashBrightness.getValue() < 1) {
        color = LXColor.hsb(
            LXColor.h(color),
            LXColor.s(color),
            LXColor.b(color) * flashBrightness.getValue()
        );
      }

      origColorField[p.index] = color;
      origValueField[p.index] = LXColor.b(color);

      // Everyone starts with their hue cut down to 1/3
      colors[p.index] = decayedColorField[p.index] = LX.hsb(
          LXColor.h(color),
          LXColor.s(color),
          origValueField[p.index] * fadedBrightness.getValuef()
      );
    }

    // Now go through the pulses and rescale intensity
    boolean needsClean = false;
    boolean first = true;
    for (PetalPulse pulse : this.pulses) {
      if (!pulse.decay.isRunning()) {
        pulse.destroy();
        needsClean = true;
        continue;
      }

      double decayVal = pulse.decay.getValue();
      double maxDecayR = decayVal * pulse.spiral.maxR;

//      if (!first)
//        continue;
//
//      first = false;
//      System.out.println("---------");
//      System.out.println("decay:" + pulse.decay.getValuef());

//      if (first) {
//        System.out.println("pulse " + pulse + " decayVal:" + decayVal);
//      }


      // a pulsing experiment...
//      pulseWithGaussianOutwards(decayVal, pulse);

      // try emphasizing the petals
      for (int i=0; i<pulse.spiral.getPetals().size(); i++) {
        double petalGaussian = pulse.gaussianForPetal(i);
//        System.out.println("petal:" + i + " gaussian:" + petalGaussian);

        for (LXPoint p : pulse.spiral.getPetals().get(i).getPoints()) {
          colors[p.index] = LXColor.lerp(
              decayedColorField[p.index],
              origColorField[p.index],
              petalGaussian
          );
        }
      }
    }

    if (needsClean) {
      pulses = pulses.stream().filter(pulse -> pulse.decay.isRunning()).collect(Collectors.toList());
    }
  }

  /**
   * Pulse style where when white flashes enabled, the white radiates outwards with a gaussian cross-fade
   * @param decayVal
   * @param pulse
   */
  private void pulseWithGaussianOutwards(double decayVal, PetalPulse pulse) {
    for (LXPoint p : pulse.spiral.getPoints()) {
      colors[p.index] = LXColor.lerp(decayedColorField[p.index], origColorField[p.index], decayVal);

      if (useWhiteFlash.getValueb()) {
        //System.out.println("maxR" + pulse.spiral.maxR + " point r:" + p.r + " gaussian:" + pulse.gaussianAtR(p.r));
        colors[p.index] = LXColor.hsb(
            LXColor.h(colors[p.index]),

            // Saturation:
            // - make entire petal flash:
            // 100 * (1.0 - decayVal),

            // - White-flash movee outwards with gaussian
            100 * pulse.gaussianAtR(p.r),

            LXColor.b(colors[p.index])
        );
      }
    }
  }

  private PerlinNoiseColorizer randomColorizer() {
    double rand = Math.random();

    // Weighted random

    // hueRotate
    if (rand < 0.2) {
      colorizerSelect.setValue(0);

      // gradient:
    } else if (rand < 0.6) {
      colorizerSelect.setValue(1);

      // pattern
    } else {
      colorizerSelect.setValue(2);
    }

    return allColorizers.get(colorizerSelect.getOption());
  }
}
