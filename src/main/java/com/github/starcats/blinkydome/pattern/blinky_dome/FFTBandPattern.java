package com.github.starcats.blinkydome.pattern.blinky_dome;

import com.github.starcats.blinkydome.model.blinky_dome.BlinkyDome;
import com.github.starcats.blinkydome.model.blinky_dome.BlinkyLED;
import com.github.starcats.blinkydome.pattern.AbstractSimplePattern;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LX;
import heronarts.lx.color.LXColor;
import heronarts.lx.modulator.Accelerator;

/**
 * Created by akesich on 6/27/17.
 */
public class FFTBandPattern extends AbstractSimplePattern {
    private final BlinkyDome model;
    private final StarCatFFT fft;

    private Accelerator ringPhi;

    public FFTBandPattern(LX lx, BlinkyDome model, StarCatFFT fft) {
        super(lx);

        this.model = model;

        this.fft = fft;

        this.ringPhi = new Accelerator(0, 0.3, 0);
        addModulator(this.ringPhi).start();
    }

    public void run(double deltaMs) {

        int c = LXColor.hsb(60, 100, 100);

        float phiPos = this.getRingPhi();

        for (BlinkyLED led : this.model.leds) {
            float dist = angluarDistance(led.phiRad, phiPos);

            if (dist < 0.3) {
                int d = LXColor.lerp(c, 0, dist / 0.3);
                setLEDColor(led, d);
            } else {
                setLEDColor(led, 0);
            }
        }
    }

    private float getRingPhi() {
        float phi = this.ringPhi.getValuef();

        if (phi > Math.PI) {
            phi -= 2 * Math.PI;
            this.ringPhi.setValue(phi);
        }

        return phi;
    }

    private static float angluarDistance(float theta, float phi) {
        float dist = Math.abs(theta - phi);

        if (dist > Math.PI) {
            return 2 * (float)Math.PI - dist;
        }

        return dist;
    }
}
