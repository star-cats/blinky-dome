package com.github.starcats.blinkydome.starpusher;

import javax.xml.crypto.Data;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class StarPusherDeviceRegistry {

    /** Listen for StarPusher UDP announce messages on this port. */
    private static final int UDP_BROADCAST_PORT = 8080;

    /** Expire any StarPushers not seen for this period of time. */
    private static final int EXPIRY_TIME_MILLIS = 10 * 1000;

    private Thread udpListenerThread;
    private UDPBroadcastListener udpListener = new UDPBroadcastListener(UDP_BROADCAST_PORT);

    /** Up-to-date list of all non-expired StarPusher devices. */
    private Map<Integer, StarPusherDevice> devices = new HashMap<>();
    /** Last seen time of all StarPusher devices. Used to trigger expiry. */
    private Map<Integer, Long> lastSeen = new HashMap<>();

    public void setPixel(int group, int port, int index, int r, int g, int b, int w) {
        synchronized (this) {
            if (devices.containsKey(group)) {
                StarPusherDevice device = devices.get(group);
                device.setPixel(port, index, r, g, b, w);
            }
        }
    }

    public void sendPixelsToDevices() {
        synchronized (this) {
            for (StarPusherDevice device : devices.values()) {
                device.sendPixelsToDevice();
            }
        }
    }

    private record StarPusherAnnounceMessage(InetAddress address, int group) {
    }

    private class UDPBroadcastListener implements Runnable {
        private int port;

        private AtomicBoolean running = new AtomicBoolean(false);

        private static final int PACKET_SIZE = 1024;

        UDPBroadcastListener(int port) {
            this.port = port;
        }

        @Override
        public void run() {
            running.set(true);
            DatagramSocket socket;
            try {
                socket = new DatagramSocket(port);
            } catch (IOException e) {
                return;
            }
            DatagramPacket packet = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);
            while (running.get()) {
                try {
                    socket.receive(packet);
                } catch (IOException e) {
                    return;
                }
                processPacket(packet);
            }
        }

        private void processPacket(DatagramPacket packet) {
        }

        public void stop() {
            running.set(false);
        }
    }

    public void listenForDeviceAnnouncements() {
        if (udpListenerThread != null) {
            throw new RuntimeException("StarPusherDeviceRegistry already started.");
        }
        udpListenerThread = new Thread(udpListener);
        udpListenerThread.start();
    }

    /** Check if we need to expire any StarPushers we haven't seen for a while. */
    private void checkDeviceExpiry() {
        synchronized (this) {
            List<Integer> expired = new LinkedList<>();
            long currentTime = System.currentTimeMillis();
            for (Map.Entry<Integer, Long> entry :
                    lastSeen.entrySet()) {
                long age = currentTime - entry.getValue();
                if (age >= EXPIRY_TIME_MILLIS) {
                    expired.add(entry.getKey());
                }
            }

            for (Integer group : expired) {
                expire(group);
            }
        }
    }

    /** Expire a specific StarPusher. */
    private void expire(int group) {
        synchronized (this) {
            if (devices.containsKey(group)) {
                devices.remove(group);
                lastSeen.remove(group);
            }
        }
    }
}
