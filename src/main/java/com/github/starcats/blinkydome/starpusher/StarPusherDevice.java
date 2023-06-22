package com.github.starcats.blinkydome.starpusher;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Deque;
import java.util.LinkedList;

public class StarPusherDevice {
    /** StarPusher is configured to have 840 pixels per port. */
    private static final int PIXELS_PER_PORT = 840;

    /** Maximum UDP packet size. */
    private static final int MAX_UDP_PACKET_SIZE = 1500;

    /** Static IP address of the StarPusher, discovered by listening to UDP announce messages. */
    private InetAddress address;

    /** Port that StarPusher is listening for LED packets on. */
    private int port;

    /** Group that this StarPusher represents. This is set using the DIP switched on the StarShield. */
    private int group;

    /** Queue of messages to send. */
    private Deque<StarPusherMessage> queue = new LinkedList<>();

    private DatagramSocket socket;
    public StarPusherDevice(InetAddress address, int port, int group) {
        this.address = address;
        this.port = port;
        this.group = group;
    }

    /** An object that can be serialized to a byte array. */
    private interface StarPusherMessage {
        public byte[] bytes();
    }

    private record SetPixelMessage(int index, int r, int g, int b, int w) implements StarPusherMessage{
        private static final int MESSAGE_SIZE = 6;
        public byte[] bytes() {
            return ByteBuffer
                    .allocate(MESSAGE_SIZE)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) (index & 0xffff))
                    .put((byte) (r & 0xff))
                    .put((byte) (g & 0xff))
                    .put((byte) (b & 0xff))
                    .put((byte) (w & 0xff))
                    .array();
        }
    }
    private record FlushPixelsMessage() implements StarPusherMessage{
        public byte[] bytes() {
            return new SetPixelMessage(0xffff, 0xff, 0xff, 0xff, 0xff).bytes();
        }
    }

    public void sendPixelsToDevice() {
        synchronized (queue) {
            if (!queue.isEmpty()) {
                addToQueue(new FlushPixelsMessage());
            }
            while (!queue.isEmpty()) {
                int flushCount = Math.min(queue.size() * SetPixelMessage.MESSAGE_SIZE, MAX_UDP_PACKET_SIZE) / SetPixelMessage.MESSAGE_SIZE;
                ByteBuffer buffer = ByteBuffer.allocate(flushCount * SetPixelMessage.MESSAGE_SIZE);
                for (int i = 0; i < flushCount; i++) {
                    StarPusherMessage message = queue.removeFirst();
                    buffer.put(message.bytes());
                }
                try {
                    send(buffer.array());
                } catch (IOException e) {
                    return;
                }
            }
        }
    }

    private void send(byte[] data) throws IOException {
        synchronized (socket) {
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

    /** Queues a setLED StarPusher message. Note, this only sets the LED state in the StarPusher's buffer. The flushLEDs
     * message must be sent to actually output the LED state to the pixel strip.
     * @param port Output port of StarPusher (currently 0 or 1).
     * @param index Index of the LED along strip on the output port.
     * @param r Red value as an unsigned 8-bit integer in the range [0, 255]
     * @param g Blue value as an unsigned 8-bit integer in the range [0, 255]
     * @param b Green value as an unsigned 8-bit integer in the range [0, 255]
     * @param w Brightness value as an unsigned 8-bit integer in the range [0, 255]
     */
    public void setPixel(int port, int index, int r, int g, int b, int w) {
        addToQueue(new SetPixelMessage(port * PIXELS_PER_PORT + index, r, g, b, w));
    }
}
