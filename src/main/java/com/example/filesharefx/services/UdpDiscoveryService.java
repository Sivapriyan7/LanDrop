package com.example.filesharefx.services;

import com.example.filesharefx.model.DeviceInfo;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import javafx.application.Platform;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UdpDiscoveryService {

    // CONSULT LOCAL SEND PROTOCOL FOR THESE VALUES
    private static final int DISCOVERY_PORT = 53317; // Common port for LocalSend, VERIFY!
    private static final String BROADCAST_ADDRESS = "255.255.255.255"; // General broadcast
    private static final int BROADCAST_INTERVAL_S = 5;
    private static final int DEVICE_TIMEOUT_S = 15;

    private final ObservableList<DeviceInfo> discoveredDevicesUi;
    private final Map<String, DeviceInfo> activeDevicesMap; // Fingerprint -> DeviceInfo
    private final DeviceInfo ownDeviceInfo;
    private final Gson gson;

    private DatagramSocket socket;
    private volatile boolean running = false;
    private ScheduledExecutorService scheduler;
    private String localIpAddress;


    public UdpDiscoveryService(ObservableList<DeviceInfo> discoveredDevicesUi, DeviceInfo ownDeviceInfo) {
        this.discoveredDevicesUi = discoveredDevicesUi;
        this.ownDeviceInfo = ownDeviceInfo;
        this.activeDevicesMap = new ConcurrentHashMap<>();
        this.gson = new Gson();
        this.localIpAddress = getLocalNetworkIp();
        if (this.localIpAddress != null) {
            this.ownDeviceInfo.setIp(this.localIpAddress); // Set self IP
        } else {
            System.err.println("COULD NOT DETERMINE LOCAL IP FOR UDP DISCOVERY");
            // Potentially fall back or prevent starting
        }
    }

    public void start() {
        if (running) return;
        if (localIpAddress == null) {
            System.err.println("UDP Discovery cannot start without a local IP address.");
            return;
        }
        running = true;
        scheduler = Executors.newScheduledThreadPool(2);

        try {
            socket = new DatagramSocket(DISCOVERY_PORT); // Bind to specific port for listening
            socket.setBroadcast(true); // Allow sending broadcast messages
            System.out.println("UDP Discovery Service: Listening on port " + DISCOVERY_PORT);

            // Start listening thread
            Thread listenerThread = new Thread(this::listen);
            listenerThread.setDaemon(true);
            listenerThread.start();

            // Start broadcasting periodically
            scheduler.scheduleAtFixedRate(this::broadcastPresence, 0, BROADCAST_INTERVAL_S, TimeUnit.SECONDS);
            // Start cleanup task for stale devices
            scheduler.scheduleAtFixedRate(this::cleanupStaleDevices, DEVICE_TIMEOUT_S, DEVICE_TIMEOUT_S / 2, TimeUnit.SECONDS);

            System.out.println("UDP Discovery Service started. Own Fingerprint: " + ownDeviceInfo.getFingerprint());

        } catch (SocketException e) {
            System.err.println("Error starting UDP Discovery Service: " + e.getMessage());
            e.printStackTrace();
            running = false;
            if (scheduler != null) scheduler.shutdownNow();
        }
    }

    private void listen() {
        byte[] buffer = new byte[2048]; // Increased buffer size
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running && socket != null && !socket.isClosed()) {
            try {
                socket.receive(packet);
                String senderIp = packet.getAddress().getHostAddress();
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                // Assuming the message is a JSON representation of DeviceInfo
                DeviceInfo receivedInfo = gson.fromJson(message, DeviceInfo.class);

                if (receivedInfo != null && receivedInfo.getFingerprint() != null) {
                    if (ownDeviceInfo.getFingerprint().equals(receivedInfo.getFingerprint())) {
                        continue; // It's our own broadcast
                    }

                    receivedInfo.setIp(senderIp); // Use IP from packet for reliability
                    receivedInfo.setServerTimestamp(System.currentTimeMillis()); // Mark as recently seen

                    // Add or update the device
                    boolean isNew = activeDevicesMap.put(receivedInfo.getFingerprint(), receivedInfo) == null;
                    if (isNew) {
                        System.out.println("Discovered new device: " + receivedInfo.getAlias() + " (" + receivedInfo.getIp() + ":" + receivedInfo.getPort() + ")");
                    }
                    updateUiDeviceList();
                }

            } catch (SocketTimeoutException e) {
                // Expected, do nothing
            } catch (JsonSyntaxException e) {
                System.err.println("Received invalid JSON in UDP packet: " + e.getMessage());
            } catch (IOException e) {
                if (running) {
                    System.err.println("UDP Listener error: " + e.getMessage());
                }
            }
        }
        System.out.println("UDP Listener stopped.");
    }

    private void broadcastPresence() {
        if (!running || socket == null || socket.isClosed() || localIpAddress == null) return;

        try {
            ownDeviceInfo.setServerTimestamp(System.currentTimeMillis()); // Update timestamp before sending
            String message = gson.toJson(ownDeviceInfo);
            byte[] sendData = message.getBytes(StandardCharsets.UTF_8);

            InetAddress broadcastInetAddress = InetAddress.getByName(BROADCAST_ADDRESS);
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, broadcastInetAddress, DISCOVERY_PORT);
            socket.send(sendPacket);
            // System.out.println("Broadcasted presence: " + ownDeviceInfo.getAlias());

        } catch (IOException e) {
            System.err.println("Error broadcasting presence: " + e.getMessage());
        }
    }

    private void cleanupStaleDevices() {
        if (!running) return;
        long now = System.currentTimeMillis();
        boolean changed = activeDevicesMap.values().removeIf(
                device -> (now - device.getServerTimestamp()) > (DEVICE_TIMEOUT_S * 1000L)
        );
        if (changed) {
            System.out.println("Cleaned up stale devices.");
            updateUiDeviceList();
        }
    }

    private void updateUiDeviceList() {
        Platform.runLater(() -> {
            discoveredDevicesUi.setAll(new ArrayList<>(activeDevicesMap.values()));
        });
    }

    public void stop() {
        System.out.println("Stopping UDP Discovery Service...");
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.err.println("Scheduler did not terminate cleanly.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        activeDevicesMap.clear();
        Platform.runLater(discoveredDevicesUi::clear);
        System.out.println("UDP Discovery Service stopped.");
    }

    private String getLocalNetworkIp() {
        try {
            // Prefer a non-loopback, site-local IPv4 address
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface ni = networkInterfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress address = inetAddresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
            // Fallback: could return InetAddress.getLocalHost().getHostAddress() but it might be 127.0.0.1
            // Forcing a more specific selection might be needed if this fails.
            System.err.println("Could not find a suitable site-local IPv4 address. UDP discovery might not work as expected.");
            // Attempt a general lookup, might be loopback.
            try { return InetAddress.getLocalHost().getHostAddress(); } catch (UnknownHostException ignored) {}

        } catch (SocketException e) {
            System.err.println("Failed to get local IP address: " + e.getMessage());
        }
        return null;
    }
}