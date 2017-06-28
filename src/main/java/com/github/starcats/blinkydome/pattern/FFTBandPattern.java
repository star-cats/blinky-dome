package com.github.starcats.blinkydome.pattern;

import com.github.starcats.blinkydome.model.LED;
import com.github.starcats.blinkydome.util.StarCatFFT;
import heronarts.lx.LX;
import heronarts.lx.color.LXColor;
import heronarts.lx.modulator.Accelerator;

/**
 * Created by akesich on 6/27/17.
 */
public class FFTBandPattern extends StarcatsLxPattern {
    private StarCatFFT fft;

    private Accelerator ringPhi;

    public FFTBandPattern(LX lx, StarCatFFT fft) {
        super(lx);

        this.fft = fft;

        this.ringPhi = new Accelerator(0, 0.3, 0);
        addModulator(this.ringPhi).start();
    }

    public void run(double deltaMs) {

        int c = LXColor.hsb(60, 100, 100);

        float phiPos = this.getRingPhi();

        for (LED led : this.leds) {
            float dist = angluarDistance(led.phi, phiPos);
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
