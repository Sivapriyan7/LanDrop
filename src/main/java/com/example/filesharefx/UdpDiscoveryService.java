package com.example.filesharefx;

import javafx.application.Platform;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class UdpDiscoveryService implements AutoCloseable {

    private static final String MULTICAST_GROUP = "230.0.0.1"; // Standard multicast address
    private static final int DISCOVERY_PORT = 4446; // Port for discovery packets
    private static final int BROADCAST_INTERVAL_MS = 5000; // Broadcast presence every 5 seconds
    private static final int DEVICE_TIMEOUT_MS = 15000; // Remove device if not heard from in 15 seconds

    private final ObservableList<String> discoveredDevicesFxList;
    private final Set<String> activeDeviceKeys = Collections.synchronizedSet(new HashSet<>());
    private final Timer deviceTimeoutTimer = new Timer("DeviceTimeoutChecker", true);


    private MulticastSocket multicastSocket;
    private final ExecutorService executorService = Executors.newFixedThreadPool(2); // One for listening, one for broadcasting

    private final String deviceName;
    private final int fileTransferPort; // The TCP port this device will use for file transfers
    private String localIpAddress;
    private volatile boolean running = false;

    public UdpDiscoveryService(ObservableList<String> discoveredDevicesFxList, String deviceName, int fileTransferPort) {
        this.discoveredDevicesFxList = discoveredDevicesFxList;
        this.deviceName = deviceName;
        this.fileTransferPort = fileTransferPort;
        this.localIpAddress = getLocalIpAddress();
        if (this.localIpAddress == null) {
            System.err.println("Could not determine local IP address. Discovery may not work correctly.");
            this.localIpAddress = "127.0.0.1"; // Fallback, not ideal
        }
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface ni = networkInterfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Error getting local IP address: " + e.getMessage());
        }
        return null;
    }

    public void start() {
        if (running) return;
        running = true;

        try {
            multicastSocket = new MulticastSocket(DISCOVERY_PORT);
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            multicastSocket.joinGroup(group); // Join the multicast group

            // Start listening for discovery packets
            executorService.submit(this::listenForPackets);

            // Start broadcasting presence periodically
            executorService.submit(this::broadcastPresencePeriodically);

            System.out.println("UDP Discovery Service started on " + localIpAddress + ":" + DISCOVERY_PORT +
                    ", broadcasting for file transfers on TCP port " + fileTransferPort);

        } catch (IOException e) {
            System.err.println("Error starting UDP Discovery Service: " + e.getMessage());
            running = false;
            close(); // Clean up
        }
    }

    private void listenForPackets() {
        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running && multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                multicastSocket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());
                InetAddress senderAddress = packet.getAddress();

                // Avoid processing our own broadcast if it somehow gets looped back
                if (senderAddress.getHostAddress().equals(localIpAddress)) {
                    // If the message is from our IP, check if it's from our specific file transfer port.
                    // This is a simple check; a more robust way would be a unique instance ID.
                    if (message.contains("\"ip\":\"" + localIpAddress + "\"") && message.contains("\"port\":" + fileTransferPort)) {
                        // System.out.println("Ignoring own broadcast packet.");
                        continue;
                    }
                }

                processDiscoveryMessage(message, senderAddress.getHostAddress());

            } catch (SocketException se) {
                if (running) { // Only log if we are supposed to be running
                    System.err.println("SocketException in listener (socket might be closing): " + se.getMessage());
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error receiving discovery packet: " + e.getMessage());
                }
            }
        }
        System.out.println("UDP Listener stopped.");
    }

    private void processDiscoveryMessage(String message, String senderIp) {
        try {
            // Basic JSON parsing (replace with a proper JSON library for robustness)
            // Example message: {"name": "OtherDevice", "ip": "192.168.1.101", "port": 5000}
            if (message.startsWith("{") && message.endsWith("}")) {
                String name = getStringValueFromJson(message, "name");
                // String ip = getStringValueFromJson(message, "ip"); // We use senderIp from packet
                String portStr = getStringValueFromJson(message, "port");

                if (name != null && portStr != null) {
                    int discoveredFileTransferPort = Integer.parseInt(portStr);
                    String deviceKey = senderIp + ":" + discoveredFileTransferPort;
                    String displayInfo = name + " (" + senderIp + ":" + discoveredFileTransferPort + ")";

                    // Check if it's our own device based on IP and declared file transfer port
                    if (senderIp.equals(this.localIpAddress) && discoveredFileTransferPort == this.fileTransferPort) {
                        return; // Don't add self to the list
                    }

                    synchronized (activeDeviceKeys) {
                        if (!activeDeviceKeys.contains(deviceKey)) {
                            activeDeviceKeys.add(deviceKey);
                            Platform.runLater(() -> {
                                if (!discoveredDevicesFxList.contains(displayInfo)) {
                                    discoveredDevicesFxList.add(displayInfo);
                                    System.out.println("Discovered: " + displayInfo);
                                }
                            });
                        }
                        // Reset timeout for this device
                        resetDeviceTimeout(deviceKey, displayInfo);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing discovery message '" + message + "': " + e.getMessage());
        }
    }

    private void resetDeviceTimeout(String deviceKey, String displayInfo) {
        // Cancel any existing timer for this device and schedule a new one
        // This part is a bit tricky with TimerTasks; managing individual tasks can be complex.
        // A simpler approach for this example: periodically prune the list.
        // For a more robust solution, you'd map deviceKey to its TimerTask.

        // Simplified pruning: Check all devices periodically
        // This is handled by the periodic pruning task initiated in the constructor (if you add one)
        // or you can do it when broadcasting. For now, we'll rely on devices rebroadcasting.
        // A full timeout mechanism would remove a device if no message is heard for DEVICE_TIMEOUT_MS
    }

    // Helper for basic JSON parsing (very rudimentary)
    private String getStringValueFromJson(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) {
            searchKey = "\"" + key + "\":"; // For numbers
            startIndex = json.indexOf(searchKey);
            if(startIndex == -1) return null;
        }

        startIndex += searchKey.length();
        int endIndex = json.indexOf("\"", startIndex); // For strings
        if (endIndex == -1) {
            endIndex = json.indexOf(",", startIndex); // For numbers followed by comma
            if (endIndex == -1) {
                endIndex = json.indexOf("}", startIndex); // For numbers at the end
            }
        }
        if (endIndex == -1) return null;

        return json.substring(startIndex, endIndex).trim();
    }


    private void broadcastPresencePeriodically() {
        Timer timer = new Timer(true); // Daemon thread
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!running) {
                    timer.cancel();
                    return;
                }
                broadcastPresenceOnce();
                pruneInactiveDevices();
            }
        }, 0, BROADCAST_INTERVAL_MS);
        System.out.println("UDP Broadcaster started.");
    }

    private void broadcastPresenceOnce() {
        if (multicastSocket == null || multicastSocket.isClosed() || localIpAddress == null) return;

        try {
            // Example message: {"name": "MyDevice", "ip": "192.168.1.100", "port": 5000}
            String message = String.format("{\"name\":\"%s\",\"ip\":\"%s\",\"port\":%d}",
                    deviceName, localIpAddress, fileTransferPort);
            byte[] buffer = message.getBytes();

            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, DISCOVERY_PORT);
            multicastSocket.send(packet);
            // System.out.println("Broadcasted presence: " + message);
        } catch (IOException e) {
            System.err.println("Error broadcasting presence: " + e.getMessage());
        }
    }

    private void pruneInactiveDevices() {
        // This is a simplified pruning. A more robust way would be to track last seen time for each device.
        // For now, we'll assume if a device stops broadcasting, it will eventually be manually removed
        // or the list will be cleared on app restart.
        // A proper implementation would involve:
        // Map<String, Long> lastSeenTime = new ConcurrentHashMap<>();
        // When a packet is received: lastSeenTime.put(deviceKey, System.currentTimeMillis());
        // In this prune method: iterate lastSeenTime, if (currentTime - lastSeen > DEVICE_TIMEOUT_MS) remove.
        // This current example doesn't fully implement the timeout removal to keep it simpler.
        // The activeDeviceKeys set helps in not re-adding, but not in active pruning based on timeout.
    }


    @Override
    public void close() {
        System.out.println("Closing UDP Discovery Service...");
        running = false;
        deviceTimeoutTimer.cancel();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
                multicastSocket.leaveGroup(group);
            } catch (IOException e) {
                System.err.println("Error leaving multicast group: " + e.getMessage());
            }
            multicastSocket.close();
            multicastSocket = null;
        }
        System.out.println("UDP Discovery Service closed.");
    }
}