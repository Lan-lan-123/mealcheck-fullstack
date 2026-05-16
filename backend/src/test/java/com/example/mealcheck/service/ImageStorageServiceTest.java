package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ImageStorageServiceTest {

    @TempDir
    Path uploadDir;

    @Test
    void storePersistsImageAndReturnsRelativePath() throws Exception {
        ImageStorageService service = new ImageStorageService(properties(uploadDir));
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "meal.png",
                "image/png",
                "fake-image".getBytes()
        );

        String storedPath = service.store(7L, image);

        assertThat(storedPath).startsWith("user-7/");
        assertThat(Path.of(storedPath).isAbsolute()).isFalse();
        assertThat(Files.exists(uploadDir.resolve(storedPath))).isTrue();
    }

    @Test
    void resolveSupportsNewRelativePaths() throws Exception {
        ImageStorageService service = new ImageStorageService(properties(uploadDir));
        Path image = uploadDir.resolve("user-1/test.jpg");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");

        Path resolved = service.resolve("user-1/test.jpg");

        assertThat(resolved).isEqualTo(image.toAbsolutePath().normalize());
    }

    @Test
    void resolveKeepsLegacyAbsolutePathsReadable() throws Exception {
        ImageStorageService service = new ImageStorageService(properties(uploadDir));
        Path legacyImage = uploadDir.resolve("legacy.jpg");
        Files.writeString(legacyImage, "image");

        Path resolved = service.resolve(legacyImage.toString());

        assertThat(resolved).isEqualTo(legacyImage.toAbsolutePath().normalize());
    }

    private AppProperties properties(Path uploadDir) {
        AppProperties properties = new AppProperties();
        properties.setUploadDir(uploadDir.toString());
        return properties;
    }
}
