package com.example.mealcheck.service;

import org.springframework.core.io.Resource;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/** References are private storage identities; callers authorize access before opening a resource. */
public interface ImageBlobStore {
    String put(Long userId, ValidatedImage image);
    Resource resource(String reference);
    void delete(String reference);
    void scanOlder(Instant cutoff, Consumer<List<String>> batch);
    default byte[] read(String reference, int maxBytes) {
        try (var stream=resource(reference).getInputStream()) {
            byte[] bytes=stream.readNBytes(maxBytes+1);
            if (bytes.length>maxBytes) throw new IllegalStateException("Stored image exceeds configured limit");
            return bytes;
        } catch (java.io.IOException error) { throw new IllegalStateException("图片读取失败",error); }
    }
}
