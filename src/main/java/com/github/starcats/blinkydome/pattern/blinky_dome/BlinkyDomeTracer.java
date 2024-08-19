package com.github.starcats.blinkydome.pattern.blinky_dome;

import com.github.starcats.blinkydome.model.blinky_dome.BlinkyLED;
import com.github.starcats.blinkydome.model.blinky_dome.BlinkyModel;
import com.github.starcats.blinkydome.model.blinky_dome.BlinkyTriangle;
import com.github.starcats.blinkydome.pattern.AbstractSimplePattern;
import com.github.starcats.blinkydome.starpusher.StarPushableLED;
import heronarts.lx.LX;

import java.util.List;

public class BlinkyDomeTracer extends AbstractSimplePattern {
    double step = 0;

    public BlinkyDomeTracer(LX lx) {
        super(lx);
    }
    @Override
    protected void run(double deltaMs) {
        BlinkyModel model = (BlinkyModel)this.model;

        step += deltaMs;

        long index = Math.round(step / 200.0) % (10*33);

        for (BlinkyTriangle triangle : model.allTriangles) {
            List<BlinkyLED> pixels = triangle.getPixelChain();
            for (int i = 0; i < pixels.size(); i++) {
                BlinkyLED led = pixels.get(i);
                int side = i / 11;
                if (side == 0) {
                    setColor(led.index, LX.rgb(255, 0, 0));

                } else if (side == 1) {
                    setColor(led.index, LX.rgb(0, 255, 0));


                } else {
                    setColor(led.index, LX.rgb(0, 0, 255));

                }
            }
        }

        /*for (StarPushableLED led : model.getSpLeds()) {
            BlinkyLED blinky = (BlinkyLED) led;
            if (blinky.getSpLedIndex() == index) {
                setColor(blinky.index, LX.rgb(255, 0, 0));
            } else {
                setColor(blinky.index, LX.rgb(0, 0, 0));
            }
        }*/
    }
}
