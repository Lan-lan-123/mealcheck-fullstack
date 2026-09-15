package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageStorageServiceTest {
    @TempDir
    Path uploadDir;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void storePersistsNormalizedImageAtomicallyAndReturnsRelativePath() {
        ImageStorageService service = new ImageStorageService(properties(uploadDir));

        String storedPath = service.store(7L, image());

        assertThat(storedPath).startsWith("user-7/").endsWith(".jpg");
        assertThat(Path.of(storedPath).isAbsolute()).isFalse();
        assertThat(Files.exists(uploadDir.resolve(storedPath))).isTrue();
        assertThat(uploadDir.resolve("user-7").toFile().listFiles((dir, name) -> name.endsWith(".tmp")))
                .isEmpty();
    }

    @Test
    void resolveAllowsOnlyPathsInsideUploadRoot() throws Exception {
        ImageStorageService service = new ImageStorageService(properties(uploadDir));
        Path image = uploadDir.resolve("user-1/test.jpg");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");

        assertThat(service.resolve("user-1/test.jpg")).isEqualTo(image.toAbsolutePath().normalize());
        assertThat(service.resolve(image.toString())).isEqualTo(image.toAbsolutePath().normalize());
        assertThatThrownBy(() -> service.resolve("../outside.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rollbackDeletesNewlyStoredImage() {
        ImageStorageService service = new ImageStorageService(properties(uploadDir));
        String storedPath = service.store(1L, image());
        beginSynchronizedTransaction();

        assertThat(service.deleteOnRollback(storedPath)).isTrue();
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(uploadDir.resolve(storedPath)).doesNotExist();
    }

    @Test
    void deleteWaitsUntilTransactionCommit() {
        ImageStorageService service = new ImageStorageService(properties(uploadDir));
        String storedPath = service.store(1L, image());
        beginSynchronizedTransaction();

        service.deleteAfterCommit(storedPath);
        assertThat(uploadDir.resolve(storedPath)).exists();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(uploadDir.resolve(storedPath)).doesNotExist();
    }

    @Test
    void orphanCleanupKeepsReferencedFilesAndHonorsGracePeriod() throws Exception {
        ImageStorageService service = new ImageStorageService(properties(uploadDir));
        String referenced = service.store(1L, image());
        String orphan = service.store(2L, image());
        String recent = service.store(3L, image());
        Instant old = Instant.now().minus(48, ChronoUnit.HOURS);
        Files.setLastModifiedTime(uploadDir.resolve(referenced), FileTime.from(old));
        Files.setLastModifiedTime(uploadDir.resolve(orphan), FileTime.from(old));

        int deleted = service.deleteOrphans(Set.of(referenced), Instant.now().minus(24, ChronoUnit.HOURS));

        assertThat(deleted).isEqualTo(1);
        assertThat(uploadDir.resolve(referenced)).exists();
        assertThat(uploadDir.resolve(orphan)).doesNotExist();
        assertThat(uploadDir.resolve(recent)).exists();
    }

    private void beginSynchronizedTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(status));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private ValidatedImage image() {
        return new ValidatedImage(new byte[]{1, 2, 3}, "image/jpeg", ".jpg", 1, 1, "meal.jpg");
    }

    private AppProperties properties(Path uploadDir) {
        AppProperties properties = new AppProperties();
        properties.setUploadDir(uploadDir.toString());
        return properties;
    }
}
