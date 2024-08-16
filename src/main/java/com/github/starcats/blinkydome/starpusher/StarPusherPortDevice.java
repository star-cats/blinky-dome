package com.github.starcats.blinkydome.starpusher;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * An LED output port on a StarPusher V3.
 *
 * This object maintains a buffer of pixel color values for all LEDs on this StarPusher port. Use setPixelColor
 * to update an individual pixel's color. Flush the buffer out to the device by calling sendPixelsToDevice().
 */
public class StarPusherPortDevice {
    /**
     * Maximum number of UDP packet a StarPusher can accept.
     *
     * We want to pack as many LED updates into each packet as possible.
     */
    private static final int MAX_DATAGRAM_SIZE = 1400;

    /**
     * Size of the StarPusher UDP packet preamble:
     *
     *  - '*'
     *  - 'P'
     *  - uint8 output_port
     *  - uint16 led_star_index
     *  - uint16 led_count
     *
     *  Note, the uint16s in the preamble are little endian byte encoded.
     */
    private static final int PREAMBLE_SIZE = 7;


    /** Bytes per LED (RGB). */
    private static final int BYTES_PER_LED = 3;

    /**
     * Buffer storing current colors of this devices LED.
     *
     * Use setPixelColor() to updates colors in this buffer, then call sendPixelsToDevice() to send all updates
     * in as few UDP packets as possible.
     */
    private final byte[] buffer;

    /** Address of this StarPusher. */
    private final InetAddress networkAddress;

    /** UDP port this StarPusher is listening for packets on. */
    private final int networkPort;

    /**
     * 1-indexed output port that this device represents.
     *
     * Each StarPusherV3Device represents an (networkAddress, spPort) combination. So one StarPusher driving 3 output
     * ports will need 3 StarPusherV3Device instances.
     */
    private final int spPort;

    /** UDP socket to send packets to StarPusher. */
    private DatagramSocket socket;

    /** Total number of LEDs on this StarPusher port. */
    private final int ledCount;

    public StarPusherPortDevice(InetAddress networkAddress, int networkPort, int spPort, int ledCount) {
        buffer = new byte[3*ledCount];
        this.networkAddress = networkAddress;
        this.networkPort = networkPort;
        this.spPort = spPort;
        this.ledCount = ledCount;

        try {
            socket = new DatagramSocket();
        } catch  (SocketException exception) {
            System.err.println("Failed to create UDP socket");
        }
    }

    /** Update the pixel's color at pixel index on this StarPusher port. */
    public void setPixelColor(int index, int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;

        buffer[BYTES_PER_LED * index] = (byte)r;
        buffer[BYTES_PER_LED * index + 1] = (byte)g;
        buffer[BYTES_PER_LED * index + 2] = (byte)b;
    }

    /**
     * Send the current pixel buffer to the StarPusher port.
     *
     * The StarPusher is configured to push its internal buffers at 60FPS (no sync). Technically this function will
     * push the pixel color state of this StarPusherV3PortDevice's buffer to the StarPusher's internal buffer via
     * UDP.
     */
    public void sendPixelsToDevice() {
        int remainingLeds = ledCount;
        int startIndex = 0;

        while (remainingLeds > 0) {
            // Send the maximum number of LED updates that will fit into the maximum StarPusher UDP packet size.
            int ledsToSend = Math.min((MAX_DATAGRAM_SIZE - PREAMBLE_SIZE) / BYTES_PER_LED, remainingLeds);

            // Generate the UDP packet preamble, the LITTLE_ENDIAN is important!
            ByteBuffer bytes = ByteBuffer.allocate(PREAMBLE_SIZE + BYTES_PER_LED * ledsToSend).order(ByteOrder.LITTLE_ENDIAN);
            bytes.put(0, (byte) '*');
            bytes.put(1, (byte) 'P');
            bytes.put(2, (byte) spPort);
            bytes.putShort(3, (short) startIndex);
            bytes.putShort(5, (short) ledsToSend);
            bytes.put(7, buffer, BYTES_PER_LED * startIndex, BYTES_PER_LED * ledsToSend);

            byte[] packetBytes = bytes.array();
            DatagramPacket packet = new DatagramPacket(packetBytes, packetBytes.length, networkAddress, networkPort);

            try {
                socket.send(packet);
            } catch (IOException exception) {
                System.err.println("Failed to send UDP packet");
            }

            remainingLeds -= ledsToSend;
            startIndex += ledsToSend;
        }
    }
}
