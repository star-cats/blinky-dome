package com.github.starcats.blinkydome.starpusher;

import com.github.starcats.blinkydome.pixelpusher.PixelPushableLED;
import com.github.starcats.blinkydome.pixelpusher.PixelPushableModel;
import heronarts.lx.LX;
import heronarts.lx.output.LXOutput;

public class StarPusherOutput extends LXOutput {
    private final PixelPushableModel model;

    private final StarPusherDeviceRegistry registry;

    private Integer updateCount = 0;
    private static final int FPS_CALCULATION_INTERVAL_MILLIS = 10000;

    public StarPusherOutput(LX lx, PixelPushableModel model, StarPusherDeviceRegistry registry) {
        super(lx);
        this.model = model;
        this.registry = registry;

        new Thread(() -> {
            while(true) {
                try {
                    Thread.sleep(FPS_CALCULATION_INTERVAL_MILLIS);
                    float fps = updateCount / (FPS_CALCULATION_INTERVAL_MILLIS / 1000);
                    System.out.println("StarPusher FPS: " + fps);
                    synchronized (updateCount) {
                        updateCount = 0;
                    }
                } catch(InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    @Override
    protected void onSend(int[] colors) {
        synchronized (updateCount) {
            updateCount++;
        }
        for (PixelPushableLED led : model.getPpLeds()) {
            if (led.getPpGroup() == -1) continue;
            if (led.getPpPortIndex() == -1) continue;
            if (led.getPpLedIndex() == -1) continue;

            int color = colors[led.getPoint().index];
            int r = (color >> 16) & 0xff;
            int g = (color >> 8) & 0xff;
            int b = color & 0xff;

            registry.setPixel(
                    led.getPpGroup(),
                    // Pixel pusher ports are zero-based, so subtract one.
                    led.getPpPortIndex() - 1,
                    led.getPpLedIndex(),
                    r,
                    g,
                    b,
                    0xff);
        }
        // Flush all pixel buffers to StarPushers.
        registry.sendPixelsToDevices();
    }
}
