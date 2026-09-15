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
