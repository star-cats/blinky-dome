package com.github.starcats.blinkydome.pattern;

import heronarts.lx.LX;
import com.github.starcats.blinkydome.model.LED;

/**
 * Created by akesich on 6/25/17.
 */
public class LayerTestPattern extends Pattern{
    public LayerTestPattern(LX lx) {
        super(lx);
    }

    public void run(double deltaMs) {
        int c;
        for (LED led : leds) {
            c = lx.hsb(led.layer * 50, 100, 80);
            setLEDColor(led, c);
        }
    }
}
