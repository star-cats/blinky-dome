package com.github.starcats.blinkydome;

import com.github.starcats.blinkydome.model.BlinkyDome;
import com.github.starcats.blinkydome.model.StarcatsLxModel;
import com.github.starcats.blinkydome.model.configuration.BlinkyDomeStudioConfig;
import com.github.starcats.blinkydome.model.configuration.StarcatsLxModelConfig;
import com.github.starcats.blinkydome.util.ModelSupplier;
import heronarts.lx.color.LXPalette;
import heronarts.lx.output.LXOutput;
import heronarts.p3lx.LXStudio;
import heronarts.p3lx.ui.component.UIPointCloud;
import processing.core.PApplet;

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

  public void settings() {
    size(1000, 800, P3D); // P3D to force GPU blending
  }

  public void setup() {

    AppGui p = this; // PApplet reference
    StarcatsLxModel scModel = ModelSupplier.getModel(p, true, () -> p.fcOutput);


    lxStudio = new LXStudio(this, scModel, false) {
      private StarcatsLxModelConfig scConfig;

      @Override
      public void initialize(LXStudio lx, LXStudio.UI ui) {
        this.scConfig = new BlinkyDomeStudioConfig(p, lx, (BlinkyDome) scModel);
        scConfig.init();


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


        // Model-specific configs
        scConfig.onUIReady(lx, ui);
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
  }

}
