package com.github.starcats.blinkydome.starpusher;

public class PixelPusherToStarPusherIndexMapper {

    /**
     * StarPusher is configured to have 840 pixels per port.
     */
    private static final int STAR_PUSHER_PIXELS_PER_PORT = 840;


    /** Convert from a PixelPusher port and pixel index to a StarPusher pixel index.
     *
     * The PixelPusher has 4 outputs ports with 420 pixels each. The StarPusher
     * has 2 output ports with 840 pixels each.
     *
     * When sending commands to the StarPusher, all of its pixels are mapped into a linear address space [0, 1680) such
     * that strip 1 maps to [0, 840) and strip 2 maps to [840, 1680).
     */
    public static int calculatePixelIndex(int port, int index) {
        return (port - 1) * STAR_PUSHER_PIXELS_PER_PORT + index;
    }
}
