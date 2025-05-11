package com.example.filesharefx.services;
//LAN DROP application Code
import com.example.filesharefx.FileServerApp; // Assuming your main app class
import com.example.filesharefx.model.DeviceInfo;
import com.example.filesharefx.model.FileMetadata;
import com.example.filesharefx.model.FileTransferRequest;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.scene.control.Alert;
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
    private final int port;
    private final Gson gson;
    private final DeviceInfo ownDeviceInfo;
    private final FileServerApp mainApp; // To interact with UI (e.g., show confirmation)

    // CONSULT LOCAL SEND PROTOCOL FOR THESE API PATHS
    private static final String API_BASE_PATH = "/api/landrop/v1"; // VERIFY THIS
    private static final String INFO_PATH = API_BASE_PATH + "/info";
    public static final String SEND_REQUEST_PATH = API_BASE_PATH + "/send-request"; // Or /request-send
    public static final String SEND_FILE_PATH = API_BASE_PATH + "/send";           // Or /upload

    public FileShareHttpServer(DeviceInfo ownDeviceInfo, FileServerApp mainApp, int desiredPort) {
        this.ownDeviceInfo = ownDeviceInfo;
        this.mainApp = mainApp;
        this.port = desiredPort; // Or find an available port
        this.gson = new Gson();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool()); // Handle requests concurrently

        // --- Define handlers based on LocalSend protocol ---
        server.createContext(INFO_PATH, new InfoHandler());
        server.createContext(SEND_REQUEST_PATH, new SendRequestHandler());
        server.createContext(SEND_FILE_PATH, new SendFileHandler()); // This might need to be dynamic like /send/{fileId}

        server.start();
        System.out.println("HTTP Server started on port: " + server.getAddress().getPort());
        // Update ownDeviceInfo with the actual port if it was dynamically assigned (port=0)
        this.ownDeviceInfo.setPort(server.getAddress().getPort());
    }

    public void stop() {
        if (server != null) {
            System.out.println("Stopping HTTP Server...");
            server.stop(1); // Stop with a 1-second delay for existing connections
            System.out.println("HTTP Server stopped.");
        }
    }

    public int getPort() {
        return (server != null) ? server.getAddress().getPort() : port;
    }

    // --- Handler for device info ---
    class InfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                ownDeviceInfo.setServerTimestamp(System.currentTimeMillis()); // Update timestamp
                String response = gson.toJson(ownDeviceInfo);
                sendResponse(exchange, 200, "application/json", response);
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

                    System.out.println("Received send request from: " + transferRequest.getInfo().getAlias());
                    for (FileMetadata fileMeta : transferRequest.getFiles().values()) {
                        System.out.println("  File: " + fileMeta.getFileName() + " (" + fileMeta.getSize() + " bytes)");
                    }

                    // === UI Interaction for Confirmation (Example) ===
                    // This needs to be thread-safe and well-managed.
                    // For simplicity, directly calling mainApp method (not ideal for pure MVC)
                    // In a real app, use a callback or event bus.
                    Platform.runLater(() -> {
                        Optional<ButtonType> result = mainApp.showReceiveConfirmationDialog(transferRequest);
                        try {
                            if (result.isPresent() && result.get() == ButtonType.OK) {
                                // User accepted
                                // Store the transferRequest or relevant info for when /send is called.
                                // This might involve generating a session ID.
                                mainApp.addPendingTransfer(transferRequest); // Example
                                sendResponse(exchange, 200, "application/json", "{\"status\":\"accepted\", \"sessionId\":\"some-session-id\"}");
                            } else {
                                // User rejected
                                sendResponse(exchange, 403, "application/json", "{\"status\":\"declined\"}");
                            }
                        } catch (IOException e) {
                            System.err.println("Error sending response from UI thread: " + e.getMessage());
                        }
                    });


                } catch (JsonSyntaxException e) {
                    sendResponse(exchange, 400, "application/json", "{\"error\":\"Invalid JSON format\"}");
                }
            } else {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            }
        }
    }

    // --- Handler for receiving actual file data ---
    // The LocalSend protocol might specify how to identify which file is being sent
    // e.g., part of the URL path, or a header, or part of a multipart request.
    // This is a simplified example assuming one file per /send call, or identified by a header.
    class SendFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String fileNameHeader = exchange.getRequestHeaders().getFirst("X-File-Name"); // Example header
                String fileIdHeader = exchange.getRequestHeaders().getFirst("X-File-ID");     // Example header
                // You need to retrieve the corresponding FileMetadata (e.g., from a pending transfer map)

                if (fileIdHeader == null || fileNameHeader == null) {
                    sendResponse(exchange, 400, "text/plain", "Missing X-File-ID or X-File-Name header");
                    return;
                }

                // Logic to check if this fileId is part of an accepted transfer request
                // FileTransferRequest pendingRequest = mainApp.getPendingTransferByFileId(fileIdHeader);
                // if(pendingRequest == null) { sendResponse(...); return; }
                // FileMetadata fileMeta = pendingRequest.getFiles().get(fileIdHeader);
                // if(fileMeta == null) { sendResponse(...); return; }


                File downloadsDir = new File("downloads"); // Configure this path
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs();
                }
                // Sanitize fileNameHeader to prevent directory traversal
                String safeFileName = Paths.get(fileNameHeader).getFileName().toString();
                File receivedFile = new File(downloadsDir, safeFileName);

                long fileSizeHeader = -1;
                String contentLengthStr = exchange.getRequestHeaders().getFirst("Content-Length");
                if (contentLengthStr != null) {
                    try {
                        fileSizeHeader = Long.parseLong(contentLengthStr);
                    } catch (NumberFormatException e) { /* ignore */ }
                }

                System.out.println("Receiving file: " + safeFileName + " (Size from header: " + fileSizeHeader + ")");

                try (InputStream is = exchange.getRequestBody();
                     OutputStream os = new FileOutputStream(receivedFile)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytesRead = 0;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;
                        // TODO: Update UI with progress (e.g., via mainApp callback or event)
                        // System.out.println("Received " + totalBytesRead + " bytes for " + safeFileName);
                    }
                    System.out.println("File received successfully: " + receivedFile.getAbsolutePath() + " (" + totalBytesRead + " bytes)");
                    sendResponse(exchange, 200, "application/json", "{\"status\":\"file_received_ok\"}");

                } catch (IOException e) {
                    System.err.println("Error receiving file: " + e.getMessage());
                    e.printStackTrace();
                    // Attempt to delete partial file
                    if (receivedFile.exists()) {
                        receivedFile.delete();
                    }
                    sendResponse(exchange, 500, "application/json", "{\"error\":\"Failed to save file\"}");
                }

            } else {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            }
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, String responseBody) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}