package com.example.filesharefx;

import com.example.filesharefx.model.DeviceInfo;
import com.example.filesharefx.model.FileMetadata;
import com.example.filesharefx.model.FileTransferRequest;
import com.example.filesharefx.services.FileShareHttpServer;
import com.example.filesharefx.services.UdpDiscoveryService;
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
import java.net.URL;
// Import java.net.http.HttpClient etc. for sending files
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID; // For fingerprint and file IDs
import com.google.gson.Gson; // For HTTP client requests

public class FileServerApp extends Application {

    private ObservableList<DeviceInfo> discoveredDevicesUiList;
    private ListView<DeviceInfo> deviceListView;
    private DeviceInfo ownDeviceInfo;
    private UdpDiscoveryService udpDiscoveryService;
    private FileShareHttpServer fileShareHttpServer;
    private HttpClient httpClient; // For sending files
    private Gson gson; // For serializing client requests

    private Map<String, FileTransferRequest> pendingTransfers = new HashMap<>(); // SessionID or some key -> request


    // HTTP Server Port - CHECK LOCAL SEND PROTOCOL - or choose dynamically
    private static final int DEFAULT_HTTP_PORT = 53318; // Example, make configurable or find available

    @Override
    public void start(Stage primaryStage) {
        gson = new Gson();
        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // --- Initialize Own Device Info ---
        String uniqueFingerprint = UUID.randomUUID().toString();
        // Try to get a non-loopback IP, port will be set by HTTP server
        // Alias can be from settings or user input later
        ownDeviceInfo = new DeviceInfo("My JavaFX Sender", "Samsung S24","mobile",uniqueFingerprint, null, DEFAULT_HTTP_PORT);
        // ownDeviceInfo.setIp() will be called by UdpDiscoveryService and port by HttpServer

        // --- Setup Services ---
        discoveredDevicesUiList = FXCollections.observableArrayList();
        udpDiscoveryService = new UdpDiscoveryService(discoveredDevicesUiList, ownDeviceInfo);
        fileShareHttpServer = new FileShareHttpServer(ownDeviceInfo, this, DEFAULT_HTTP_PORT); // Pass `this` for UI interaction

        try {
            fileShareHttpServer.start();
            // Update ownDeviceInfo with the actual port the server is listening on
            ownDeviceInfo.setPort(fileShareHttpServer.getPort());
            System.out.println("Own HTTP server is on port: " + ownDeviceInfo.getPort());
            udpDiscoveryService.start();
        } catch (IOException e) {
            System.err.println("FATAL: Could not start services: " + e.getMessage());
            e.printStackTrace();
            // Show error dialog to user
            showAlert("Service Error", "Could not start network services: " + e.getMessage(), Alert.AlertType.ERROR);
            // Optionally, Platform.exit();
            return;
        }


        // Sidebar
        VBox sidebar = new VBox(20);
        sidebar.setPadding(new Insets(30));
        sidebar.setStyle("-fx-background-color: #1e1e1e;"); // Dark gray
        sidebar.setPrefWidth(180); // Slightly wider for device names

        Button receiveBtn = new Button("Receive (Auto)"); // Receive is now handled by HTTP server
        Button sendBtn = new Button("Send File...");
        Button settingsBtn = new Button("Settings"); // Placeholder

        for (Button btn : new Button[]{receiveBtn, sendBtn, settingsBtn}) {
            btn.getStyleClass().add("sidebar-button");
            btn.setMaxWidth(Double.MAX_VALUE);
        }
        sendBtn.setOnAction(e -> handleSendFileAction(primaryStage));

        sidebar.getChildren().addAll(receiveBtn, sendBtn, settingsBtn);

        // --- Center content ---
        VBox centerBox = new VBox(15);
        centerBox.setPadding(new Insets(20));
        centerBox.setAlignment(Pos.TOP_CENTER);

        Circle circle = new Circle(50);
        circle.setFill(Color.web("#25c2a0")); // Teal accent

        Label deviceNameLabel = new Label(ownDeviceInfo.getAlias());
        deviceNameLabel.getStyleClass().add("device-name");

        Label deviceCodeLabel = new Label("ID: " + ownDeviceInfo.getFingerprint().substring(0, 6)); // Short fingerprint
        deviceCodeLabel.getStyleClass().add("device-code");


        Label devicesListLabel = new Label("Available Devices:");
        devicesListLabel.setTextFill(Color.WHITE);

        deviceListView = new ListView<>(discoveredDevicesUiList);
        deviceListView.setPrefHeight(200);
        // Customize cell factory to display DeviceInfo nicely if needed
        deviceListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(DeviceInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getAlias() + " (" + item.getIp() + ":" + item.getPort() + ")");
                }
            }
        });


        VBox devicesDisplayBox = new VBox(10, devicesListLabel, deviceListView);
        devicesDisplayBox.setAlignment(Pos.CENTER_LEFT);

        centerBox.getChildren().addAll(circle, deviceNameLabel, deviceCodeLabel, devicesDisplayBox);

        // Root layout
        BorderPane root = new BorderPane();
        root.setLeft(sidebar);
        root.setCenter(centerBox);
        root.setStyle("-fx-background-color: #2b2b2b;"); // Very dark gray

        Scene scene = new Scene(root, 800, 600); // Increased size

        URL cssUrl = getClass().getResource("/com/example/filesharefx/style.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        } else {
            System.err.println("WARNING: style.css not found!");
        }

        primaryStage.setTitle("FileShareFX (LocalSend Clone)");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(event -> stopServices()); // Ensure services are stopped
        primaryStage.show();
    }

    private void handleSendFileAction(Stage ownerStage) {
        DeviceInfo selectedDevice = deviceListView.getSelectionModel().getSelectedItem();
        if (selectedDevice == null) {
            showAlert("No Device Selected", "Please select a device to send the file to.", Alert.AlertType.INFORMATION);
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Send");
        File fileToSend = fileChooser.showOpenDialog(ownerStage);

        if (fileToSend != null && fileToSend.exists()) {
            System.out.println("Preparing to send: " + fileToSend.getName() + " to " + selectedDevice.getAlias());
            // This is where you'd initiate the HTTP client logic
            initiateFileSend(selectedDevice, fileToSend);
        }
    }

    private void initiateFileSend(DeviceInfo recipient, File file) {
        // 1. Send a "/send-request" to the recipient
        // This should be done in a background thread
        new Thread(() -> {
            try {
                FileMetadata metadata = new FileMetadata(
                        UUID.randomUUID().toString(), // Unique file ID for this transfer
                        file.getName(),
                        file.length(),
                        Files.probeContentType(file.toPath()) // Basic MIME type detection
                );
                Map<String, FileMetadata> filesMap = new HashMap<>();
                filesMap.put(metadata.getId(), metadata);

                FileTransferRequest requestPayload = new FileTransferRequest(this.ownDeviceInfo, filesMap);
                String requestJson = gson.toJson(requestPayload);

                // CONSULT LOCAL SEND PROTOCOL for the exact URL and port
                String targetUrl = recipient.getProtocol() + "://" + recipient.getIp() + ":" + recipient.getPort() + FileShareHttpServer.SEND_REQUEST_PATH;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .build();

                Platform.runLater(() -> showAlert("Sending", "Sending file request to " + recipient.getAlias(), Alert.AlertType.INFORMATION));

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    // Assuming response body is like {"status":"accepted", "sessionId":"..."}
                    // Parse response, if accepted, proceed to send the actual file
                    // For simplicity, assuming it's accepted and proceeding.
                    System.out.println("Send request accepted by " + recipient.getAlias());
                    Platform.runLater(() -> showAlert("Accepted", "File request accepted by " + recipient.getAlias() + ". Sending file...", Alert.AlertType.INFORMATION));
                    sendActualFile(recipient, file, metadata.getId());
                } else {
                    System.err.println("Send request failed or was rejected by " + recipient.getAlias() + ". Status: " + response.statusCode() + " Body: " + response.body());
                    Platform.runLater(() -> showAlert("Failed", "File request rejected or failed. Status: " + response.statusCode(), Alert.AlertType.ERROR));
                }

            } catch (IOException | InterruptedException e) {
                System.err.println("Error sending file request: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> showAlert("Error", "Could not send file request: " + e.getMessage(), Alert.AlertType.ERROR));
            }
        }).start();
    }

    private void sendActualFile(DeviceInfo recipient, File file, String fileId) {
        // 2. If accepted, send the actual file to "/send" (or protocol equivalent)
        // This should also be in a background thread
        new Thread(() -> {
            try {
                // CONSULT LOCAL SEND PROTOCOL for the exact URL and port for sending file data
                String targetUrl = recipient.getProtocol() + "://" + recipient.getIp() + ":" + recipient.getPort() + FileShareHttpServer.SEND_FILE_PATH;

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .header("Content-Type", "application/octet-stream") // Or appropriate MIME type
                        .header("X-File-Name", file.getName()) // Custom header, check protocol
                        .header("X-File-ID", fileId);          // Custom header, check protocol

                HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.ofFile(file.toPath())).build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    System.out.println("File sent successfully to " + recipient.getAlias());
                    Platform.runLater(() -> showAlert("Success", "File '" + file.getName() + "' sent successfully!", Alert.AlertType.INFORMATION));
                } else {
                    System.err.println("File send failed to " + recipient.getAlias() + ". Status: " + response.statusCode() + " Body: " + response.body());
                    Platform.runLater(() -> showAlert("Failed", "File send failed. Status: " + response.statusCode(), Alert.AlertType.ERROR));
                }

            } catch (IOException | InterruptedException e) {
                System.err.println("Error sending actual file: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> showAlert("Error", "Could not send file: " + e.getMessage(), Alert.AlertType.ERROR));
            }
        }).start();
    }


    // Method for HTTP Server to call for UI confirmation
    public Optional<ButtonType> showReceiveConfirmationDialog(FileTransferRequest request) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Incoming File Transfer");
        alert.setHeaderText("Receive files from " + request.getInfo().getAlias() + "?");
        StringBuilder fileDetails = new StringBuilder();
        for (FileMetadata meta : request.getFiles().values()) {
            fileDetails.append(meta.getFileName()).append(" (").append(formatFileSize(meta.getSize())).append(")\n");
        }
        alert.setContentText("Files:\n" + fileDetails.toString());
        alert.initOwner(getPrimaryStage()); // Ensure dialog is owned by main window

        return alert.showAndWait();
    }

    // Store pending transfers that were accepted by the user.
    public void addPendingTransfer(FileTransferRequest request) {
        // You might use a session ID from the client or generate one.
        // For now, storing the whole request keyed by sender fingerprint for simplicity.
        // The HTTP server's SendFileHandler would need to look up this information.
        // This is a very basic way to handle this state.
        pendingTransfers.put(request.getInfo().getFingerprint() + "_" + request.getFiles().keySet().iterator().next() , request);
        System.out.println("Added pending transfer from: " + request.getInfo().getAlias());
    }


    private Stage getPrimaryStage() {
        // A bit of a hack to get the primary stage if needed for dialog ownership.
        // Consider passing the stage reference around or using a static holder if absolutely necessary.
        if (deviceListView != null && deviceListView.getScene() != null) {
            return (Stage) deviceListView.getScene().getWindow();
        }
        return null; // Fallback
    }


    private void showAlert(String title, String content, Alert.AlertType alertType) {
        Alert alert = new Alert(alertType);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.initOwner(getPrimaryStage());
        alert.showAndWait();
    }

    private static String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return String.format("%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }


    private void stopServices() {
        System.out.println("Application shutting down. Stopping services...");
        if (udpDiscoveryService != null) {
            udpDiscoveryService.stop();
        }
        if (fileShareHttpServer != null) {
            fileShareHttpServer.stop();
        }
        // Close HttpClient if it has an explicit close/shutdown
    }

    @Override
    public void stop() throws Exception {
        stopServices();
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}