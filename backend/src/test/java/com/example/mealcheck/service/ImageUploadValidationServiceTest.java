package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageUploadValidationServiceTest {

    @Test
    void capsConcurrentDecodingAndReleasesSlotsAfterCompletion() throws Exception {
        var properties = new AppProperties();
        properties.getUpload().setMaxConcurrentDecodes(1);
        properties.getUpload().setMaxPendingDecodes(0);
        var validation = service(properties);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        byte[] png = imageBytes("png", 10, 10);
        var blocked = new MockMultipartFile("image", png) {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                entered.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("test timeout");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.io.IOException(error); }
                return super.getInputStream();
            }
        };
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> validation.validateAndNormalize(blocked));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = new MockMultipartFile("image", png);
            assertThatThrownBy(() -> validation.validateAndNormalize(second)).isInstanceOf(RateLimitExceededException.class);
            release.countDown();
            assertThat(first.get(2, java.util.concurrent.TimeUnit.SECONDS).contentType()).isEqualTo("image/jpeg");
            assertThat(validation.validateAndNormalize(second).contentType()).isEqualTo("image/jpeg");
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void boundedWaitAcceptsBurstWithoutIncreasingDecodeConcurrency() throws Exception {
        var properties = new AppProperties();
        properties.getUpload().setMaxConcurrentDecodes(1);
        properties.getUpload().setMaxPendingDecodes(1);
        properties.getUpload().setDecodeWaitMillis(1500);
        var validation = service(properties);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        byte[] png = imageBytes("png", 10, 10);
        var blocked = new MockMultipartFile("image", png) {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                entered.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("test timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.io.IOException(error); }
                return super.getInputStream();
            }
        };
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> validation.validateAndNormalize(blocked));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var next = new MockMultipartFile("image", png);
            var waiting = executor.submit(() -> validation.validateAndNormalize(next));
            assertThatThrownBy(() -> waiting.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThatThrownBy(() -> validation.validateAndNormalize(next)).isInstanceOf(UploadBusyException.class);
            release.countDown();
            assertThat(first.get(2, java.util.concurrent.TimeUnit.SECONDS).contentType()).isEqualTo("image/jpeg");
            assertThat(waiting.get(2, java.util.concurrent.TimeUnit.SECONDS).contentType()).isEqualTo("image/jpeg");
            assertThat(validation.validateAndNormalize(next).contentType()).isEqualTo("image/jpeg");
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void timeoutReleasesWaitingCapacityAndInvalidImageReleasesDecodeSlot() throws Exception {
        var properties = new AppProperties();
        properties.getUpload().setMaxConcurrentDecodes(1);
        properties.getUpload().setMaxPendingDecodes(1);
        properties.getUpload().setDecodeWaitMillis(100);
        var validation = service(properties);
        var slots = (java.util.concurrent.Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(validation,"decodeSlots");
        slots.acquire();
        var next = new MockMultipartFile("image",imageBytes("png",10,10));
        try {
            for (int n=0;n<2;n++) assertThatThrownBy(() -> validation.validateAndNormalize(next)).isInstanceOf(UploadBusyException.class);
        } finally { slots.release(); }
        assertThatThrownBy(() -> validation.validateAndNormalize(new MockMultipartFile("image",new byte[]{1}))).isInstanceOf(IllegalArgumentException.class);
        assertThat(validation.validateAndNormalize(next).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void interruptedWaitRestoresInterruptFlagAndReleasesAdmission() throws Exception {
        var properties = new AppProperties();
        properties.getUpload().setMaxConcurrentDecodes(1);
        properties.getUpload().setMaxPendingDecodes(0);
        var validation = service(properties);
        var slots = (java.util.concurrent.Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(validation,"decodeSlots");
        slots.acquire();
        var restored = new java.util.concurrent.atomic.AtomicBoolean();
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var next = new MockMultipartFile("image",imageBytes("png",10,10));
        var worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            try { validation.validateAndNormalize(next); }
            catch (Throwable failure) { error.set(failure); restored.set(Thread.currentThread().isInterrupted()); }
        });
        worker.start(); worker.join(1000); slots.release();
        assertThat(error.get()).isInstanceOf(UploadBusyException.class);
        assertThat(restored).isTrue();
        assertThat(validation.validateAndNormalize(next).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void streamingHashChecksActualSizeBeforeAnyDecoding() throws Exception {
        var validation = service(new AppProperties());
        byte[] png = imageBytes("png", 10, 10);
        var upload = new MockMultipartFile("image", png);
        var expected = java.security.MessageDigest.getInstance("SHA-256");
        expected.update(png); expected.update("\ncurrent".getBytes(StandardCharsets.UTF_8));
        assertThat(validation.payloadHash(upload,"current")).isEqualTo(java.util.HexFormat.of().formatHex(expected.digest()));
        var small = new AppProperties(); small.getUpload().setMaxInputBytes(1024);
        var dishonest = new MockMultipartFile("image",new byte[2048]) { @Override public long getSize() { return 1; } };
        assertThatThrownBy(() -> service(small).payloadHash(dishonest,"current")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsContentByActualBytesAndNormalizesToMetadataFreeJpeg() throws Exception {
        byte[] png = imageBytes("png", 40, 30);
        byte[] marker = "GPS-SECRET-METADATA".getBytes(StandardCharsets.UTF_8);
        byte[] withTrailingMetadata = new byte[png.length + marker.length];
        System.arraycopy(png, 0, withTrailingMetadata, 0, png.length);
        System.arraycopy(marker, 0, withTrailingMetadata, png.length, marker.length);
        MockMultipartFile upload = new MockMultipartFile(
                "image", "misleading.txt", "text/plain", withTrailingMetadata);

        ValidatedImage result = service(new AppProperties()).validateAndNormalize(upload);

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.extension()).isEqualTo(".jpg");
        assertThat(result.width()).isEqualTo(40);
        assertThat(result.height()).isEqualTo(30);
        assertThat(new String(result.bytes(), StandardCharsets.ISO_8859_1))
                .doesNotContain("GPS-SECRET-METADATA");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(result.bytes()))).isNotNull();
    }

    @Test
    void rejectsNonImageEvenWhenFilenameAndMimeClaimJpeg() {
        MockMultipartFile upload = new MockMultipartFile(
                "image", "meal.jpg", "image/jpeg", "not an image".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service(new AppProperties()).validateAndNormalize(upload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JPEG、PNG 或 WebP");
    }

    @Test
    void rejectsDecodableButUnapprovedGif() throws Exception {
        MockMultipartFile upload = new MockMultipartFile(
                "image", "meal.gif", "image/gif", imageBytes("gif", 10, 10));

        assertThatThrownBy(() -> service(new AppProperties()).validateAndNormalize(upload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JPEG、PNG 或 WebP");
    }

    @Test
    void acceptsAndDecodesWebpBeforeNormalizing() {
        byte[] webp = Base64.getDecoder().decode(
                "UklGRiYAAABXRUJQVlA4IBoAAABQAQCdASoBAAEAAgA0JZwABAAAAP76GDwIAA==");
        MockMultipartFile upload = new MockMultipartFile(
                "image", "meal.webp", "image/webp", webp);

        ValidatedImage result = service(new AppProperties()).validateAndNormalize(upload);

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.width()).isEqualTo(1);
        assertThat(result.height()).isEqualTo(1);
    }

    @Test
    void rejectsImageDimensionsBeforeFullProcessing() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getUpload().setMaxWidth(100);
        MockMultipartFile upload = new MockMultipartFile(
                "image", "wide.png", "image/png", imageBytes("png", 101, 2));

        assertThatThrownBy(() -> service(properties).validateAndNormalize(upload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("像素尺寸过大");
    }

    @Test
    void downsizesAcceptedImagesToConfiguredEdge() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getUpload().setNormalizedMaxEdge(256);
        MockMultipartFile upload = new MockMultipartFile(
                "image", "large.png", "image/png", imageBytes("png", 512, 256));

        ValidatedImage result = service(properties).validateAndNormalize(upload);

        assertThat(result.width()).isEqualTo(256);
        assertThat(result.height()).isEqualTo(128);
    }

    private ImageUploadValidationService service(AppProperties properties) {
        return new ImageUploadValidationService(properties);
    }

    @Test
    void rejectsOversizedTrailingBytesEvenWhenDeclaredSizeIsSmall() throws Exception {
        var properties = new AppProperties();
        properties.getUpload().setMaxInputBytes(1024);
        byte[] padded = java.util.Arrays.copyOf(imageBytes("png", 10, 10), 2048);
        var upload = new MockMultipartFile("image", padded) { @Override public long getSize() { return 100; } };
        assertThatThrownBy(() -> service(properties).validateAndNormalize(upload))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("大小");
        assertThat(service(properties).validateAndNormalize(new MockMultipartFile("image", imageBytes("png", 10, 10))).width()).isEqualTo(10);
    }

    @Test
    void preservesColoursAndFlattensTransparencyOnWhite() throws Exception {
        for (int type : new int[]{BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_INT_ARGB}) {
            BufferedImage input = new BufferedImage(40, 20, type);
            var graphics = input.createGraphics();
            graphics.setColor(Color.RED); graphics.fillRect(0, 0, 20, 20);
            if (type != BufferedImage.TYPE_INT_ARGB) {
                graphics.setColor(Color.BLUE); graphics.fillRect(20, 0, 20, 20);
            }
            graphics.dispose();
            var bytes = new ByteArrayOutputStream(); ImageIO.write(input, "png", bytes);
            var result = service(new AppProperties()).validateAndNormalize(new MockMultipartFile("image", bytes.toByteArray()));
            var output = ImageIO.read(new java.io.ByteArrayInputStream(result.bytes()));
            var red = new Color(output.getRGB(5, 10));
            assertThat(red.getRed()).isGreaterThan(230);
            assertThat(red.getBlue()).isLessThan(25);
            var right = new Color(output.getRGB(35, 10));
            assertThat(right.getBlue()).isGreaterThan(230);
            if (type == BufferedImage.TYPE_INT_ARGB) {
                assertThat(right.getRed()).isGreaterThan(230);
                assertThat(right.getGreen()).isGreaterThan(230);
            } else assertThat(right.getRed()).isLessThan(25);
        }
    }

    private byte[] imageBytes(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }
}
