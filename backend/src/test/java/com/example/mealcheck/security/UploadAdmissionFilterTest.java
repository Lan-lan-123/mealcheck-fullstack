package com.example.mealcheck.security;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class UploadAdmissionFilterTest {
    @TempDir Path directory;
    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/meals/analysis-requests");
        request.setContentType("multipart/form-data; boundary=test");
        return request;
    }
    UploadAdmissionFilter filter(int concurrency, long quota) throws Exception {
        var properties = new AppProperties();
        properties.getUpload().setMaxConcurrentDecodes(concurrency);
        properties.getUpload().setMaxPendingDecodes(0);
        properties.getUpload().setTemporaryMaxBytes(quota);
        return new UploadAdmissionFilter(properties,directory.toString(),12L*1024*1024,new ObjectMapper().findAndRegisterModules());
    }
    @Test void rejectsBeforeReadingMultipartWhenAllSlotsAreReservedAndReleasesOnFailure() throws Exception {
        var filter = filter(1,64L*1024*1024);
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> filter.doFilter(request(),new MockHttpServletResponse(),(req,res) -> {
            var rejected = new MockHttpServletResponse();
            filter.doFilter(request(),rejected,(r,s) -> calls.incrementAndGet());
            assertThat(rejected.getStatus()).isEqualTo(429);
            assertThat(rejected.getContentAsString()).contains("UPLOAD_BUSY");
            assertThat(rejected.getHeader("Retry-After")).isEqualTo("1");
            throw new IOException("downstream failed");
        })).isInstanceOf(IOException.class);
        filter.doFilter(request(),new MockHttpServletResponse(),(req,res) -> calls.incrementAndGet());
        assertThat(calls).hasValue(1);
    }
    @Test void reservesBytesBeforeParsingAndReleasesBytesAfterCompletion() throws Exception {
        var filter = filter(2,20L*1024*1024);
        var calls = new AtomicInteger();
        filter.doFilter(request(),new MockHttpServletResponse(),(req,res) -> {
            var rejected = new MockHttpServletResponse();
            filter.doFilter(request(),rejected,(r,s) -> calls.incrementAndGet());
            assertThat(rejected.getStatus()).isEqualTo(429);
        });
        filter.doFilter(request(),new MockHttpServletResponse(),(req,res) -> calls.incrementAndGet());
        assertThat(calls).hasValue(1);
    }
    @Test void accountsForExistingFilesAndOnlyCleansOldParserFilesAtStartup() throws Exception {
        Path old = Files.write(directory.resolve("upload_old.tmp"),new byte[1]);
        Files.setLastModifiedTime(old,FileTime.from(Instant.now().minusSeconds(7200)));
        Path fresh = Files.write(directory.resolve("upload_fresh.tmp"),new byte[1]);
        Path keep = Files.write(directory.resolve("keep.jpg"),new byte[9*1024*1024]);
        var filter = filter(2,20L*1024*1024);
        assertThat(old).doesNotExist(); assertThat(fresh).exists(); assertThat(keep).exists();
        var rejected = new MockHttpServletResponse();
        filter.doFilter(request(),rejected,(r,s) -> fail("Storage budget must reject before body parsing"));
        assertThat(rejected.getStatus()).isEqualTo(429);
    }
    @Test void normalQueriesDoNotConsumeUploadCapacity() throws Exception {
        var filter = filter(1,1);
        var calls = new AtomicInteger();
        filter.doFilter(new MockHttpServletRequest("GET","/api/meals"),new MockHttpServletResponse(),(r,s) -> calls.incrementAndGet());
        assertThat(calls).hasValue(1);
    }
}
