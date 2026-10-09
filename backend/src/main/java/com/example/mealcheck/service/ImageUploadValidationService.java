package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;

@Service
public class ImageUploadValidationService {
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final AppProperties properties;
    private final java.util.concurrent.Semaphore decodeSlots;
    private final java.util.concurrent.Semaphore admitted;
    private final int capacity;

    public ImageUploadValidationService(AppProperties properties) {
        this.properties = properties;
        int decodes = Math.max(1, properties.getUpload().getMaxConcurrentDecodes());
        this.decodeSlots = new java.util.concurrent.Semaphore(decodes, true);
        this.capacity = Math.addExact(decodes, Math.max(0, properties.getUpload().getMaxPendingDecodes()));
        this.admitted = new java.util.concurrent.Semaphore(capacity);
    }

    public ValidatedImage validateAndNormalize(MultipartFile upload) {
        if (!admitted.tryAcquire()) throw new UploadBusyException(capacity);
        try {
            if (!decodeSlots.tryAcquire(Math.max(0, properties.getUpload().getDecodeWaitMillis()),
                    java.util.concurrent.TimeUnit.MILLISECONDS)) throw new UploadBusyException(capacity);
            try { return normalize(upload); }
            finally { decodeSlots.release(); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new UploadBusyException(capacity);
        } finally {
            admitted.release();
        }
    }

    /** Hash the bounded input without decoding or retaining another full byte array. */
    public String payloadHash(MultipartFile upload, String goal) {
        if (upload == null || upload.isEmpty()) throw new IllegalArgumentException("请上传饭菜图片");
        long maxBytes = Math.max(1024L, properties.getUpload().getMaxInputBytes());
        if (upload.getSize() > maxBytes) throw new IllegalArgumentException("图片大小不能超过 " + readableMegabytes(maxBytes) + " MB");
        try (InputStream stream = upload.getInputStream()) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) throw new IllegalArgumentException("图片大小超过限制");
                digest.update(buffer, 0, count);
            }
            digest.update(("\n" + goal).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException error) {
            throw new IllegalArgumentException("无法读取上传的图片", error);
        }
    }

    private ValidatedImage normalize(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) {
            throw new IllegalArgumentException("请上传饭菜图片");
        }

        AppProperties.Upload policy = properties.getUpload();
        long maxBytes = Math.max(1024L, policy.getMaxInputBytes());
        if (upload.getSize() > maxBytes) {
            throw new IllegalArgumentException("图片大小不能超过 " + readableMegabytes(maxBytes) + " MB");
        }

        BufferedImage decoded = readAndDecode(upload, maxBytes, policy);
        BufferedImage normalized = normalizeDimensions(decoded, policy.getNormalizedMaxEdge());
        byte[] encoded = encodeJpeg(normalized, policy.getJpegQuality());
        if (encoded.length > maxBytes) {
            throw new IllegalArgumentException("图片重新编码后仍然过大，请压缩后重试");
        }

        return new ValidatedImage(
                encoded,
                "image/jpeg",
                ".jpg",
                normalized.getWidth(),
                normalized.getHeight(),
                safeOriginalFilename(upload.getOriginalFilename())
        );
    }

    private BufferedImage readAndDecode(MultipartFile upload, long maxBytes, AppProperties.Upload policy) {
        // Multipart data already resides on disk. Let ImageIO seek in its cache without
        // retaining another complete compressed image (or readNBytes' temporary copies).
        try (InputStream stream = upload.getInputStream();
             InputStream bounded = new InputStream() {
                 private long count;
                 private void consumed(int n) {
                     if (n > 0 && (count += n) > maxBytes) {
                         throw new IllegalArgumentException("图片大小超过限制");
                     }
                 }
                 @Override public int read() throws IOException {
                     int value = stream.read();
                     if (value >= 0) consumed(1);
                     return value;
                 }
                 @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                     int n = stream.read(bytes, offset, (int) Math.min(length, maxBytes - count + 1));
                     consumed(n);
                     return n;
                 }
             };
             ImageInputStream imageInput = ImageIO.createImageInputStream(bounded)) {
            if (imageInput == null) throw new IllegalArgumentException("无法读取上传的图片");
            byte[] signature = new byte[12];
            int length = 0, n;
            while (length < signature.length && (n = imageInput.read(signature, length, signature.length - length)) != -1) {
                length += n;
            }
            ImageFormat format = detectSignature(java.util.Arrays.copyOf(signature, length));
            if (format == null) throw new IllegalArgumentException("仅支持 JPEG、PNG 或 WebP 图片");
            imageInput.seek(0);
            BufferedImage decoded = decodeAndValidate(imageInput, format, policy);
            // Readers may stop at the image trailer. Validate the entire upload,
            // including trailing bytes, even when MultipartFile.getSize() is wrong.
            byte[] remainder = new byte[8192];
            while (imageInput.read(remainder) != -1) { }
            return decoded;
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取上传的图片", e);
        }
    }

    private BufferedImage decodeAndValidate(ImageInputStream imageInput,
                                            ImageFormat signatureFormat,
                                            AppProperties.Upload policy) {
        try {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("图片内容损坏或编码不受支持");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                ImageFormat decodedFormat = ImageFormat.fromReaderName(reader.getFormatName());
                if (decodedFormat != signatureFormat) {
                    throw new IllegalArgumentException("图片内容与文件格式不一致");
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height, policy);
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw new IllegalArgumentException("图片内容损坏或编码不受支持");
                }
                return decoded;
            } finally {
                reader.dispose();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("图片内容损坏或编码不受支持", e);
        }
    }

    private void validateDimensions(int width, int height, AppProperties.Upload policy) {
        long pixels;
        try {
            pixels = Math.multiplyExact((long) width, (long) height);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("图片像素尺寸过大", e);
        }
        if (width <= 0 || height <= 0
                || width > Math.max(1, policy.getMaxWidth())
                || height > Math.max(1, policy.getMaxHeight())
                || pixels > Math.max(1L, policy.getMaxPixels())) {
            throw new IllegalArgumentException("图片像素尺寸过大，最大允许 "
                    + policy.getMaxWidth() + "x" + policy.getMaxHeight()
                    + " 且总像素不超过 " + policy.getMaxPixels());
        }
    }

    private BufferedImage normalizeDimensions(BufferedImage source, int configuredMaxEdge) {
        int maxEdge = Math.max(256, configuredMaxEdge);
        double scale = Math.min(1.0, Math.min((double) maxEdge / source.getWidth(),
                (double) maxEdge / source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        // Re-encoding below strips metadata regardless of whether pixels need copying.
        if (width == source.getWidth() && height == source.getHeight()
                && (source.getType() == BufferedImage.TYPE_3BYTE_BGR || source.getType() == BufferedImage.TYPE_INT_RGB)) {
            return source;
        }
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] encodeJpeg(BufferedImage image, float configuredQuality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("服务器缺少 JPEG 编码器");
        }

        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.5f, Math.min(0.95f, configuredQuality)));
            }
            writer.write(null, new IIOImage(image, null, null), param);
            imageOutput.flush();
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("服务器无法安全地重新编码图片", e);
        } finally {
            writer.dispose();
        }
    }

    private ImageFormat detectSignature(byte[] bytes) {
        if (bytes.length >= 3
                && unsigned(bytes[0]) == 0xff
                && unsigned(bytes[1]) == 0xd8
                && unsigned(bytes[2]) == 0xff) {
            return ImageFormat.JPEG;
        }
        if (startsWith(bytes, PNG_SIGNATURE)) {
            return ImageFormat.PNG;
        }
        if (bytes.length >= 12
                && ascii(bytes, 0, "RIFF")
                && ascii(bytes, 8, "WEBP")) {
            return ImageFormat.WEBP;
        }
        return null;
    }

    private boolean startsWith(byte[] input, byte[] signature) {
        if (input.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (input[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean ascii(byte[] input, int offset, String expected) {
        if (input.length < offset + expected.length()) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if (input[offset + i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }

    private long readableMegabytes(long bytes) {
        return Math.max(1L, (bytes + 1024L * 1024L - 1L) / (1024L * 1024L));
    }

    private String safeOriginalFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "upload";
        }
        String normalized = originalFilename.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private enum ImageFormat {
        JPEG, PNG, WEBP;

        static ImageFormat fromReaderName(String name) {
            String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (normalized.contains("jpeg") || normalized.equals("jpg")) return JPEG;
            if (normalized.contains("png")) return PNG;
            if (normalized.contains("webp")) return WEBP;
            return null;
        }
    }
}
