package com.github.starcats.blinkydome;

import com.github.starcats.blinkydome.model.StarcatsLxModel;
import com.github.starcats.blinkydome.util.AudioDetector;
import com.github.starcats.blinkydome.util.ModelSupplier;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LXPattern;
import heronarts.lx.output.FadecandyOutput;
import heronarts.lx.output.LXOutput;
import heronarts.p3lx.P3LX;
import heronarts.p3lx.ui.UI3dContext;
import heronarts.p3lx.ui.component.UIPointCloud;
import heronarts.p3lx.ui.control.UIChannelControl;
import processing.core.PApplet;

import java.util.List;

/**
 * Main GUI-iful processing app that runs the P3LX UI
 */
public class AppGui extends PApplet {

  private static boolean isVerbose = false;

  static public void main(String args[]) {
    PApplet.main(new String[] { "com.github.starcats.blinkydome.AppGui" });

    for (String s: args) {
      if (s.equalsIgnoreCase("-v")) {
        isVerbose = true;
      }
    }
  }

  private P3LX lx;
  private LXOutput fcOutput;
  private final StarCatFFT starCatFFT = new StarCatFFT();

  private float lastDrawMs = 0;

  public void settings() {
    size(600, 600, P3D); // P3D to force GPU blending
  }

  public void setup() {

    AppGui me = this;
    StarcatsLxModel model = ModelSupplier.getModel(me, true, () -> me.fcOutput);

    AudioDetector.init(starCatFFT.in.mix);

    lx = new P3LX(this, model);

    model.initLx(lx);

    List<LXPattern> patterns = model.configPatterns(lx, this, starCatFFT);
    lx.setPatterns(patterns.toArray(new LXPattern[patterns.size()]));
    lx.goPattern(patterns.get(0));

    fcOutput = new FadecandyOutput(lx, "localhost", 7890);
    lx.addOutput(fcOutput);

    lx.ui.addLayer(
        new UI3dContext(lx.ui)
            .setCenter(model.cx, model.cy, model.cz)
            .setRadius(model.xMax - model.xMin)
            .addComponent(new UIPointCloud(lx, model).setPointSize(5))
    );

    lx.ui.addLayer(new UIChannelControl(lx.ui, lx, 16, 4, 4));

    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

      public void run () {

        System.out.println("Shutting down: turning all off...");

        fcOutput.mode.setValue(0d);
        fcOutput.send(null);
        for (int i=0; i<100000; i++)
          Thread.yield();
      }
    }
    ));
  }

  public void draw() {
    // Wipe the frame...
    background(0x292929);
    // ...and everything else is handled by P3LX!

    starCatFFT.forward();
    AudioDetector.LINE_IN.tick(this.millis() - lastDrawMs, isVerbose);

    lastDrawMs = this.millis();
  }

}
