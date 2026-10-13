package com.example.mealcheck.service;

import java.util.Arrays;

/**
 * An image that has been decoded, size-checked and re-encoded by the server.
 */
public final class ValidatedImage {
    private final byte[] bytes;
    private final String contentType;
    private final String extension;
    private final int width;
    private final int height;
    private final String originalFilename;

    public ValidatedImage(byte[] bytes,
                          String contentType,
                          String extension,
                          int width,
                          int height,
                          String originalFilename) {
        this.bytes = Arrays.copyOf(bytes, bytes.length);
        this.contentType = contentType;
        this.extension = extension;
        this.width = width;
        this.height = height;
        this.originalFilename = originalFilename;
    }

    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
    public int size() { return bytes.length; }
    public java.io.InputStream openStream() { return new java.io.ByteArrayInputStream(bytes); }
    public void writeTo(java.io.OutputStream output) throws java.io.IOException { output.write(bytes); }
    public String base64() { return java.util.Base64.getEncoder().encodeToString(bytes); }

    public String contentType() { return contentType; }
    public String extension() { return extension; }
    public int width() { return width; }
    public int height() { return height; }
    public String originalFilename() { return originalFilename; }
}
