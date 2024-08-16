package com.github.starcats.blinkydome.starpusher;

import heronarts.lx.LX;
import heronarts.lx.output.LXOutput;
import javafx.util.Pair;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

public class StarPusherOutput extends LXOutput {

    /**
     * This is hardcoded to the number of LEDs per port for BM2024 Dome.
     *
     * Each port has 10 triangles and each triangle has 33 logical LEDs (99 physical LEDs since each WS2811 chip
     * drives 3 adjacent physical LEDs).
     */
    private static final int EXPECTED_LEDS_PER_PORT = 330;

    /** StarPusher V3 listens for UDP packets on port 6868. */
    private static final int STARPUSHER_NETWORK_PORT = 6868;

    private final StarPushableModel model;

    /** Maintain a map between the tuple (StarPusher address, StarPusher output port) and StarPusher port devices. */
    private final Map<Pair<String, Integer>, StarPusherPortDevice> devices = new HashMap<>();

    public StarPusherOutput(LX lx, StarPushableModel model) {
        super(lx);
        this.model = model;
        setupDevices(model);
    }

    /** Validates model per-port LED counts and constructs all StarPusher port devices. */
    private void setupDevices(StarPushableModel model) {
        // Count the number of LEDs per StarPusher output port.
        Map<Pair<String, Integer>, Integer> counts = new HashMap<>();
        for (StarPushableLED led : model.getSpLeds()) {
            Pair<String, Integer> key = new Pair(led.getSpAddress(), led.getSpPort());
            if (!counts.containsKey(key)) {
                counts.put(key, 0);
            }
            counts.put(key, counts.get(key) + 1);
        }

        for (Map.Entry<Pair<String, Integer>, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            String spAddress = entry.getKey().getKey();
            Integer spPort = entry.getKey().getValue();

            // Make sure there's the model geometry has the expected number of LEDs per StarPusher port.
            if (count == EXPECTED_LEDS_PER_PORT) {
                try {
                    InetAddress networkAddress = InetAddress.getByName(spAddress);
                    StarPusherPortDevice device = new StarPusherPortDevice(networkAddress, STARPUSHER_NETWORK_PORT, spPort, count);
                    devices.put(entry.getKey(), device);
                } catch (UnknownHostException exception) {
                    System.err.println("Failed to lookup address: " + spAddress);
                }
            } else {
                System.err.println(
                        "Starpusher " + spAddress
                                + " port " + String.valueOf(spPort)
                                + " has " + String.valueOf(count)
                                + " leds, expected " + String.valueOf(EXPECTED_LEDS_PER_PORT));
            }


        }
    }

    /** This gets called on every frame to updates all LED colors. */
    @Override
    protected void onSend(int[] colors) {
        // Update the color of every LED in the StarPusher device buffers.
        for (StarPushableLED led : model.getSpLeds()) {
            int color =  colors[led.getPoint().index];
            setPixelColor(led.getSpAddress(), led.getSpPort(), led.getSpLedIndex(), color);
        }

        // Bulk send current device buffers to all StarPushers.
        sendPixelsToDevices();
    }

    /** Sets the color of a specific LED on the DOM, indexed by StarPusher address, output port, and index. */
    private void setPixelColor(String spAddress, int spPort, int index, int color) {
        Pair<String, Integer> key = new Pair<>(spAddress, spPort);
        devices.get(key).setPixelColor(index, color);
    }

    /** Send LED colors to all StarPushers. */
    private void sendPixelsToDevices() {
        for (StarPusherPortDevice device : devices.values()) {
            device.sendPixelsToDevice();
        }
    }
}
