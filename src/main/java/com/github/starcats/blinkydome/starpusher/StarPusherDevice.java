package com.github.starcats.blinkydome.starpusher;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Deque;
import java.util.LinkedList;

/** A StarPusher device.
 *
 * This class manages a single StarPusher device.
 *
 * Each StarPusher is uniquely identified by its Device ID which is set by the DIP switches on the StarPusher's
 * StarShield. StarPusher's auto-assign themselves a static IP address of the form 10.1.1.1XX where XX is the
 * Pusher's Device ID. (e.g. deviceId = 1 will auto-assign 10.1.1.101).
 */
public class StarPusherDevice {
    /**
     * Maximum UDP packet size.
     */
    private static final int MAX_UDP_PACKET_SIZE = 1400;

    /**
     * Static IP address of the StarPusher, discovered by listening to UDP announce messages.
     */
    public final InetAddress address;

    /**
     * Port that StarPusher is listening for LED packets on.
     */
    public final int port;

    /**
     * Group that this StarPusher represents. This is set using the DIP switched on the StarShield.
     */
    public final int deviceId;

    /**
     * Queue of messages to send.
     */
    private Deque<StarPusherMessage> queue = new LinkedList<>();

    /** Send UDP messages using this socket. */
    private DatagramSocket socket;

    private static final int CONFIGURE_SPI_MESSAGE_SIZE = 7;

    public StarPusherDevice(InetAddress address, int port, int deviceId) {
        this.address = address;
        this.port = port;
        this.deviceId = deviceId;
    }

    /**
     * An object that can be serialized to a byte array.
     */
    private interface StarPusherMessage {
        public byte[] bytes();
    }

    /** Sets the r, g, b, brightness value of the pixel at index in the StarPusher's internal buffer. Ths StarPusher
     * pushes its internal buffer to attached LED strips every ~16ms (60FPS).
     */
    private record SetPixelMessage(int port, int index, int r, int g, int b, int w) implements StarPusherMessage {
        private static final int MESSAGE_SIZE = 7;
        public byte[] bytes() {
            return ByteBuffer
                    .allocate(MESSAGE_SIZE)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte) (port & 0xff))
                    .putShort((short) (index & 0xffff))
                    .put((byte) (r & 0xff))
                    .put((byte) (g & 0xff))
                    .put((byte) (b & 0xff))
                    .put((byte) (w & 0xff))
                    .array();
        }
    }

    public void sendPixelsToDevice() {
        synchronized (queue) {
            while (!queue.isEmpty()) {
                int flushCount = Math.min(queue.size() * SetPixelMessage.MESSAGE_SIZE, MAX_UDP_PACKET_SIZE - 2) / SetPixelMessage.MESSAGE_SIZE;
                ByteBuffer buffer = ByteBuffer.allocate(2 + flushCount * SetPixelMessage.MESSAGE_SIZE);
                buffer.put((byte) 'B');
                buffer.put((byte) 'D');
                for (int i = 0; i < flushCount; i++) {
                    StarPusherMessage message = queue.removeFirst();
                    buffer.put(message.bytes());
                }
                try {
                    send(buffer.array());
                } catch (IOException e) {
                    System.err.println(e);
                    return;
                }
            }
        }
    }
    public void configureSPI(long clockSpeedHz) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(CONFIGURE_SPI_MESSAGE_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte)'S');
        buffer.put((byte)'P');
        buffer.put((byte)'I');
        buffer.putInt((int)(clockSpeedHz&0xFFFFFFFF));
        send(buffer.array());
    }

    private void send(byte[] data) throws IOException {
        synchronized (this) {
            if (socket == null) {
                socket = new DatagramSocket();
            }
            DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
            socket.send(packet);
        }
    }

    private void addToQueue(StarPusherMessage message) {
        synchronized (queue) {
            queue.add(message);
        }
    }

    /**
     * Queues a setLED StarPusher message. Note, this only sets the LED state in the StarPusher's buffer. The flushLEDs
     * message must be sent to actually output the LED state to the pixel strip.
     *
     * @param port  Output port of StarPusher (LED0-3).
     * @param index Index of the LED along strip on the output port.
     * @param r     Red value as an unsigned 8-bit integer in the range [0, 255]
     * @param g     Blue value as an unsigned 8-bit integer in the range [0, 255]
     * @param b     Green value as an unsigned 8-bit integer in the range [0, 255]
     * @param w     Brightness value as an unsigned 8-bit integer in the range [0, 255]
     */
    public void setPixel(int port, int index, int r, int g, int b, int w) {
        addToQueue(new SetPixelMessage(port, index, r, g, b, w));
    }
}
