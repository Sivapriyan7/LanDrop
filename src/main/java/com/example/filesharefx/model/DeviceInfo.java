package com.example.filesharefx.model;

import java.util.Objects;

public class DeviceInfo {
    private String alias;
    private String version = "1.0-fx"; // Your app's version, adapt to protocol
    private String deviceModel;
    private String deviceType; // e.g., "desktop", "mobile" or more specific like "windows", "linux"
    private String fingerprint; // A unique ID for your app instance
    private String ip; // Will be populated from the sender or self-identified
    private int port; // The port where this device's HTTP server is listening
    private String protocol = "http"; // "http" or "https"
    private boolean download = true; // If this device can receive files
    private long serverTimestamp;

    // Constructors, getters, setters

    public DeviceInfo() {
        this.serverTimestamp = System.currentTimeMillis();
    }

    public DeviceInfo(String alias,String deviceModel,String deviceType, String fingerprint, String ip, int port) {
        this();
        this.alias = alias;
        this.deviceModel = deviceModel;
        this.deviceType = deviceType;
        this.fingerprint = fingerprint;
        this.ip = ip;
        this.port = port;
    }

    // --- Getters and Setters for all fields ---
    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getDeviceModel() { return deviceModel; }
    public void setDeviceModel(String deviceModel) { this.deviceModel = deviceModel; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public boolean isDownload() { return download; }
    public void setDownload(boolean download) { this.download = download; }
    public long getServerTimestamp() { return serverTimestamp; }
    public void setServerTimestamp(long serverTimestamp) { this.serverTimestamp = serverTimestamp; }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeviceInfo that = (DeviceInfo) o;
        // A device is unique by its fingerprint, or IP and Port if fingerprint isn't available early
        if (fingerprint != null && that.fingerprint != null) {
            return fingerprint.equals(that.fingerprint);
        }
        return port == that.port && Objects.equals(ip, that.ip);
    }

    @Override
    public int hashCode() {
        if (fingerprint != null) {
            return Objects.hash(fingerprint);
        }
        return Objects.hash(ip, port);
    }

    @Override
    public String toString() {
        return String.format("%s (%s:%d)", alias, ip, port);
    }
}