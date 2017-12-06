package com.github.starcats.blinkydome.pattern;

import com.github.starcats.blinkydome.model.dlo.DloRoadBikeModel;
import heronarts.lx.LX;
import heronarts.lx.LXLayer;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.modulator.SawLFO;
import heronarts.lx.modulator.SinLFO;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;

public class HazardStripesPattern extends LXPattern {

  private final BooleanParameter muteStripes = new BooleanParameter("mute", false);
  private final CompoundParameter huePeriod = new CompoundParameter("hue period", 500, 20000);
  private final CompoundParameter hue = new CompoundParameter("h", 0, 360);
  private final CompoundParameter stripeWidth = new CompoundParameter("strp w", 94, 20, 200);
  private final CompoundParameter stripePeriodMs = new CompoundParameter("strp sp", 420, 1, 1000);

  public HazardStripesPattern(LX lx) {
    super(lx);

    StripesLayer stripesLayer = new StripesLayer(lx);
    stripesLayer.bindToGpio();

    addLayer(stripesLayer);

    addParameter(huePeriod);
    huePeriod.setValue(10000);

    addParameter(hue);

    addParameter(stripeWidth);
    addParameter(stripePeriodMs);
    addParameter(muteStripes);
  }

  public void run(double deltaMx) {
    // no-op -- layers run automatically
  }

  private class StripesLayer extends LXLayer {

    private final SinLFO color = new SinLFO(0, 36, huePeriod);
    private final SawLFO stripeOffset = new SawLFO(0, stripeWidth, stripePeriodMs);
    private StripesLayer(LX lx) {
      super(lx);
      addModulator(color).start();
      addModulator(stripeOffset).start();
    }

    protected void bindToGpio() {
//      if (!RaspiGpio.isActive()) {
//        return;
//      }
//
//      RaspiGpio.blackMoment.addListener(new GpioPinListenerDigital() {
//        @Override
//        public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
//          if (event.getState().isHigh()) {
//            muteStripes.setValue(!muteStripes.getValueb());
//          }
//        }
//      });
    }

    public void run(double deltaMx) {
      // Make stripes unless muted
      for (LXPoint p : model.points) {
        boolean on = (p.x + stripeOffset.getValuef()) % stripeWidth.getValuef() < (stripeWidth.getValuef() / 2);

        colors[p.index] = LXColor.hsb(
            color.getValue(),
            100,
            !muteStripes.getValueb() && on ? 100 : 0
        );
      }

      boolean flasher = stripeOffset.getValue() % (stripeWidth.getValuef() / 2) < stripeWidth.getValuef() / 4;

      for (LXPoint p : ((DloRoadBikeModel)model).headlightSpiral.getPoints()) {
        colors[p.index] = LXColor.hsb(
            0,
            0,
            flasher ? (muteStripes.getValueb() ? 100 : 80) : 0
        );
      }

      for (LXPoint p : ((DloRoadBikeModel)model).brakeBlob.getPoints()) {
        colors[p.index] = LXColor.hsb(
            color.getValuef(),
            100,
            flasher ? 100 : 0
        );
      }
      for (LXPoint p : ((DloRoadBikeModel)model).seatpostLoop.getPoints()) {
        colors[p.index] = LXColor.hsb(
            color.getValuef(),
            100,
            flasher ? 0 : 100
        );
      }
    }
  }
}
