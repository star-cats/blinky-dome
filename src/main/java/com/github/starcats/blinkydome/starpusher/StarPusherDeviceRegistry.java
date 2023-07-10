package com.github.starcats.blinkydome.starpusher;

import java.io.IOError;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class StarPusherDeviceRegistry {

    /** StarPushers expect to be on the 10.1.1.0 subnet. Their IP address is configured
     * statically based on their DIP switch value. The Laptop running LX Studio must have
     * its Ethernet interface set to the static IP 10.1.1.1.
     */
    private static final String LX_STATIC_IP = "10.1.1.1";

    /** Listen for StarPusher UDP announce messages on this port. */
    private static final int DISCOVERY_MULTICAST_PORT = 21059;

    /** Listen for StarPusher discovery multicast messages on this port. */
    private static final String DISCOVERY_MULTICAST_GROUP = "239.10.11.12";

    /** StarPusher's are listening for commands on this port. */
    private static final int DEVICE_UDP_PORT = 6868;

    /** Thread running the StarPusher discovery listener. */
    private Thread discoveryListenerThread;

    /** The StarPusher discovery listener listens for multicast discovery messages. */
    private DiscoveryListener discoveryListener = new DiscoveryListener(DISCOVERY_MULTICAST_PORT);

    /** All active StarPusher devices, keyed by Device ID. */
    private Map<Integer, StarPusherDevice> devices = new HashMap<>();

    /** Last seen time of all StarPusher devices, keyed by Device ID. Used to trigger expiry. */
    private Map<Integer, Long> lastSeen = new HashMap<>();

    /** Expire any StarPushers not seen for this period of time. */
    private static final int DEVICE_EXPIRY_TIME_MILLIS = 10 * 1000;

    /** Default to using a 2Mhz SPI clock speed. */
    private static final int SPI_CLOCK_SPEED = 2000000;

    /** Check for device expiry every 5 seconds. */
    private static final int EXPIRY_CHECK_INTERVAL_MILLIS = 5 * 1000;

    /** Sets the value of a pixel in a StarPusher's buffer.
     *
     * @param deviceId The zero-based deviceId number of the target StarPusher.
     * @param port The one-based port on the StarPusher. This is actually a virtual port because the StarPusher has
     *             a flat address space for pixels. This is to maintain backwards compatibility with PixelPusher code.
     * @param index Zero-based index of the pixel on port of deviceId.
     * @param r Red value in range 0-255.
     * @param g Green value in range 0-255.
     * @param b Blue value in range 0-255.
     * @param w Brightness value in range 0-255.
     */
    public void setPixel(int deviceId, int port, int index, int r, int g, int b, int w) {
        synchronized (this) {
            if (devices.containsKey(deviceId)) {
                StarPusherDevice device = devices.get(deviceId);
                device.setPixel(port, index, r, g, b, w);
            }
        }
    }

    /** Sends the StarPusher's pixel buffers to the pixel strips. This must be called after a sequence
     * of calls to setPixel().
     */
    public void sendPixelsToDevices() {
        synchronized (this) {
            for (StarPusherDevice device : devices.values()) {
                device.sendPixelsToDevice();
            }
        }
    }

    /** Periodically check for expired StarPusher devices. */
    private class DeviceExpiry implements Runnable {
        public void run() {
            while (true) {
                StarPusherDeviceRegistry.this.checkDeviceExpiry();
                try {
                    Thread.sleep(EXPIRY_CHECK_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private class DiscoveryListener implements Runnable {
        /** Listening for multicast messages on this port. */
        private int port;

        /** True while the discovery listener is running. */
        private AtomicBoolean running = new AtomicBoolean(false);

        /** Maximum size, in bytes, of UDP packet to receive. */
        private static final int MAX_PACKET_SIZE = 1024;

        /** Size, in bytes, of the StarPusher discovery UDP packet .*/
        private static final int DISCOVER_PACKET_SIZE = 7;

        DiscoveryListener(int port) {
            this.port = port;
        }

        public void run() {
            running.set(true);

            try {
                boolean found = false;
                for (NetworkInterface netif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    for (InetAddress addr : Collections.list(netif.getInetAddresses())) {
                        if (addr.getHostAddress() == LX_STATIC_IP) {
                            found = true;
                        }
                    }
                }
                if (!found) {
                    System.err.println("Couldn't find static IP address " + LX_STATIC_IP);
                }
            } catch(SocketException e) {
                return;
            }

            MulticastSocket socket;
            InetAddress multicastGroup;
            try {
                socket = new MulticastSocket(DISCOVERY_MULTICAST_PORT);
                socket.setInterface(InetAddress.getByName(LX_STATIC_IP));
                multicastGroup = InetAddress.getByName(DISCOVERY_MULTICAST_GROUP);
                socket.joinGroup(multicastGroup);
                DatagramPacket packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
                while (running.get()) {
                    socket.receive(packet);
                    processPacket(packet);
                }
                socket.leaveGroup(multicastGroup);
                socket.close();
            } catch (IOException e) {
                System.err.println(e);
            }
        }

        /** Processes a raw UDP packet received over multicast, parsing and handling StarPusher discovery packets. */
        private void processPacket(DatagramPacket packet) {
            byte[] data = packet.getData();

            if (packet.getLength() != DISCOVER_PACKET_SIZE) {
                return;
            }
            if (data[0] != 'S' || data[1] != 'P') {
                return;
            }

            int deviceId = data[2];
            StarPusherDeviceRegistry.this.deviceSeen(packet.getAddress(), deviceId);
        }

        public void stop() {
            running.set(false);
        }
    }

    /** Start listening for StarPusher discover messages. */
    public void startDeviceDiscovery() {
        if (discoveryListenerThread != null) {
            throw new RuntimeException("StarPusherDeviceRegistry already started.");
        }
        discoveryListenerThread = new Thread(discoveryListener);
        discoveryListenerThread.start();
        new Thread(new DeviceExpiry()).start();
    }

    /** Check if we need to expire any StarPushers we haven't seen for a while. */
    private void checkDeviceExpiry() {
        synchronized (this) {
            List<Integer> expired = new LinkedList<>();
            long currentTime = System.currentTimeMillis();
            for (Map.Entry<Integer, Long> entry :
                    lastSeen.entrySet()) {
                long age = currentTime - entry.getValue();
                if (age >= DEVICE_EXPIRY_TIME_MILLIS) {
                    expired.add(entry.getKey());
                }
            }

            for (Integer deviceId : expired) {
                expire(deviceId);
            }
        }
    }

    /** Add new devices and update the last seen time of a device. */
    private void deviceSeen(InetAddress address, int deviceId) {
        synchronized (this) {
            if (!devices.containsKey(deviceId)) {
                onNewDevice(new StarPusherDevice(address, DEVICE_UDP_PORT, deviceId));
            }
            lastSeen.put(deviceId, System.currentTimeMillis());
        }
    }

    /** Called when a new device is seen. */
    private void onNewDevice(StarPusherDevice device) {
        System.out.format("⭐ Discovered new StarPusher! Device ID: %d, IP Address: %s:%d\n", device.deviceId, device.address.getHostAddress(), device.port);

        devices.put(device.deviceId, device);
        try {
            device.configureSPI(SPI_CLOCK_SPEED);
        } catch (IOException e) {
            System.err.format("Failed to set PixelPusher %d SPI clock speed\n", device.deviceId);
        }
    }

    private void onExpiredDevice(StarPusherDevice device) {
        System.out.format("ERROR StarPusher %d expired!\n", device.deviceId);
        devices.remove(device.deviceId);
        lastSeen.remove(device.deviceId);
    }

    /** Expire a specific StarPusher identified by its Device ID. */
    private void expire(int deviceId) {
        synchronized (this) {
            if (devices.containsKey(deviceId)) {
                onExpiredDevice(devices.get(deviceId));
            }
        }
    }
}
