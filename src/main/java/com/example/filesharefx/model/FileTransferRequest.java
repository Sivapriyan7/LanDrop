package com.example.filesharefx.model;

import java.util.Map;

public class FileTransferRequest {
    private DeviceInfo info; // Sender's device info
    private Map<String, FileMetadata> files; // Map of fileId to FileMetadata

    // Constructors, getters, setters
    public FileTransferRequest() {
    }

    public FileTransferRequest(DeviceInfo info, Map<String, FileMetadata> files) {
        this.info = info;
        this.files = files;
    }

    public DeviceInfo getInfo() {
        return info;
    }

    public void setInfo(DeviceInfo info) {
        this.info = info;
    }

    public Map<String, FileMetadata> getFiles() {
        return files;
    }

    public void setFiles(Map<String, FileMetadata> files) {
        this.files = files;
    }
}