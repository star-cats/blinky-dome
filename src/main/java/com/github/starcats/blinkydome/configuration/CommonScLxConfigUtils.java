package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.color.ColorMappingSource;
import com.github.starcats.blinkydome.color.ImageColorSampler;
import com.github.starcats.blinkydome.color.ImageColorSamplerClan;
import com.github.starcats.blinkydome.pattern.FixtureColorBarsPattern;
import com.github.starcats.blinkydome.util.AudioDetector;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LX;
import heronarts.lx.audio.BandGate;
import heronarts.lx.model.LXFixture;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.LXTriggerSource;
import heronarts.lx.modulator.VariableLFO;
import heronarts.lx.parameter.LXCompoundModulation;
import heronarts.lx.parameter.LXNormalizedParameter;
import heronarts.lx.parameter.LXTriggerModulation;
import processing.core.PApplet;

import java.util.Collection;

/**
 * Config factories that provide common StarCat configurations
 */
public class CommonScLxConfigUtils {


  public static class Components {
    /**
     * @return minim-based FFT.  Offers better beat-detection algorithms than LX built-in stuff
     */
    public static StarCatFFT makeStarcatFft(LX lx) {
      StarCatFFT starCatFFT = new StarCatFFT(lx);
      AudioDetector.init(starCatFFT.in.mix);
      return starCatFFT;
    }

    /**
     * @return A {@link com.github.starcats.blinkydome.color.ColorMappingSource} compromising of gradient and pattern
     *   color samplers
     */
    public static ImageColorSamplerClan makeColorSampler(PApplet p) {
      // Color Samplers
      ImageColorSampler gradientColorSource = new ImageColorSampler(p, "gradients.png");
      ImageColorSampler patternColorSource = new ImageColorSampler(p, "patterns.png");
      return new ImageColorSamplerClan(new ImageColorSampler[] {
          gradientColorSource,
          patternColorSource
      });
    }
  }

  public static class Modulators {

    /**
     * A beat-detecting {@link LXModulator} for kick drums.
     *
     * Note: This is also a {@link LXTriggerSource}, ie can also call {@link BandGate#getTriggerSource()} for the
     * instantaneous beat-trigger
     */
    public static BandGate makeKickModulator(LX lx) {
      BandGate kickModulator = new BandGate("Kick beat detect", lx);
      kickModulator.running.setValue(true);
      kickModulator.gain.setValue(30); //dB
      return kickModulator;
    }

    /**
     * A simple time-varying LFO that configurations often hook up into the imageColorSampler
     * {@link com.github.starcats.blinkydome.color.ColorMappingSource} to get out time-varying colors.
     */
    public static VariableLFO makeColorMappingLFO() {
      VariableLFO colorMappingSourceLfo = new VariableLFO("Color Mapping LFO");
      colorMappingSourceLfo.running.setValue(true);
      colorMappingSourceLfo.period.setValue(3000);
      return colorMappingSourceLfo;
    }
  }

  public static class Patterns {
    /**
     * Wires up a {@link FixtureColorBarsPattern} with common modulations for if the config wants to use it.
     *
     * @param lx LX
     * @param fixtures List of fixtures the FixtureColorBarsPattern should draw on
     * @param colorSource eg {@link Components#makeColorSampler}
     * @param colorSourceModulation eg {@link Modulators#makeColorMappingLFO}
     * @param newBarTrigger eg {@link Modulators#makeKickModulator}
     *
     * @return A FixtureColorBarsPattern instance with common modulations
     */
    public static FixtureColorBarsPattern wireUpFixtureColorBarsPattern(
        LX lx,
        Collection<? extends LXFixture> fixtures,
        ColorMappingSource colorSource,
        LXNormalizedParameter colorSourceModulation,
        LXTriggerSource newBarTrigger
    )
    {
      FixtureColorBarsPattern fixtureColorBarsPattern = new FixtureColorBarsPattern(
          lx, fixtures, colorSource
      );

      LXCompoundModulation fcbpAudioModulation = new LXCompoundModulation(
          lx.engine.audio.meter, fixtureColorBarsPattern.visibleRange
      );
      fixtureColorBarsPattern.visibleRange.setValue(0.25);
      fcbpAudioModulation.range.setValue(0.75);
      lx.engine.modulation.addModulation(fcbpAudioModulation);

      LXCompoundModulation fcbpColorModulation = new LXCompoundModulation(
          colorSourceModulation, fixtureColorBarsPattern.colorSourceI
      );
      fixtureColorBarsPattern.colorSourceI.setValue(0.0);
      fcbpColorModulation.range.setValue(1.0);
      lx.engine.modulation.addModulation(fcbpColorModulation);

      LXTriggerModulation fcbpNewBarModulation = new LXTriggerModulation(
          newBarTrigger.getTriggerSource(), fixtureColorBarsPattern.getTriggerTarget()
      );
      lx.engine.modulation.addTrigger(fcbpNewBarModulation);

      return fixtureColorBarsPattern;
    }
  }

}
