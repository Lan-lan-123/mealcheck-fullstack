package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Service
public class ImageStorageService {
    private static final Logger log = LoggerFactory.getLogger(ImageStorageService.class);

    private final AppProperties properties;
    private final LocalImageBlobStore local;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private OssImageBlobStore oss;
    @org.springframework.beans.factory.annotation.Value("${mealcheck.storage.provider:local}")
    private String provider="local";

    public ImageStorageService(AppProperties properties) {
        this.properties = properties;
        this.local = new LocalImageBlobStore(properties);
    }

    @jakarta.annotation.PostConstruct
    public void validateProvider() {
        if (!Set.of("local","oss").contains(provider) || ("oss".equals(provider) && oss==null))
            throw new IllegalArgumentException("Invalid or unavailable image storage provider");
    }
    public boolean usesObjectStorage() { return "oss".equals(provider); }
    private ImageBlobStore backend(String reference) {
        if(reference != null && reference.startsWith("oss://")) {
            if(oss==null)throw new IllegalStateException("OSS storage is not configured");
            return oss;
        }
        return local;
    }
    public String store(Long userId, ValidatedImage image) {
        return (usesObjectStorage()?oss:local).put(userId,image);
    }
    public Path resolve(String reference) {
        if(reference != null && reference.startsWith("oss://"))throw new IllegalArgumentException("Object images do not have a local path");
        return local.resolve(reference);
    }
    public org.springframework.core.io.Resource resource(String reference) { return backend(reference).resource(reference); }
    public byte[] read(String reference) { return backend(reference).read(reference,properties.getUpload().getMaxInputBytes()>Integer.MAX_VALUE-1
            ? Integer.MAX_VALUE-1 : (int)properties.getUpload().getMaxInputBytes()); }
    public String canonicalReference(String reference) { return reference.startsWith("oss://")?reference:local.canonical(reference); }

    public boolean deleteOnRollback(String storedImagePath) {
        if (!transactionSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // An unknown commit outcome may already have persisted the reference.
                // Only a confirmed rollback permits immediate deletion.
                if (status == STATUS_ROLLED_BACK) {
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

    public void deleteSafely(String reference) {
        if(reference==null || reference.isBlank())return;
        try { backend(reference).delete(reference); }
        catch(Exception error){log.warn("Image cleanup deferred type={}",error.getClass().getSimpleName());}
    }
    public int deleteOrphans(Set<String> referencedPaths, Instant cutoff) {
        Set<String> references=new HashSet<>();
        if(referencedPaths!=null)for(String reference:referencedPaths)references.add(canonicalReference(reference));
        return deleteOrphans(batch -> references,cutoff);
    }
    public int deleteOrphans(java.util.function.Function<java.util.List<String>,Set<String>> lookup,Instant cutoff) {
        var deleted=new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<java.util.List<String>> clean=batch -> {
            Set<String> referenced=new HashSet<>();
            for(String reference:lookup.apply(batch))referenced.add(canonicalReference(reference));
            for(String reference:batch)if(!referenced.contains(canonicalReference(reference))) {
                backend(reference).delete(reference);deleted.incrementAndGet();
            }
        };
        local.scanOlder(cutoff,clean);
        if(oss!=null)oss.scanOlder(cutoff,clean);
        return deleted.get();
    }

    private boolean transactionSynchronizationActive() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }

}
