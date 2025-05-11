package com.example.filesharefx.model;

public class FileMetadata {
    private String id; // Unique ID for this file in the transfer
    private String fileName;
    private long size;
    private String fileType; // MIME type
    // Add other fields if the protocol requires (e.g., encryption IV, checksum)

    // Constructors, getters, setters
    public FileMetadata() {}
    public FileMetadata(String id, String fileName, long size, String fileType) {
        this.id = id;
        this.fileName = fileName;
        this.size = size;
        this.fileType = fileType;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
}