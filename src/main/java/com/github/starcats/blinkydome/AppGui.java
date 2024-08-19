package com.github.starcats.blinkydome;

import com.github.starcats.blinkydome.configuration.StarcatsLxConfig;
import com.github.starcats.blinkydome.configuration.StarcatsLxGuiConfig;
import heronarts.lx.color.LXPalette;
import heronarts.lx.model.LXModel;
import heronarts.lx.output.LXOutput;
import heronarts.p3lx.LXStudio;
import processing.core.PApplet;

/**
 * Main GUI-iful processing app that runs the P3LX UI
 */
public class AppGui extends PApplet {

    /**
     * If true, run in fullscreen mode
     */
    private static boolean isFullscreen = false;

    /**
     * Width of window in pixels.
     */
    private static int windowWidth = 1200;

    /**
     * Height of window in pixels.
     */
    private static int windowHeight = 800;

    static public void main(String args[]) {
        // Parse command line args, supported flags:
        //  -f, --fullscreen: Fullscreen mode
        //  -w, --width: Width of window
        //  -h, --height: Height of window
        for (int i = 0; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-f") || args[i].equalsIgnoreCase("--fullscreen")) {
                isFullscreen = true;
            } else if (args[i].equalsIgnoreCase("-w") || args[i].equalsIgnoreCase("--width")) {
                windowWidth = Integer.parseInt(args[i++ + 1]);
            } else if (args[i].equalsIgnoreCase("-h") || args[i].equalsIgnoreCase("--height")) {
                windowHeight = Integer.parseInt(args[i++ + 1]);
            }
        }

        PApplet.main(new String[]{"com.github.starcats.blinkydome.AppGui"});
    }

    private LXStudio lxStudio;

    public void settings() {
        if (isFullscreen) {
            fullScreen(P3D);
        } else {
            size(windowWidth, windowHeight, P3D); // P3D to force GPU blending
        }
    }

    public void setup() {

        AppGui p = this; // PApplet reference

        StarcatsLxConfig<?> suppliedScConfig = ConfigSupplier.getConfig(p);

        if (!(suppliedScConfig instanceof StarcatsLxGuiConfig<?> scConfig)) {
            throw new RuntimeException(
                    "Configuration supplied not a StarcatsLxGuiConfig: " + suppliedScConfig.getClass().getName()
            );
        }

        lxStudio = new LXStudio(this, scConfig.getModel(), false) {

            @Override
            public void initialize(LXStudio lx, LXStudio.UI ui) {
                scConfig.init(lx);
                scConfig.initUI(lx, ui);


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
                        .setPhi(PI / 6)
                        .setTheta(PI);

                // Turn off clip editor by default
                ui.toggleClipView();

                // Model-specific configs
                scConfig.onUIReady(lx, ui);
            }
        };

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

            public void run() {

                System.out.println("Shutting down: turning all off...");

                lxStudio.engine.output.mode.setValue(LXOutput.Mode.OFF);
                lxStudio.engine.output.send(null);

                // wait a few ms for all commands to flush
                long waitUntil = System.currentTimeMillis() + 100;
                while (System.currentTimeMillis() < waitUntil) {
                    Thread.yield();
                }
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
