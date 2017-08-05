package com.github.starcats.blinkydome.configuration;

import com.github.starcats.blinkydome.color.ImageColorSampler;
import com.github.starcats.blinkydome.color.ImageColorSamplerGroup;
import com.github.starcats.blinkydome.util.AudioDetector;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LX;
import heronarts.lx.audio.BandGate;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.LXTriggerSource;
import processing.core.PApplet;

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
    public static ImageColorSamplerGroup makeColorSampler(PApplet p, LX lx) {
      // Color Samplers
      ImageColorSampler gradientColorSource = new ImageColorSampler(p, lx, "gradients.png");
      ImageColorSampler patternColorSource = new ImageColorSampler(p, lx, "patterns.png");
      return new ImageColorSamplerGroup(lx, "color samplers", new ImageColorSampler[] {
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
      kickModulator.floor.setValue(0.9);
      return kickModulator;
    }
  }

}
