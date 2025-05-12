package com.example.filesharefx;

import com.example.filesharefx.model.DeviceInfo;
import com.example.filesharefx.model.FileMetadata;
import com.example.filesharefx.model.FileTransferRequest;
import com.example.filesharefx.services.FileShareHttpServer;
import com.example.filesharefx.services.UdpDiscoveryService;
import com.google.gson.Gson;
import com.google.gson.JsonObject; // For parsing simple JSON responses
import com.google.gson.JsonParser; // For parsing simple JSON responses

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class FileServerApp extends Application {

    private ObservableList<DeviceInfo> discoveredDevicesUiList;
    private ListView<DeviceInfo> deviceListView;
    private DeviceInfo ownDeviceInfo;
    private UdpDiscoveryService udpDiscoveryService;
    private FileShareHttpServer fileShareHttpServer;

    private HttpClient httpClient;
    private Gson gson;
    private ExecutorService backgroundExecutor;

    // Store pending transfers that this user has accepted. Key: SessionID
    private Map<String, FileTransferRequest> pendingIncomingTransfers = new HashMap<>();

    // Default HTTP Port (LocalSend protocol suggests 53317 as default for HTTP too)
    // Using 0 will make the OS pick an available port, which is good for avoiding conflicts during testing.
    // The actual port used will be announced.
    private static final int DEFAULT_HTTP_SERVER_PORT = 0; // 0 for dynamic, or 53317 for LocalSend default


    @Override
    public void start(Stage primaryStage) {
        gson = new Gson();
        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // Or HTTP_2 if server supports
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        backgroundExecutor = Executors.newFixedThreadPool(5, r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true);
            return t;
        });

        // --- Initialize Own Device Info (as per LocalSend protocol) ---
        String uniqueFingerprint = UUID.randomUUID().toString(); // Random string for HTTP fingerprint
        String deviceAlias = "JavaFX Peer"; // Make this configurable later
        String deviceModel = System.getProperty("os.name", "Desktop"); // Basic OS name as model
        String deviceType = "desktop"; // or "laptop", "server"

        // Port will be updated by FileShareHttpServer after it starts
        ownDeviceInfo = new DeviceInfo(deviceAlias, uniqueFingerprint, deviceModel, deviceType, DEFAULT_HTTP_SERVER_PORT, true);
        ownDeviceInfo.setVersion("2.0"); // Protocol version
        ownDeviceInfo.setProtocol(getOwnProtocol()); // "http" for now
        ownDeviceInfo.setDownload(isDownloadEnabled()); // Is this app ready to receive?

        // --- Setup Services ---
        discoveredDevicesUiList = FXCollections.observableArrayList();
        // Pass 'this' (FileServerApp instance) to services if they need to call back to it
        fileShareHttpServer = new FileShareHttpServer(ownDeviceInfo, this, DEFAULT_HTTP_SERVER_PORT);
        udpDiscoveryService = new UdpDiscoveryService(discoveredDevicesUiList, ownDeviceInfo, this);


        try {
            fileShareHttpServer.start(); // Starts HTTP server, ownDeviceInfo.port gets updated inside
            // ownDeviceInfo.setPort() is now called within fileShareHttpServer.start()
            System.out.println("FileServerApp: Own HTTP server is on port: " + ownDeviceInfo.getPort());
            udpDiscoveryService.start(); // Now UDP discovery can announce the correct HTTP port
        } catch (IOException e) {
            System.err.println("FATAL: Could not start network services: " + e.getMessage());
            e.printStackTrace();
            showAlert("Service Startup Error", "Could not start critical network services: " + e.getMessage() + "\nThe application will now close.", Alert.AlertType.ERROR);
            Platform.exit(); // Exit if services can't start
            return;
        }

        // --- UI Setup ---
        BorderPane root = setupUI(primaryStage);
        Scene scene = new Scene(root, 800, 600);

        URL cssUrl = getClass().getResource("/com/example/filesharefx/style.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        } else {
            System.err.println("WARNING: style.css not found!");
        }

        primaryStage.setTitle("FileShareFX (LocalSend v2.0 Clone)");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(event -> {
            System.out.println("Close request received. Shutting down...");
            stopServicesAndExecutor();
            // Platform.exit() will be called implicitly if not already.
        });
        primaryStage.show();
    }

    private BorderPane setupUI(Stage ownerStage) {
        // Sidebar
        VBox sidebar = new VBox(20);
        sidebar.setPadding(new Insets(30));
        sidebar.setStyle("-fx-background-color: #1e1e1e;");
        sidebar.setPrefWidth(180);

        Button sendBtn = new Button("Send File...");
        Button settingsBtn = new Button("Settings"); // Placeholder

        for (Button btn : new Button[]{sendBtn, settingsBtn}) {
            btn.getStyleClass().add("sidebar-button");
            btn.setMaxWidth(Double.MAX_VALUE);
        }
        sendBtn.setOnAction(e -> handleSendFileAction(ownerStage));

        sidebar.getChildren().addAll(sendBtn, settingsBtn);

        // Center content
        VBox centerBox = new VBox(15);
        centerBox.setPadding(new Insets(20));
        centerBox.setAlignment(Pos.TOP_CENTER);

        Circle circle = new Circle(50);
        circle.setFill(Color.web("#25c2a0"));

        Label deviceNameLabel = new Label(ownDeviceInfo.getAlias());
        deviceNameLabel.getStyleClass().add("device-name");

        // Display a short part of the fingerprint for identification
        Label deviceCodeLabel = new Label("ID: " + ownDeviceInfo.getFingerprint().substring(0, Math.min(8, ownDeviceInfo.getFingerprint().length())));
        deviceCodeLabel.getStyleClass().add("device-code");

        Label devicesListLabel = new Label("Available Devices:");
        devicesListLabel.setTextFill(Color.WHITE);

        deviceListView = new ListView<>(discoveredDevicesUiList);
        deviceListView.setPrefHeight(250);
        deviceListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(DeviceInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    // Display more info, e.g., alias (model) @ ip:port
                    setText(String.format("%s (%s) - %s:%d",
                            item.getAlias(),
                            item.getDeviceModel() != null ? item.getDeviceModel() : item.getDeviceType(),
                            item.getIp(),
                            item.getPort()));
                }
            }
        });

        VBox devicesDisplayBox = new VBox(10, devicesListLabel, deviceListView);
        devicesDisplayBox.setAlignment(Pos.CENTER_LEFT);
        centerBox.getChildren().addAll(circle, deviceNameLabel, deviceCodeLabel, devicesDisplayBox);

        BorderPane rootLayout = new BorderPane();
        rootLayout.setLeft(sidebar);
        rootLayout.setCenter(centerBox);
        rootLayout.setStyle("-fx-background-color: #2b2b2b;");
        return rootLayout;
    }

    private void handleSendFileAction(Stage ownerStage) {
        DeviceInfo selectedDevice = deviceListView.getSelectionModel().getSelectedItem();
        if (selectedDevice == null) {
            showAlert("No Device Selected", "Please select a target device from the list.", Alert.AlertType.INFORMATION);
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Send");
        File fileToSend = fileChooser.showOpenDialog(ownerStage);

        if (fileToSend != null && fileToSend.exists()) {
            System.out.println("User selected file: " + fileToSend.getName() + " to send to " + selectedDevice.getAlias());
            initiateFileSendProcedure(selectedDevice, fileToSend);
        }
    }

    private void initiateFileSendProcedure(DeviceInfo recipient, File file) {
        backgroundExecutor.submit(() -> {
            try {
                // 1. Create FileMetadata
                String fileId = UUID.randomUUID().toString();
                String mimeType = "application/octet-stream"; // Default
                try {
                    String detectedMimeType = Files.probeContentType(file.toPath());
                    if (detectedMimeType != null) {
                        mimeType = detectedMimeType;
                    }
                } catch (IOException e) {
                    System.err.println("Could not detect MIME type for " + file.getName() + ": " + e.getMessage());
                }
                FileMetadata metadata = new FileMetadata(fileId, file.getName(), file.length(), mimeType);
                Map<String, FileMetadata> filesMap = new HashMap<>();
                filesMap.put(fileId, metadata);

                // 2. Create FileTransferRequest payload
                // Ensure ownDeviceInfo has up-to-date IP and HTTP port
                ownDeviceInfo.setIp(udpDiscoveryService.getLocalIpAddress()); // Refresh IP
                ownDeviceInfo.setPort(fileShareHttpServer.getPort()); // Refresh Port
                ownDeviceInfo.setProtocol(getOwnProtocol());
                ownDeviceInfo.setDownload(isDownloadEnabled());

                FileTransferRequest requestPayload = new FileTransferRequest(this.ownDeviceInfo, filesMap);
                String requestJson = gson.toJson(requestPayload);

                // 3. Send the /send-request to the recipient
                String targetBaseUrl = recipient.getProtocol() + "://" + recipient.getIp() + ":" + recipient.getPort();
                String sendRequestUrl = targetBaseUrl + FileShareHttpServer.SEND_REQUEST_PATH;

                Platform.runLater(() -> updateStatus("Sending file request to " + recipient.getAlias() + "..."));

                HttpRequest sendRequestHttp = HttpRequest.newBuilder()
                        .uri(URI.create(sendRequestUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(15))
                        .build();

                HttpResponse<String> response = httpClient.send(sendRequestHttp, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
                    String status = responseJson.has("status") ? responseJson.get("status").getAsString() : "";
                    String sessionId = responseJson.has("sessionId") ? responseJson.get("sessionId").getAsString() : null;

                    if ("accepted".equalsIgnoreCase(status) && sessionId != null) {
                        Platform.runLater(() -> updateStatus("Request accepted by " + recipient.getAlias() + ". Session: " + sessionId + ". Uploading..."));
                        // 4. If accepted, send the actual file to /send
                        sendActualFile(recipient, file, fileId, sessionId, targetBaseUrl);
                    } else {
                        Platform.runLater(() -> showAlert("Request Declined/Failed", "Recipient " + recipient.getAlias() + " did not accept the transfer or responded unexpectedly. Status: " + status, Alert.AlertType.WARNING));
                    }
                } else {
                    Platform.runLater(() -> showAlert("Request Failed", "Failed to send file request to " + recipient.getAlias() + ". HTTP Status: " + response.statusCode() + "\nBody: " + response.body(), Alert.AlertType.ERROR));
                }

            } catch (IOException | InterruptedException e) {
                System.err.println("Error during file send procedure: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> showAlert("Send Error", "An error occurred while trying to send the file: " + e.getMessage(), Alert.AlertType.ERROR));
            }
        });
    }

    private void sendActualFile(DeviceInfo recipient, File file, String fileId, String sessionId, String targetBaseUrl) {
        // This method is called from the background thread in initiateFileSendProcedure
        try {
            String sendFileUrl = targetBaseUrl + FileShareHttpServer.SEND_FILE_PATH;

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(sendFileUrl))
                    .header("Content-Type", "application/octet-stream") // Standard for binary data
                    .header("X-Session-ID", sessionId) // Session ID from /send-request response
                    .header("X-File-ID", fileId)       // File ID being sent
                    // The LocalSend protocol might specify other headers e.g. for filename if not part of FileMetadata in session
                    .timeout(Duration.ofMinutes(30)); // Long timeout for potentially large files

            HttpRequest sendFileHttp = requestBuilder.POST(HttpRequest.BodyPublishers.ofFile(file.toPath())).build();

            HttpResponse<String> response = httpClient.send(sendFileHttp, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Platform.runLater(() -> showAlert("Transfer Complete", "File '" + file.getName() + "' sent successfully to " + recipient.getAlias() + "!", Alert.AlertType.INFORMATION));
            } else {
                Platform.runLater(() -> showAlert("Upload Failed", "Failed to upload file to " + recipient.getAlias() + ". HTTP Status: " + response.statusCode() + "\nBody: " + response.body(), Alert.AlertType.ERROR));
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error sending actual file data: " + e.getMessage());
            e.printStackTrace();
            Platform.runLater(() -> showAlert("Upload Error", "An error occurred during file upload: " + e.getMessage(), Alert.AlertType.ERROR));
        } finally {
            Platform.runLater(() -> updateStatus("")); // Clear status
        }
    }

    // --- UI Interaction and Helper Methods ---

    public Optional<ButtonType> showReceiveConfirmationDialog(FileTransferRequest request) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Incoming File Transfer");
        alert.setHeaderText("Receive files from " + request.getInfo().getAlias() + " (" + request.getInfo().getIp() + ")?");
        StringBuilder fileDetails = new StringBuilder("Files:\n");
        for (FileMetadata meta : request.getFiles().values()) {
            fileDetails.append("  - ").append(meta.getFileName()).append(" (").append(formatFileSize(meta.getSize())).append(")\n");
        }
        alert.setContentText(fileDetails.toString());
        alert.initOwner(getPrimaryStage());
        return alert.showAndWait();
    }

    public void addPendingTransfer(String sessionId, FileTransferRequest request) {
        pendingIncomingTransfers.put(sessionId, request);
        System.out.println("FileServerApp: Added pending incoming transfer with session ID: " + sessionId);
    }

    public FileTransferRequest getPendingTransfer(String sessionId) {
        return pendingIncomingTransfers.get(sessionId);
    }

    // You might want a way to remove pending transfers after completion or timeout
    public void removePendingTransfer(String sessionId) {
        pendingIncomingTransfers.remove(sessionId);
        System.out.println("FileServerApp: Removed pending incoming transfer with session ID: " + sessionId);
    }


    private Stage getPrimaryStage() {
        if (deviceListView != null && deviceListView.getScene() != null && deviceListView.getScene().getWindow() instanceof Stage) {
            return (Stage) deviceListView.getScene().getWindow();
        }
        // Fallback if UI not fully initialized or on wrong thread, though alert.initOwner(null) is okay
        return null;
    }

    private void showAlert(String title, String content, Alert.AlertType alertType) {
        Platform.runLater(() -> { // Ensure alert is shown on JavaFX Application Thread
            Alert alert = new Alert(alertType);
            alert.setTitle(title);
            alert.setHeaderText(null); // No header text
            alert.setContentText(content);
            alert.initOwner(getPrimaryStage()); // Make dialog modal to the main window if possible
            alert.showAndWait();
        });
    }
    private void updateStatus(String message) { // Placeholder for a status bar or label
        System.out.println("Status: " + message);
        // Example: if you have a statusLabel in your UI:
        // Platform.runLater(() -> statusLabel.setText(message));
    }


    private static String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1; // Safety for very large files
        return String.format("%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private void stopServicesAndExecutor() {
        System.out.println("FileServerApp: Initiating shutdown of services and executor...");
        if (udpDiscoveryService != null) {
            udpDiscoveryService.stop();
        }
        if (fileShareHttpServer != null) {
            fileShareHttpServer.stop();
        }
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdown();
            try {
                if (!backgroundExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    backgroundExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                backgroundExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("FileServerApp: Shutdown complete.");
    }

    @Override
    public void stop() throws Exception { // Called when JavaFX application exits
        stopServicesAndExecutor();
        super.stop();
    }

    // --- Getters for services and config, useful for other classes ---
    public HttpClient getHttpClient() { return httpClient; }
    public ExecutorService getExecutorService() { return backgroundExecutor; }
    public UdpDiscoveryService getUdpDiscoveryService() { return udpDiscoveryService; }
    public boolean isDownloadEnabled() { return ownDeviceInfo.isDownload(); /* Or from a setting */ }
    public String getOwnProtocol() { return ownDeviceInfo.getProtocol(); /* "http" or "https" */ }


    public static void main(String[] args) {
        launch(args);
    }
}