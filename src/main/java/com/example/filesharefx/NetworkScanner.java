package com.example.filesharefx;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ListView;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// You can place this in same package: com.example.filesharefx
public class NetworkScanner {
    private final ObservableList<String> reachableDevices = FXCollections.observableArrayList();
    private final ExecutorService executor = Executors.newFixedThreadPool(50);
    private final ListView<String> listView;

    public NetworkScanner(ListView<String> listView) {
        this.listView = listView;
        listView.setItems(reachableDevices);
    }

    public void scanLocalNetwork() {
        reachableDevices.clear();
        try {
            String localIP = InetAddress.getLocalHost().getHostAddress();
            String subnet = localIP.substring(0, localIP.lastIndexOf('.') + 1);

            for (int i = 1; i <= 254; i++) {
                final String host = subnet + i;
                executor.submit(() -> {
                    try {
                        InetAddress address = InetAddress.getByName(host);
                        if (address.isReachable(200)) {
                            Platform.runLater(() -> reachableDevices.add(host));
                        }
                    } catch (IOException ignored) {}
                });
            }
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }
}
