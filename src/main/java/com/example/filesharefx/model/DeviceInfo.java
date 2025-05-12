package com.example.filesharefx.model;

import java.util.Objects;

public class DeviceInfo {
    private String alias;
    private String version = "2.0"; // Protocol version
    private String deviceModel; // Nullable
    private String deviceType;  // Nullable (mobile | desktop | web | headless | server)
    private String fingerprint; // Unique ID for your app instance
    private String ip;          // Sender IP or self-identified
    private int port;           // Port for HTTP/S server
    private String protocol = "http"; // "http" or "https"
    private boolean download = false; // If download API is active (default: false as per protocol text)
    private boolean announce = false; // True for initial announcements, false for responses

    // Not in protocol spec for JSON, but useful internally
    private transient long lastSeenTimestamp;


    public DeviceInfo() {
        this.lastSeenTimestamp = System.currentTimeMillis();
    }

    // Constructor for own device info
    public DeviceInfo(String alias, String fingerprint, String deviceModel, String deviceType, int httpPort, boolean canDownload) {
        this();
        this.alias = alias;
        this.fingerprint = fingerprint;
        this.deviceModel = deviceModel;
        this.deviceType = deviceType;
        this.port = httpPort;
        this.download = canDownload;
        // IP and protocol will be set
    }


    // --- Getters and Setters ---
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
    public boolean isAnnounce() { return announce; }
    public void setAnnounce(boolean announce) { this.announce = announce; }
    public long getLastSeenTimestamp() { return lastSeenTimestamp; }
    public void setLastSeenTimestamp(long lastSeenTimestamp) { this.lastSeenTimestamp = lastSeenTimestamp; }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeviceInfo that = (DeviceInfo) o;
        return Objects.equals(fingerprint, that.fingerprint); // Fingerprint is the unique key
    }

    @Override
    public int hashCode() {
        return Objects.hash(fingerprint);
    }

    @Override
    public String toString() {
        // Customize as needed for UI display
        return String.format("%s (%s) @ %s:%d", alias, deviceModel != null ? deviceModel : deviceType, ip, port);
    }
}