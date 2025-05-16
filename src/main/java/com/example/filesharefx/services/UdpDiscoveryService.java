package com.example.filesharefx.services;

import com.example.filesharefx.FileServerApp;
import com.example.filesharefx.model.DeviceInfo;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import javafx.application.Platform;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UdpDiscoveryService {

    // Protocol Defaults
    private static final int DISCOVERY_PORT = 53317;
    private static final String MULTICAST_ADDRESS = "224.0.0.167"; // As per LocalSend protocol
    private static final String PROTOCOL_VERSION = "2.0";

    private static final int ANNOUNCE_INTERVAL_S = 5; // How often we send our main announcement
    private static final int DEVICE_TIMEOUT_S = 15;   // How long before considering a device stale

    private final ObservableList<DeviceInfo> discoveredDevicesUi;
    private final Map<String, DeviceInfo> activeDevicesMap; // Fingerprint -> DeviceInfo
    private final DeviceInfo ownDeviceInfo; // This app's info
    private final FileServerApp mainApp; // Reference to main app for HttpClient and other info

    private final Gson gson;
    private MulticastSocket multicastSocket;
    private volatile boolean running = false;
    private ScheduledExecutorService scheduler;
    private String localIpAddress;
    private NetworkInterface multicastNetworkInterface;

    public UdpDiscoveryService(ObservableList<DeviceInfo> discoveredDevicesUi, DeviceInfo ownDeviceInfo, FileServerApp mainApp) {
        this.discoveredDevicesUi = discoveredDevicesUi;
        this.ownDeviceInfo = ownDeviceInfo; // Should have alias, fingerprint, model, type, http port set by FileServerApp
        this.ownDeviceInfo.setVersion(PROTOCOL_VERSION); // Ensure protocol version
        this.mainApp = mainApp; // For sending HTTP responses
        this.activeDevicesMap = new ConcurrentHashMap<>();
        this.gson = new Gson();
    }

    public String getLocalIpAddress() {
        return this.localIpAddress;
    }

    /**
     * Starts the UDP Discovery Service.
     * <p>
     * This method performs the following steps:
     * <ul>
     *     <li>Checks if the service is already running and exits if so.</li>
     *     <li>Attempts to determine the best local IP address to use.</li>
     *     <li>Initializes and binds a {@link MulticastSocket} to the discovery port.</li>
     *     <li>Joins a multicast group using a suitable network interface if found, or falls back to default.</li>
     *     <li>Configures the socket's loopback mode and time-to-live settings.</li>
     *     <li>Starts background threads for listening to multicast packets and scheduling periodic announcements and cleanup tasks.</li>
     * </ul>
     * <p>
     * If any step fails (e.g., IP cannot be determined, socket errors), the method logs the error and aborts startup.
     *
     * @see #stop() for stopping the service
     * @see #sendAnnouncement() for sending periodic device presence messages
     * @see #listenLoop() for handling incoming multicast messages
     * @see #cleanupStaleDevices() for removing inactive devices
     */


    public void start() {
        if (running) return;

        this.localIpAddress = getBestLocalIpAddress();
        if (this.localIpAddress == null) {
            System.err.println("UDP Discovery: CRITICAL - Could not determine a suitable local IP address. Service cannot start reliably.");
            return;
        }
        this.ownDeviceInfo.setIp(this.localIpAddress);
        this.ownDeviceInfo.setProtocol(mainApp.getOwnProtocol()); // http or https (from FileServerApp)

        try {
            multicastSocket = new MulticastSocket(DISCOVERY_PORT); // Bind to the discovery port for listening
            InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);

            multicastNetworkInterface = findMulticastNetworkInterface(group);
            if (multicastNetworkInterface != null) {
                multicastSocket.joinGroup(new InetSocketAddress(group, DISCOVERY_PORT), multicastNetworkInterface);
                System.out.println("UDP Discovery: Joined multicast group " + MULTICAST_ADDRESS + " on interface: " + multicastNetworkInterface.getDisplayName());
            } else {
                // Fallback if specific interface not found (might not work on all systems/configs)
                multicastSocket.joinGroup(group);
                System.out.println("UDP Discovery: Joined multicast group " + MULTICAST_ADDRESS + " using default interface.");
            }
            multicastSocket.setLoopbackMode(false); // Avoid receiving own packets if possible (though fingerprint check handles it)
            multicastSocket.setTimeToLive(4); // Keep multicast packets within a reasonable local network scope

            running = true;
            scheduler = Executors.newScheduledThreadPool(2, r -> {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setDaemon(true);
                return t;
            });

            Thread listenerThread = new Thread(this::listenLoop);
            listenerThread.setDaemon(true);
            listenerThread.setName("UdpDiscoveryListener");
            listenerThread.start();

            // Send initial announcement almost immediately, then periodically
            scheduler.scheduleAtFixedRate(this::sendAnnouncement, 1, ANNOUNCE_INTERVAL_S, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(this::cleanupStaleDevices, DEVICE_TIMEOUT_S, DEVICE_TIMEOUT_S / 2, TimeUnit.SECONDS);

            System.out.println("UDP Discovery Service started on " + MULTICAST_ADDRESS + ":" + DISCOVERY_PORT);
            System.out.println("UDP Discovery: Own Device Info for announcement: " + gson.toJson(this.ownDeviceInfo));


        } catch (IOException e) {
            System.err.println("UDP Discovery: Error starting service: " + e.getMessage());
            e.printStackTrace();
            stop(); // Cleanup
        }
    }

    // In UdpDiscoveryService.java
    public void addOrUpdateDiscoveredDeviceViaHttp(DeviceInfo deviceInfoFromHttp) {
        if (deviceInfoFromHttp == null || deviceInfoFromHttp.getFingerprint() == null ||
                ownDeviceInfo.getFingerprint().equals(deviceInfoFromHttp.getFingerprint())) {
            return; // Ignore self or invalid
        }
        // Ensure IP is set if not already (though HTTP sender should ideally include it, or get from exchange if possible)
        // For simplicity, assume it's in deviceInfoFromHttp
        deviceInfoFromHttp.setLastSeenTimestamp(System.currentTimeMillis());
        addOrUpdateActiveDevice(deviceInfoFromHttp);
        System.out.println("UDP Discovery: Device " + deviceInfoFromHttp.getAlias() + " updated/added via HTTP register call.");
    }

    private void sendAnnouncement() {
        if (!running || multicastSocket == null || multicastSocket.isClosed()) return;

        ownDeviceInfo.setAnnounce(true); // This is a primary announcement
        // Port should be the HTTP/S server port, already set in ownDeviceInfo
        // IP should be set, protocol (http/https) should be set
        ownDeviceInfo.setDownload(mainApp.isDownloadEnabled()); // Get current download status

        String message = gson.toJson(ownDeviceInfo);
        sendUdpMulticast(message);
        // System.out.println("UDP Discovery: Sent announcement: " + message);
    }

    private void sendUdpMulticast(String jsonMessage) {
        try {
            byte[] sendData = jsonMessage.getBytes(StandardCharsets.UTF_8);
            InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, group, DISCOVERY_PORT);
            if (multicastNetworkInterface != null) {
                // Some OS versions might require setting the interface for sending too
                multicastSocket.setNetworkInterface(multicastNetworkInterface);
            }
            multicastSocket.send(sendPacket);
        } catch (IOException e) {
            if (running) {
                System.err.println("UDP Discovery: Error sending multicast message: " + e.getMessage());
            }
        }
    }

    /**
     * Listens for incoming multicast UDP packets in a loop.
     * <p>
     * This method runs in a background (daemon) thread and blocks while waiting for packets using
     * {@link java.net.MulticastSocket#receive(java.net.DatagramPacket)}. It processes incoming packets
     * containing JSON-encoded {@link DeviceInfo} objects from other devices on the network.
     * <p>
     * The loop continues as long as the discovery service is marked as {@code running}, and the socket
     * is valid and open. For each packet:
     * <ul>
     *   <li>Parses the sender's IP and deserializes the JSON payload into a {@code DeviceInfo} object.</li>
     *   <li>Ignores packets from the same device (identified by fingerprint).</li>
     *   <li>Updates or adds the device to the active devices map.</li>
     *   <li>If the received device is announcing itself, sends a response back.</li>
     * </ul>
     * <p>
     * Handles malformed JSON, I/O exceptions, and logs issues accordingly. The method exits cleanly when the
     * discovery service is stopped or the socket is closed.
     *
     * @see DeviceInfo
     * @see #respondToAnnouncement(DeviceInfo)
     * @see #addOrUpdateActiveDevice(DeviceInfo)
     */
    private void listenLoop() {
        byte[] buffer = new byte[2048]; // Buffer for incoming packets
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        System.out.println("UDP Discovery: Listener thread started.");
        while (running && multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                multicastSocket.receive(packet); // Blocking call
                String senderIp = packet.getAddress().getHostAddress();
                String messageJson = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                DeviceInfo receivedInfo = gson.fromJson(messageJson, DeviceInfo.class);

                if (receivedInfo == null || receivedInfo.getFingerprint() == null) {
                    System.err.println("UDP Discovery: Received invalid or incomplete device info.");
                    continue;
                }

                // Ignore self
                if (ownDeviceInfo.getFingerprint().equals(receivedInfo.getFingerprint())) {
                    continue;
                }

                // Use IP from packet header for reliability, port from message (it's the HTTP/S port)
                receivedInfo.setIp(senderIp);
                receivedInfo.setLastSeenTimestamp(System.currentTimeMillis());

                //System.out.println("UDP Discovery: Received packet from " + senderIp + ":" + packet.getPort() + " ("+receivedInfo.getAlias()+", announce="+receivedInfo.isAnnounce()+")");

                boolean deviceJustAdded = addOrUpdateActiveDevice(receivedInfo);

                // If it's an announcement from someone else, we need to respond.
                if (receivedInfo.isAnnounce()) {
                    System.out.println("UDP Discovery: Received an ANNOUNCE from " + receivedInfo.getAlias() + ". Responding...");
                    respondToAnnouncement(receivedInfo);
                } else {
                    // If announce is false, it's likely a response or a periodic presence update without expecting a reply.
                    // We've already updated them in activeDevicesMap.
                    if (deviceJustAdded) {
                        System.out.println("UDP Discovery: New device " + receivedInfo.getAlias() + " appeared via non-announce packet.");
                    }
                }

            } catch (SocketTimeoutException e) {
                // This is normal if a timeout is set on the socket, but we are not setting one here (blocking receive)
            } catch (JsonSyntaxException e) {
                System.err.println("UDP Discovery: Received malformed JSON: " + e.getMessage());
            } catch (IOException e) {
                if (running) { // Only log if we're supposed to be running
                    System.err.println("UDP Discovery: Listener I/O error: " + e.getMessage());
                    // Consider a small delay or specific error handling if this repeats rapidly
                }
            }
        }
        System.out.println("UDP Discovery: Listener thread stopped.");
    }

    private boolean addOrUpdateActiveDevice(DeviceInfo deviceInfo) {
        // Returns true if the device was newly added (not just updated)
        DeviceInfo existingDevice = activeDevicesMap.put(deviceInfo.getFingerprint(), deviceInfo);
        updateUiDeviceList(); // Always update UI if any change or refresh
        return existingDevice == null || !existingDevice.getIp().equals(deviceInfo.getIp()) || existingDevice.getPort() != deviceInfo.getPort();
    }

    private void respondToAnnouncement(DeviceInfo announcerInfo) {
        // Prepare our own device info for the response
        DeviceInfo responseDeviceInfo = new DeviceInfo(
                ownDeviceInfo.getAlias(),
                ownDeviceInfo.getFingerprint(),
                ownDeviceInfo.getDeviceModel(),
                ownDeviceInfo.getDeviceType(),
                ownDeviceInfo.getPort(), // Our HTTP/S port
                mainApp.isDownloadEnabled()
        );
        responseDeviceInfo.setVersion(PROTOCOL_VERSION);
        responseDeviceInfo.setIp(this.localIpAddress); // Our IP
        responseDeviceInfo.setProtocol(mainApp.getOwnProtocol());
        responseDeviceInfo.setAnnounce(false); // This is a response, not an announcement

        // Primary: HTTP Response
        // This needs to run in a separate thread to not block the UDP listener
        mainApp.getExecutorService().submit(() -> {
            sendHttpResponseToDevice(announcerInfo, responseDeviceInfo);
        });

        // Fallback or Supplementary: UDP Multicast Response (as per protocol docs "members can also respond with a Multicast/UDP message")
        // This means sending our info (with announce:false) to the multicast group.
        // This seems a bit redundant if the HTTP response works, but the protocol mentions it.
        // Let's add a small delay to prioritize HTTP and avoid immediate UDP storm.
        scheduler.schedule(() -> {
            System.out.println("UDP Discovery: Sending UDP fallback/supplementary response for " + announcerInfo.getAlias());
            sendUdpMulticast(gson.toJson(responseDeviceInfo));
        }, 500, TimeUnit.MILLISECONDS); // Small delay
    }

    private void sendHttpResponseToDevice(DeviceInfo targetDevice, DeviceInfo payloadDeviceInfo) {
        try {
            String targetHttpProtocol = targetDevice.getProtocol() != null ? targetDevice.getProtocol() : "http";
            // THE PROTOCOL DOES NOT SPECIFY THE HTTP ENDPOINT FOR THIS RESPONSE.
            // ASSUMING "/api/localsend/v1/register" or a new one like "/discovery-response"
            // For now, let's try POSTing to their base /info or /register endpoint.
            // This needs to be VERIFIED against the full LocalSend protocol.
            // A common pattern is POSTing to the same endpoint they use for their announcements if it accepts it,
            // or a dedicated response endpoint.
            // Let's assume a conceptual endpoint for now.
            String endpointPath = FileShareHttpServer.API_BASE_PATH + "/register"; // Placeholder - VERIFY THIS PATH
            String targetUrl = targetHttpProtocol + "://" + targetDevice.getIp() + ":" + targetDevice.getPort() + endpointPath;

            String requestJson = gson.toJson(payloadDeviceInfo);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(5)) // Short timeout for discovery response
                    .build();

            System.out.println("UDP Discovery: Sending HTTP response to " + targetDevice.getAlias() + " at " + targetUrl);
            HttpResponse<String> response = mainApp.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("UDP Discovery: HTTP response sent successfully to " + targetDevice.getAlias() + ". Status: " + response.statusCode());
            } else {
                System.err.println("UDP Discovery: HTTP response to " + targetDevice.getAlias() + " failed. Status: " + response.statusCode() + " Body: " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("UDP Discovery: Exception sending HTTP response to " + targetDevice.getAlias() + ": " + e.getMessage());
            // Here, one might trigger the UDP fallback more explicitly if desired, but current design sends it anyway after a delay.
        }
    }


    private void cleanupStaleDevices() {
        if (!running) return;
        long now = System.currentTimeMillis();
        boolean changed = activeDevicesMap.values().removeIf(
                device -> (now - device.getLastSeenTimestamp()) > (DEVICE_TIMEOUT_S * 1000L)
        );
        if (changed) {
            System.out.println("UDP Discovery: Cleaned up stale devices.");
            updateUiDeviceList();
        }
    }

    private void updateUiDeviceList() {
        Platform.runLater(() -> {
            discoveredDevicesUi.setAll(new ArrayList<>(activeDevicesMap.values()));
        });
    }

    public void stop() {
        System.out.println("UDP Discovery: Stopping service...");
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    //System.err.println("UDP Discovery: Scheduler did not terminate cleanly.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
                if (multicastNetworkInterface != null) {
                    multicastSocket.leaveGroup(new InetSocketAddress(group, DISCOVERY_PORT), multicastNetworkInterface);
                } else {
                    multicastSocket.leaveGroup(group);
                }
            } catch (IOException e) {
                // System.err.println("UDP Discovery: Error leaving multicast group: " + e.getMessage());
            }
            multicastSocket.close();
        }
        activeDevicesMap.clear();
        Platform.runLater(discoveredDevicesUi::clear);
        System.out.println("UDP Discovery: Service stopped.");
    }

    private String getBestLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            List<InetAddress> candidateAddresses = new ArrayList<>();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || !ni.supportsMulticast()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        // Prefer non-loopback, site-local IPv4 for LAN
                        return addr.getHostAddress();
                    } else if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        candidateAddresses.add(addr); // Store other non-loopback IPv4 as fallback
                    }
                }
            }
            if (!candidateAddresses.isEmpty()) {
                return candidateAddresses.get(0).getHostAddress(); // Return first non-loopback IPv4 if no site-local found
            }
            // Final fallback
            return InetAddress.getLocalHost().getHostAddress();

        } catch (SocketException | UnknownHostException e) {
            System.err.println("UDP Discovery: Error getting local IP address: " + e.getMessage());
            return null;
        }
    }

    private NetworkInterface findMulticastNetworkInterface(InetAddress multicastGroup) throws SocketException {
        Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
        while (nis.hasMoreElements()) {
            NetworkInterface ni = nis.nextElement();
            if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || !ni.supportsMulticast()) {
                continue;
            }
            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                // Check if address family matches multicast group and is suitable
                if (multicastGroup instanceof Inet4Address && addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                    return ni; // Return first suitable IPv4 interface
                }
                // Add similar check for IPv6 if multicastGroup could be IPv6
            }
        }
        System.err.println("UDP Discovery: Could not find a specific NetworkInterface for multicast. Using OS default.");
        return null; // Let OS pick
    }
}