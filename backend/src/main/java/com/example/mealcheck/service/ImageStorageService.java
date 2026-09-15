package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ImageStorageService {
    private static final Logger log = LoggerFactory.getLogger(ImageStorageService.class);

    private final AppProperties properties;

    public ImageStorageService(AppProperties properties) {
        this.properties = properties;
    }

    public String store(Long userId, ValidatedImage image) {
        if (userId == null || image == null) {
            throw new IllegalArgumentException("用户和图片不能为空");
        }

        Path temp = null;
        try {
            Path root = uploadRoot();
            Files.createDirectories(root);
            applyDirectoryPermissions(root);

            Path userDir = root.resolve("user-" + userId).normalize();
            ensureUnderRoot(userDir);
            Files.createDirectories(userDir);
            applyDirectoryPermissions(userDir);

            temp = Files.createTempFile(userDir, ".upload-", ".tmp");
            Files.write(temp, image.bytes());
            Path target = userDir.resolve(UUID.randomUUID() + image.extension()).normalize();
            ensureUnderRoot(target);
            moveAtomically(temp, target);
            applyFilePermissions(target);
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            deletePathQuietly(temp);
            throw new IllegalStateException("图片存储失败，请稍后重试", e);
        } catch (RuntimeException e) {
            deletePathQuietly(temp);
            throw e;
        }
    }

    public Path resolve(String storedImagePath) {
        if (storedImagePath == null || storedImagePath.isBlank()) {
            return null;
        }

        Path root = uploadRoot();
        Path rawPath = Path.of(storedImagePath);
        Path resolved = rawPath.isAbsolute() ? rawPath.normalize() : root.resolve(rawPath).normalize();
        ensureUnderRoot(resolved);
        return resolved;
    }

    public boolean deleteOnRollback(String storedImagePath) {
        if (!transactionSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteSafely(storedImagePath);
                }
            }
        });
        return true;
    }

    public void deleteAfterCommit(String storedImagePath) {
        if (storedImagePath == null || storedImagePath.isBlank()) {
            return;
        }
        if (!transactionSynchronizationActive()) {
            deleteSafely(storedImagePath);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteSafely(storedImagePath);
            }
        });
    }

    public void deleteSafely(String storedImagePath) {
        if (storedImagePath == null || storedImagePath.isBlank()) {
            return;
        }

        try {
            Path imagePath = resolve(storedImagePath);
            if (imagePath != null && Files.isRegularFile(imagePath)) {
                Files.deleteIfExists(imagePath);
            }
        } catch (Exception e) {
            log.warn("Failed to delete stored image path={}", storedImagePath, e);
        }
    }

    public int deleteOrphans(Set<String> referencedPaths, Instant olderThan) {
        Path root = uploadRoot();
        if (!Files.isDirectory(root)) {
            return 0;
        }

        Set<Path> referenced = new HashSet<>();
        if (referencedPaths != null) {
            for (String path : referencedPaths) {
                try {
                    Path resolved = resolve(path);
                    if (resolved != null) referenced.add(resolved);
                } catch (IllegalArgumentException e) {
                    log.warn("Ignoring invalid stored image path during orphan scan: {}", path);
                }
            }
        }

        int deleted = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!referenced.contains(normalized)
                        && Files.getLastModifiedTime(normalized).toInstant().isBefore(olderThan)) {
                    Files.deleteIfExists(normalized);
                    deleted++;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描孤儿图片失败", e);
        }
        return deleted;
    }

    private boolean transactionSynchronizationActive() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private Path uploadRoot() {
        return Path.of(properties.getUploadDir()).toAbsolutePath().normalize();
    }

    private void ensureUnderRoot(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(uploadRoot())) {
            throw new IllegalArgumentException("非法的图片存储路径");
        }
    }

    private void applyDirectoryPermissions(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // POSIX permissions are unavailable on Windows and some mounted volumes.
        }
    }

    private void applyFilePermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // POSIX permissions are unavailable on Windows and some mounted volumes.
        }
    }

    private void deletePathQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to remove temporary image path={}", path, e);
        }
    }
}
