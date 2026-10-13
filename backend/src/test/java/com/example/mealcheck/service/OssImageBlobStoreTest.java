package com.example.mealcheck.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class OssImageBlobStoreTest {
    @Test void privateUploadAndBoundedReadUseOnlyTheConfiguredBucket() throws Exception {
        var client=mock(OSS.class);var storage=new OssImageBlobStore(client,"mealcheck-test","mealcheck/");
        String reference=storage.put(3L,new ValidatedImage(new byte[]{1,2,3},"image/jpeg",".jpg",1,1,"meal.jpg"));
        assertThat(reference).startsWith("oss://mealcheck-test/mealcheck/user-3/");
        verify(client).putObject(eq("mealcheck-test"),anyString(),any(InputStream.class),argThat(metadata->
                metadata.getContentLength()==3 && "private".equals(metadata.getRawMetadata().get("x-oss-object-acl"))));
        var object=new OSSObject();object.setObjectContent(new ByteArrayInputStream(new byte[]{1,2,3}));
        when(client.getObject(eq("mealcheck-test"),anyString())).thenReturn(object);
        assertThatThrownBy(()->storage.read(reference,2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->storage.resource("oss://another-bucket/mealcheck/user-3/../../secret.jpg")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void listingUsesPagesAndSkipsRecentOrUnmanagedObjects() {
        var client=mock(OSS.class);var storage=new OssImageBlobStore(client,"mealcheck-test","mealcheck/");
        var page=mock(ListObjectsV2Result.class);var old=new OSSObjectSummary();
        old.setKey("mealcheck/user-3/12345678-1234-1234-1234-123456789abc.jpg");old.setLastModified(Date.from(Instant.now().minusSeconds(100)));
        var unrelated=new OSSObjectSummary();unrelated.setKey("mealcheck/unmanaged.txt");unrelated.setLastModified(old.getLastModified());
        when(page.getObjectSummaries()).thenReturn(List.of(old,unrelated));when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page);
        List<String> found=new ArrayList<>();storage.scanOlder(Instant.now().minusSeconds(50),found::addAll);
        assertThat(found).containsExactly("oss://mealcheck-test/"+old.getKey());
    }
}
