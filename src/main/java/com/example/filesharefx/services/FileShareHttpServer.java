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
    /**
     * Initializes and starts the HTTP server used for LocalSend communication.
     * <p>
     * This method performs the following steps:
     * <ul>
     *     <li>Creates an HTTP server on the specified port. If the port is {@code 0}, the OS will choose an available port.</li>
     *     <li>Sets a cached thread pool executor with daemon threads to handle incoming HTTP requests.</li>
     *     <li>Registers handlers for the following endpoints as per the LocalSend protocol:
     *         <ul>
     *             <li>{@code /info} - returns device information.</li>
     *             <li>{@code /register} - handles device discovery registration via HTTP.</li>
     *             <li>{@code /send-request} - initiates a file transfer request.</li>
     *             <li>{@code /send-file} - receives the actual file data.</li>
     *         </ul>
     *     </li>
     *     <li>Starts the HTTP server and logs the active port being used.</li>
     *     <li>Updates the local device info object with the actual port in use.</li>
     * </ul>
     *
     * @throws IOException if an error occurs while creating or starting the server
     */
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

    /**
     * Stops the HTTP server gracefully.
     * <p>
     * If the server is running, this method shuts it down with a 1-second delay to allow
     * any ongoing exchanges to complete before termination.
     * </p>
     * <p>
     * Logs messages before and after the server is stopped for monitoring purposes.
     * </p>
     */
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

    /**
     * Sends an HTTP response with the specified status code, content type, and response body.
     * <p>
     * This utility method sets the appropriate headers (including UTF-8 encoding),
     * writes the response body to the output stream, and ensures proper closing of the stream.
     *
     * @param exchange     the {@link HttpExchange} object representing the HTTP request and response
     * @param statusCode   the HTTP status code to send (e.g., 200 for OK, 400 for Bad Request)
     * @param contentType  the MIME type of the response (e.g., "application/json", "text/plain")
     * @param responseBody the string content to be sent as the response body
     * @throws IOException if an I/O error occurs during writing the response
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, String responseBody) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    // --- Handler for current device info ---
    /**
     * Handles incoming HTTP GET requests to the `/info` endpoint.
     * <p>
     * This endpoint returns the current device's metadata as a JSON response.
     * It is typically used by other devices in the network to retrieve basic information
     * such as alias, IP address, protocol, download capability, and other identifiers.
     *
     * /**<br><br>handle Method:<br>
     *  * Processes HTTP GET requests and returns the current device's information.
     *  * <p>
     *  * Before responding, it ensures that dynamic fields like the IP address,
     *  * protocol, and download status are up to date.
     *  * <p>
     *  * Response codes:
     *  * <ul>
     *  *   <li>200 - Success. Returns a JSON object with device information.</li>
     *  *   <li>405 - Method Not Allowed. If the request method is not GET.</li>
     *  * </ul>
     *  *
     *  * @param exchange the HTTP exchange object representing the request and response.
     *  * @throws IOException if an error occurs while writing the response.
     *  */
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
    /**
     * Handles HTTP POST requests to the `/register` endpoint as part of the LocalSend discovery protocol.
     * <p>
     * This handler is invoked when a remote device responds to a multicast UDP announcement with an HTTP
     * registration request. The request body should contain the device's {@link DeviceInfo} JSON payload.
     * </p>
     *
     * <p>Functionality:</p>
     * <ul>
     *   <li>Accepts only HTTP POST requests. Returns 405 for other methods.</li>
     *   <li>Parses the incoming JSON body into a {@code DeviceInfo} object.</li>
     *   <li>Uses the IP from the request if not explicitly provided in the {@code DeviceInfo} payload.</li>
     *   <li>Updates or adds the remote device info in the active discovery map via {@code addOrUpdateDiscoveredDeviceViaHttp}.</li>
     *   <li>Returns HTTP 200 with a JSON status response if valid; otherwise responds with appropriate error codes (400 for bad data, 405 for wrong method).</li>
     * </ul>
     *
     * <p>This endpoint is critical for peer discovery and confirmation over HTTP after the initial UDP-based handshake.</p>
     */
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
    /**
     * Handles incoming HTTP POST requests to the `/send-request` endpoint.
     * <p>
     * This handler receives file transfer requests from other devices, parses the
     * request JSON into a {@link FileTransferRequest}, displays a confirmation dialog
     * to the user, and responds with either an acceptance (including a generated session ID)
     * or a rejection.
     *
     * Expected content type: application/json
     * Expected format:
     * {
     *     "info": {...},
     *     "files": {
     *         "fileId1": { "fileName": "...", "size": ... },
     *         ...
     *     }
     * }
     */
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
    /**
     * Handles incoming HTTP POST requests to the `/send-file` endpoint.
     * <p>
     * This endpoint is responsible for receiving actual file data from the sender,
     * typically after a file transfer session has been accepted via `/send-request`.
     * <p>
     * The handler validates headers like {@code X-Session-ID} and {@code X-File-ID},
     * ensures the file metadata exists for the session, and writes the file content
     * to the local disk in a designated downloads directory.
     **<br><br>handle Method:<br>
     * Processes HTTP POST requests to receive and store a file from a remote device.
     * <p>
     * Expects two HTTP headers:
     * <ul>
     *   <li>{@code X-Session-ID} - Identifier of the file transfer session.</li>
     *   <li>{@code X-File-ID} - Identifier of the specific file within that session.</li>
     * </ul>
     * The file content is read from the request body and saved to the {@code downloads_localsend} directory.
     * <p>
     * Response codes:
     * <ul>
     *   <li>200 - File received successfully and size matches</li>
     *   <li>400 - Missing headers</li>
     *   <li>404 - Unknown session or file ID</li>
     *   <li>500 - Internal error (e.g., file system issue, size mismatch)</li>
     *   <li>405 - If method is not POST</li>
     * </ul>
     *
     * @param exchange the HTTP exchange object containing the request and response.
     * @throws IOException if an I/O error occurs while handling the request.
     */

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