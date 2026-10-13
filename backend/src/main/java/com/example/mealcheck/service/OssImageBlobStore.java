package com.example.mealcheck.service;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name="mealcheck.storage.provider",havingValue="oss")
public class OssImageBlobStore implements ImageBlobStore {
    private final OSS client;
    private final String bucket,prefix;
    @org.springframework.beans.factory.annotation.Autowired
    public OssImageBlobStore(Environment environment) {
        this(build(environment),required(environment,"bucket"),environment.getProperty("mealcheck.storage.oss.prefix","mealcheck/"));
    }
    OssImageBlobStore(OSS client,String bucket,String prefix) {
        this.client=client;this.bucket=bucket;
        if(!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))throw new IllegalArgumentException("Invalid OSS bucket");
        this.prefix=prefix.endsWith("/")?prefix:prefix+"/";
        if(!this.prefix.matches("[A-Za-z0-9_/-]+") || this.prefix.startsWith("/")) throw new IllegalArgumentException("Invalid OSS prefix");
    }
    private static String required(Environment environment,String key) {
        String value=environment.getProperty("mealcheck.storage.oss."+key);
        if(value==null || value.isBlank())throw new IllegalArgumentException("Missing OSS "+key);
        return value;
    }
    private static OSS build(Environment environment) {
        String endpoint=required(environment,"endpoint");
        var uri=java.net.URI.create(endpoint);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)
            throw new IllegalArgumentException("OSS endpoint must use HTTPS");
        var configuration=new ClientBuilderConfiguration();configuration.setSignatureVersion(SignVersion.V4);
        configuration.setConnectionTimeout(3000);configuration.setSocketTimeout(20000);
        configuration.setConnectionRequestTimeout(2000);configuration.setMaxConnections(16);configuration.setMaxErrorRetry(0);
        return OSSClientBuilder.create().credentialsProvider(new DefaultCredentialProvider(required(environment,"access-key-id"),required(environment,"access-key-secret")))
                .clientConfiguration(configuration).region(required(environment,"region")).endpoint(endpoint).build();
    }
    private String reference(String key) { return "oss://"+bucket+"/"+key; }
    private String key(String reference) {
        String expected="oss://"+bucket+"/"+prefix;
        if(reference==null || !reference.startsWith(expected))throw new IllegalArgumentException("Invalid OSS image reference");
        String suffix=reference.substring(expected.length());
        if(!suffix.matches("user-[1-9][0-9]*/[0-9a-f-]{36}\\.(jpg|png|webp)"))throw new IllegalArgumentException("Invalid OSS image key");
        return prefix+suffix;
    }
    @Override public String put(Long userId,ValidatedImage image) {
        String key=prefix+"user-"+userId+"/"+UUID.randomUUID()+image.extension();String reference=reference(key);key(reference);
        ObjectMetadata metadata=new ObjectMetadata();metadata.setContentType(image.contentType());metadata.setContentLength(image.size());
        metadata.setHeader("x-oss-object-acl","private");
        try(var stream=image.openStream()) { client.putObject(bucket,key,stream,metadata); }
        catch(java.io.IOException error) { throw new IllegalStateException("图片存储失败",error); }
        return reference;
    }
    @Override public Resource resource(String reference) {
        String key=key(reference);
        return new AbstractResource() {
            @Override public String getDescription(){return "Private meal image";}
            @Override public String getFilename(){return key.substring(key.lastIndexOf('/')+1);}
            @Override public boolean exists(){return client.doesObjectExist(bucket,key);}
            @Override public long contentLength(){return client.getObjectMetadata(bucket,key).getContentLength();}
            @Override public InputStream getInputStream(){return client.getObject(bucket,key).getObjectContent();}
        };
    }
    @Override public void delete(String reference){client.deleteObject(bucket,key(reference));}
    @Override public void scanOlder(Instant cutoff,Consumer<List<String>> batch) {
        String continuation=null;
        do {
            var request=new ListObjectsV2Request(bucket);request.setPrefix(prefix);request.setMaxKeys(100);request.setContinuationToken(continuation);
            var page=client.listObjectsV2(request);List<String> references=new ArrayList<>();
            for(var object:page.getObjectSummaries()) {
                String reference=reference(object.getKey());
                try { key(reference); } catch(IllegalArgumentException ignored){continue;}
                if(object.getLastModified().toInstant().isBefore(cutoff))references.add(reference);
            }
            if(!references.isEmpty())batch.accept(references);
            continuation=page.isTruncated()?page.getNextContinuationToken():null;
        } while(continuation!=null);
    }
    @jakarta.annotation.PreDestroy public void close(){client.shutdown();}
}
