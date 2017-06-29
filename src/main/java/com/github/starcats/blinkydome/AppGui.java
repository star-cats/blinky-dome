package com.github.starcats.blinkydome;

import com.github.starcats.blinkydome.model.StarcatsLxModel;
import com.github.starcats.blinkydome.pattern.effects.WhiteWipePattern;
import com.github.starcats.blinkydome.util.AudioDetector;
import com.github.starcats.blinkydome.util.ModelSupplier;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LXChannel;
import heronarts.lx.LXPattern;
import heronarts.lx.color.LXPalette;
import heronarts.lx.output.LXOutput;
import heronarts.p3lx.LXStudio;
import heronarts.p3lx.ui.component.UIPointCloud;
import processing.core.PApplet;

import java.util.LinkedList;
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


        // Channel 2: All of the normal patterns + effects for blending, like white wipes and sparkles
        LXChannel channel2 = lx.engine.addChannel();
        channel2.fader.setValue(1); // Turn it on
        LXPattern ch2Default = new WhiteWipePattern(lx);
        patterns = new LinkedList<>();
        patterns.add(ch2Default);
        patterns.addAll(scModel.configPatterns(lx, p, starCatFFT));
        channel2.setPatterns(patterns.toArray(new LXPattern[patterns.size()]));


        // Config default palette to do interesting things
        lx.palette.hueMode.setValue(LXPalette.Mode.OSCILLATE);
        lx.palette.period.setValue(10000); // 10sec
        lx.palette.range.setValue(130); // to about green
      }

      @Override
      public void onUIReady(LXStudio lx, LXStudio.UI ui) {
        // Configure camera view
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
