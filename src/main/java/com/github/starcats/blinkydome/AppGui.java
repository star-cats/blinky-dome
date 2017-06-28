package com.github.starcats.blinkydome;

import com.github.starcats.blinkydome.model.StarcatsLxModel;
import com.github.starcats.blinkydome.pattern.effects.WhiteWipePattern;
import com.github.starcats.blinkydome.util.AudioDetector;
import com.github.starcats.blinkydome.util.ModelSupplier;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
import heronarts.lx.output.LXOutput;
import heronarts.p3lx.LXStudio;
import heronarts.p3lx.ui.component.UIPointCloud;
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

  private LXStudio lxStudio;
  private LXOutput fcOutput;
  private final StarCatFFT starCatFFT = new StarCatFFT();

  private float lastDrawMs = 0;

  public void settings() {
    size(1000, 800, P3D); // P3D to force GPU blending
  }

  public void setup() {

    AppGui p = this; // PApplet reference
    StarcatsLxModel scModel = ModelSupplier.getModel(p, true, () -> p.fcOutput);

    AudioDetector.init(starCatFFT.in.mix);


    lxStudio = new LXStudio(this, scModel, false) {
      @Override
      public void initialize(LXStudio lx, LXStudio.UI ui) {
        scModel.initLx(lx);

        LXChannel mainCh = lx.engine.getChannels().get(0);

        List<LXPattern> patterns = scModel.configPatterns(lx, p, starCatFFT);
        mainCh.setPatterns(patterns.toArray(new LXPattern[patterns.size()]));
        mainCh.goPattern(patterns.get(0));


        LXPattern ch2Default = new WhiteWipePattern(lx);
        patterns = scModel.configPatterns(lx, p, starCatFFT);
        patterns.add(ch2Default);
        LXChannel channel2 = lx.engine.addChannel(patterns.toArray(new LXPattern[patterns.size()]));
        channel2.goPattern(ch2Default);
        channel2.label.setDescription("Blend-into patterns");
        channel2.fader.setValue(1);
      }

      @Override
      public void onUIReady(LXStudio lx, LXStudio.UI ui) {
        ui.preview
            .setRadius(scModel.xMax - scModel.xMin + 50)
            .setCenter(scModel.cx, scModel.cy, scModel.cz)
            .addComponent(new UIPointCloud(lx, scModel).setPointSize(5));


        // Turn off clip editor by default
        ui.toggleClipView();

        // Enable audio support
        lx.engine.audio.enabled.setValue(true);
      }
    };


//    fcOutput = new FadecandyOutput(lx, "localhost", 7890);
//    lx.addOutput(fcOutput);

    //lx.ui.addLayer(new UIChannelControl(lx.ui, lx, 16, 4, 4));

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
