package com.github.starcats.blinkydome;

import com.github.starcats.blinkydome.model.configuration.AbstractStarcatsLxModelConfig;
import com.github.starcats.blinkydome.util.ConfigSupplier;
import heronarts.lx.color.LXPalette;
import heronarts.lx.model.LXModel;
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

  public void settings() {
    size(1000, 800, P3D); // P3D to force GPU blending
  }

  public void setup() {

    AppGui p = this; // PApplet reference

    final AbstractStarcatsLxModelConfig scConfig = ConfigSupplier.getConfig(p);


    lxStudio = new LXStudio(this, scConfig.getModel(), false) {

      @Override
      public void initialize(LXStudio lx, LXStudio.UI ui) {
        scConfig.init(lx);


        // Config default palette to do interesting things
        lx.palette.hueMode.setValue(LXPalette.Mode.OSCILLATE);
        lx.palette.period.setValue(10000); // 10sec
        lx.palette.range.setValue(130); // to about green

      }

      @Override
      public void onUIReady(LXStudio lx, LXStudio.UI ui) {
        LXModel model = scConfig.getModel();
        // Configure camera view
        ui.preview
            .setRadius(model.xMax - model.xMin + 50)
            .setCenter(model.cx, model.cy, model.cz)
            .addComponent(new UIPointCloud(lx, model).setPointSize(5));


        // Turn off clip editor by default
        ui.toggleClipView();


        // Model-specific configs
        scConfig.onUIReady(lx, ui);
      }
    };

    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

      public void run () {

        System.out.println("Shutting down: turning all off...");

        lxStudio.engine.output.mode.setValue(0d);
        lxStudio.engine.output.send(null);
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
