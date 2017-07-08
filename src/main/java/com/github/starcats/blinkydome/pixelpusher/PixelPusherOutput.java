package com.github.starcats.blinkydome.pixelpusher;

import com.heroicrobot.dropbit.devices.pixelpusher.Strip;
import com.heroicrobot.dropbit.registry.DeviceRegistry;
import heronarts.lx.LX;
import heronarts.lx.output.LXOutput;

import java.util.List;

/**
 * PixelPusher Output, loosely borrowed from https://github.com/ascensionproject/ascension
 *
 * See https://docs.google.com/document/d/1D3tlMd0-H1p7Nmi4XtdEq_6MiXMoZ2Fhey-4_rigBz4 for details on using PixelPushers.
 */
public class PixelPusherOutput extends LXOutput {

  private final PixelPushableModel model;
  private final DeviceRegistry ppRegistry;

  public PixelPusherOutput(LX lx, PixelPushableModel model, DeviceRegistry ppRegistry) {
    super(lx);
    // enabled.setValue(false);
    this.model = model;
    this.ppRegistry = ppRegistry;
    ppRegistry.startPushing();
  }

  public void onSend(int[] colors) {
    for (PixelPushableLed led : model.getPpLeds()) {
      if (led.getPpGroup() == -1) continue;
      if (led.getPpStripIndex() == -1) continue;
      if (led.getPpLedIndex() == -1) continue;

      List<Strip> ppStrips = ppRegistry.getStrips(led.getPpGroup());
      if (ppStrips.size() < led.getPpStripIndex()) continue;

      Strip strip = ppStrips.get(led.getPpStripIndex() - 1);
      strip.setPixel(
          colors[ led.getPoint().index ],
          led.getPpLedIndex()
      );
    }
  }

}
