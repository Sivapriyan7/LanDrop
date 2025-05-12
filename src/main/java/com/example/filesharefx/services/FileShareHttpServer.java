package com.example.filesharefx.services;

import com.example.filesharefx.FileServerApp;
import com.example.filesharefx.model.DeviceInfo;
import com.example.filesharefx.model.FileMetadata;
import com.example.filesharefx.model.FileTransferRequest;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.scene.control.ButtonType;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class FileShareHttpServer {

    private HttpServer server;
    private int port; // Will store the dynamically assigned or configured port
    private final Gson gson;
    private final DeviceInfo ownDeviceInfoRef; // Reference to the main app's DeviceInfo
    private final FileServerApp mainApp;

    // --- API Paths (Consult LocalSend Protocol for exact paths) ---
    public static final String API_BASE_PATH = "/api/localsend/v1"; // Base path from protocol
    public static final String INFO_PATH = API_BASE_PATH + "/info";
    public static final String REGISTER_PATH = API_BASE_PATH + "/register"; // For devices responding to announcements via HTTP
    public static final String SEND_REQUEST_PATH = API_BASE_PATH + "/send-request";
    public static final String SEND_FILE_PATH = API_BASE_PATH + "/send";


    public FileShareHttpServer(DeviceInfo ownDeviceInfo, FileServerApp mainApp, int desiredPort) {
        this.ownDeviceInfoRef = ownDeviceInfo;
        this.mainApp = mainApp;
        this.port = desiredPort; // 0 for dynamic, or a specific port like 53317
        this.gson = new Gson();
    }

    public void start() throws IOException {
        // If port is 0, OS assigns an available port. Otherwise, tries the specified port.
        server = HttpServer.create(new InetSocketAddress(this.port), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true);
            return t;
        }));

        // Define handlers based on LocalSend protocol
        server.createContext(INFO_PATH, new InfoHandler());
        server.createContext(REGISTER_PATH, new RegisterHandler());
        server.createContext(SEND_REQUEST_PATH, new SendRequestHandler());
        server.createContext(SEND_FILE_PATH, new SendFileHandler());

        server.start();
        this.port = server.getAddress().getPort(); // Get the actual port being used
        System.out.println("HTTP Server started on port: " + this.port);

        // Update the application's main DeviceInfo object with the actual port
        this.ownDeviceInfoRef.setPort(this.port);
    }

    public void stop() {
        if (server != null) {
            System.out.println("Stopping HTTP Server...");
            server.stop(1); // Stop with a 1-second delay for existing connections
            System.out.println("HTTP Server stopped.");
        }
    }

    public int getPort() {
        return this.port;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, String responseBody) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    // --- Handler for current device info ---
    class InfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                // Ensure ownDeviceInfoRef is up-to-date (especially IP, protocol, download status)
                ownDeviceInfoRef.setIp(mainApp.getUdpDiscoveryService().getLocalIpAddress()); // Refresh IP just in case
                ownDeviceInfoRef.setProtocol(mainApp.getOwnProtocol());
                ownDeviceInfoRef.setDownload(mainApp.isDownloadEnabled());
                // ownDeviceInfoRef.setAnnounce(false); // Typically info requests are not announcements

                String response = gson.toJson(ownDeviceInfoRef);
                sendResponse(exchange, 200, "application/json", response);
            } else {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            }
        }
    }

    // --- Handler for devices registering/responding to our announcements via HTTP ---
    class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String requestOriginIp = exchange.getRemoteAddress().getAddress().getHostAddress();
                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
                     BufferedReader br = new BufferedReader(isr)) {
                    String requestBody = br.lines().collect(Collectors.joining("\n"));
                    DeviceInfo respondingDevice = gson.fromJson(requestBody, DeviceInfo.class);

                    if (respondingDevice != null && respondingDevice.getFingerprint() != null) {
                        // The responding device should send its own IP in its DeviceInfo.
                        // If not, we can use the requestOriginIp, but trust the payload more if present.
                        if (respondingDevice.getIp() == null || respondingDevice.getIp().isEmpty()) {
                            respondingDevice.setIp(requestOriginIp);
                        }
                        // This device is making itself known or responding to our announcement.
                        System.out.println("HTTP Server: Received POST on /register from: " + respondingDevice.getAlias() + " (" + respondingDevice.getIp() + ":" + respondingDevice.getPort() + ")");
                        mainApp.getUdpDiscoveryService().addOrUpdateDiscoveredDeviceViaHttp(respondingDevice);

                        // Respond to the POST request
                        // The protocol doesn't specify the response body for this, an OK is probably fine.
                        sendResponse(exchange, 200, "application/json", "{\"status\":\"received\"}");
                    } else {
                        sendResponse(exchange, 400, "application/json", "{\"error\":\"Bad request: Missing or invalid device info\"}");
                    }
                } catch (JsonSyntaxException e) {
                    System.err.println("HTTP Server: Invalid JSON on /register: " + e.getMessage());
                    sendResponse(exchange, 400, "application/json", "{\"error\":\"Invalid JSON format\"}");
                }
            } else {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            }
        }
    }


    // --- Handler for incoming file transfer requests ---
    class SendRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
                     BufferedReader br = new BufferedReader(isr)) {
                    String requestBody = br.lines().collect(Collectors.joining("\n"));
                    FileTransferRequest transferRequest = gson.fromJson(requestBody, FileTransferRequest.class);

                    if (transferRequest == null || transferRequest.getInfo() == null || transferRequest.getFiles() == null || transferRequest.getFiles().isEmpty()) {
                        sendResponse(exchange, 400, "application/json", "{\"error\":\"Bad request: Missing info or files\"}");
                        return;
                    }

                    System.out.println("HTTP Server: Received send-request from: " + transferRequest.getInfo().getAlias());
                    for (FileMetadata fileMeta : transferRequest.getFiles().values()) {
                        System.out.println("  File: " + fileMeta.getFileName() + " (" + fileMeta.getSize() + " bytes)");
                    }

                    Platform.runLater(() -> {
                        Optional<ButtonType> result = mainApp.showReceiveConfirmationDialog(transferRequest);
                        try {
                            if (result.isPresent() && result.get() == ButtonType.OK) {
                                String sessionId = UUID.randomUUID().toString(); // Generate a session ID for this transfer
                                mainApp.addPendingTransfer(sessionId, transferRequest);
                                sendResponse(exchange, 200, "application/json", "{\"status\":\"accepted\", \"sessionId\":\"" + sessionId + "\"}");
                            } else {
                                sendResponse(exchange, 403, "application/json", "{\"status\":\"declined\"}"); // 403 Forbidden or a custom status
                            }
                        } catch (IOException e) {
                            System.err.println("HTTP Server: Error sending response from UI thread for send-request: " + e.getMessage());
                        }
                    });

                } catch (JsonSyntaxException e) {
                    System.err.println("HTTP Server: Invalid JSON on /send-request: " + e.getMessage());
                    sendResponse(exchange, 400, "application/json", "{\"error\":\"Invalid JSON format\"}");
                }
            } else {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            }
        }
    }

    // --- Handler for receiving actual file data ---
    class SendFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                // Extract file identifiers (e.g., from headers or query parameters)
                // The LocalSend protocol needs to specify how the server identifies which file this is,
                // especially if multiple files can be queued from a single /send-request.
                // Using headers as an example:
                String sessionId = exchange.getRequestHeaders().getFirst("X-Session-ID");
                String fileId = exchange.getRequestHeaders().getFirst("X-File-ID");

                if (sessionId == null || fileId == null) {
                    sendResponse(exchange, 400, "text/plain", "Missing X-Session-ID or X-File-ID header");
                    return;
                }

                FileTransferRequest pendingRequest = mainApp.getPendingTransfer(sessionId);
                if (pendingRequest == null) {
                    sendResponse(exchange, 404, "text/plain", "Session ID not found or transfer not accepted");
                    return;
                }

                FileMetadata fileMeta = pendingRequest.getFiles().get(fileId);
                if (fileMeta == null) {
                    sendResponse(exchange, 404, "text/plain", "File ID not found in the accepted session");
                    return;
                }

                String safeFileName = Paths.get(fileMeta.getFileName()).getFileName().toString(); // Sanitize
                File downloadsDir = new File("downloads_localsend");
                if (!downloadsDir.exists()) {
                    if (!downloadsDir.mkdirs()) {
                        System.err.println("HTTP Server: Could not create downloads directory: " + downloadsDir.getAbsolutePath());
                        sendResponse(exchange, 500, "application/json", "{\"error\":\"Server error creating download directory\"}");
                        return;
                    }
                }
                File receivedFile = new File(downloadsDir, safeFileName);

                System.out.println("HTTP Server: Receiving file: " + safeFileName + " for session " + sessionId);
                long bytesWritten = 0;
                try (InputStream is = exchange.getRequestBody()) {
                    // Using Files.copy for potentially cleaner handling and progress (though basic stream copy is fine too)
                    bytesWritten = Files.copy(is, receivedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                    if (bytesWritten == fileMeta.getSize()) {
                        System.out.println("HTTP Server: File '" + safeFileName + "' received successfully. Size: " + bytesWritten);
                        sendResponse(exchange, 200, "application/json", "{\"status\":\"file_received_ok\"}");
                    } else {
                        System.err.println("HTTP Server: File '" + safeFileName + "' received with size mismatch. Expected: " + fileMeta.getSize() + ", Got: " + bytesWritten);
                        // Potentially delete partial file if size mismatch is critical
                        sendResponse(exchange, 500, "application/json", "{\"error\":\"File size mismatch\"}");
                    }
                } catch (IOException e) {
                    System.err.println("HTTP Server: Error receiving file '" + safeFileName + "': " + e.getMessage());
                    e.printStackTrace();
                    if (receivedFile.exists()) {
                        receivedFile.delete(); // Attempt to clean up partial file
                    }
                    sendResponse(exchange, 500, "application/json", "{\"error\":\"Failed to save file on server\"}");
                } finally {
                    // Clean up pending transfer for this specific file or session if fully handled
                    // mainApp.removePendingFileFromTransfer(sessionId, fileId);
                }
            } else {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            }
        }
    }
}