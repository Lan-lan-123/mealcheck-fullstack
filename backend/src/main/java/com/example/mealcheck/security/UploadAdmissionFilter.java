package com.example.mealcheck.security;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/** Reserve upload capacity before DispatcherServlet parses or spills multipart bodies. */
public class UploadAdmissionFilter extends OncePerRequestFilter {
    private final Semaphore slots;
    private final int capacity;
    private final long maxBytes;
    private final long requestReservation;
    private final Path directory;
    private final ObjectMapper mapper;
    private long reserved;

    public UploadAdmissionFilter(AppProperties properties, String location, long maxRequestBytes,
                                 ObjectMapper mapper) throws IOException {
        capacity = Math.addExact(Math.max(1, properties.getUpload().getMaxConcurrentDecodes()),
                Math.max(0, properties.getUpload().getMaxPendingDecodes()));
        slots = new Semaphore(capacity);
        if (maxRequestBytes <= 0) throw new IllegalArgumentException("Multipart request size must have a positive limit");
        maxBytes = Math.max(1, properties.getUpload().getTemporaryMaxBytes());
        // Leave room for parser buffers around the configured maximum request size.
        requestReservation = Math.addExact(maxRequestBytes, 64L * 1024);
        directory = Path.of(location).toAbsolutePath().normalize();
        this.mapper = mapper;
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Multipart directory cannot be a symbolic link");
        // Dedicated multipart directory only; never scan the persistent image directory.
        Instant staleBefore = Instant.now().minusSeconds(3600);
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("upload_") && name.endsWith(".tmp")
                        && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(file).toInstant().isBefore(staleBefore)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return type == null || !type.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws IOException, ServletException {
        if (!slots.tryAcquire()) { reject(response, 429, "UPLOAD_BUSY", "上传入口繁忙，请稍后重试。"); return; }
        boolean reservation = false;
        try {
            try { reservation = reserve(); }
            catch (IOException error) {
                reject(response, 503, "UPLOAD_STORAGE_UNAVAILABLE", "上传临时存储暂时不可用，请稍后重试。");
                return;
            }
            if (!reservation) { reject(response, 429, "UPLOAD_BUSY", "上传临时空间繁忙，请稍后重试。"); return; }
            chain.doFilter(request, response);
        } finally {
            if (reservation) releaseReservation();
            slots.release();
        }
    }

    private synchronized boolean reserve() throws IOException {
        long diskBytes = 0;
        try (var files = Files.list(directory)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected directory in multipart storage");
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    try { diskBytes = Math.addExact(diskBytes, Files.size(file)); }
                    catch (java.nio.file.NoSuchFileException ignored) { /* Another request finished cleanup. */ }
                    if (diskBytes > maxBytes) return false;
                }
            }
        } catch (java.io.UncheckedIOException error) {
            throw error.getCause();
        }
        // Existing active files plus reservations are counted conservatively twice.
        if (diskBytes > maxBytes - requestReservation - reserved
                || Files.getFileStore(directory).getUsableSpace() < requestReservation + 64L * 1024 * 1024) return false;
        reserved += requestReservation;
        return true;
    }

    private synchronized void releaseReservation() { reserved -= requestReservation; }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", "1");
        response.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
        response.setHeader("X-RateLimit-Remaining", "0");
        mapper.writeValue(response.getOutputStream(), new ApiErrorResponse(status,
                status == 429 ? "Too Many Requests" : "Service Unavailable", code, message, AppTime.now()));
    }
}
