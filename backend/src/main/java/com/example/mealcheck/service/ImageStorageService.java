package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class ImageStorageService {
    private final AppProperties properties;

    public ImageStorageService(AppProperties properties) {
        this.properties = properties;
    }

    public String store(Long userId, MultipartFile image) {
        try {
            Path root = uploadRoot();
            Path userDir = root.resolve("user-" + userId).normalize();
            Files.createDirectories(userDir);

            Path target = userDir.resolve(UUID.randomUUID() + sanitizeExtension(image.getOriginalFilename()));
            Files.copy(image.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            return root.relativize(target).toString().replace('\\', '/');
        } catch (Exception e) {
            return null;
        }
    }

    public Path resolve(String storedImagePath) {
        if (storedImagePath == null || storedImagePath.isBlank()) {
            return null;
        }

        Path rawPath = Path.of(storedImagePath);
        if (rawPath.isAbsolute()) {
            return rawPath.normalize();
        }

        Path rootPath = uploadRoot().resolve(storedImagePath).normalize();
        if (Files.exists(rootPath)) {
            return rootPath;
        }

        Path workingDirectoryPath = rawPath.toAbsolutePath().normalize();
        if (Files.exists(workingDirectoryPath)) {
            return workingDirectoryPath;
        }

        return rootPath;
    }

    public void deleteSafely(String storedImagePath) {
        if (storedImagePath == null || storedImagePath.isBlank()) {
            return;
        }

        try {
            Path imagePath = resolve(storedImagePath);
            if (imagePath == null || !isUnderUploadRoot(imagePath)) {
                System.err.println("Skip deleting image outside upload directory: " + imagePath);
                return;
            }

            if (Files.exists(imagePath) && Files.isRegularFile(imagePath)) {
                Files.deleteIfExists(imagePath);
            }
        } catch (Exception e) {
            System.err.println("Failed to delete image: " + storedImagePath + ", " + e.getMessage());
        }
    }

    private Path uploadRoot() {
        return Path.of(properties.getUploadDir()).toAbsolutePath().normalize();
    }

    private boolean isUnderUploadRoot(Path imagePath) {
        return imagePath.toAbsolutePath().normalize().startsWith(uploadRoot());
    }

    private String sanitizeExtension(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return ".jpg";
        }

        String extension = originalFilename.substring(originalFilename.lastIndexOf('.'))
                .replaceAll("[^a-zA-Z0-9.]", "");
        return extension.isBlank() ? ".jpg" : extension;
    }
}
