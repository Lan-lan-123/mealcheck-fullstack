package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

public final class LocalImageBlobStore implements ImageBlobStore {
    private final AppProperties properties;
    public LocalImageBlobStore(AppProperties properties) { this.properties=properties; }
    private Path root() { return Path.of(properties.getUploadDir()).toAbsolutePath().normalize(); }
    public String canonical(String reference) { return root().relativize(resolve(reference)).toString().replace('\\','/'); }
    public Path resolve(String reference) {
        if(reference==null || reference.isBlank()) return null;
        Path raw=Path.of(reference),resolved=raw.isAbsolute()?raw.normalize():root().resolve(raw).normalize();
        if(!resolved.toAbsolutePath().normalize().startsWith(root())) throw new IllegalArgumentException("非法的图片存储路径");
        return resolved;
    }
    @Override public String put(Long userId,ValidatedImage image) {
        if(userId==null || userId<1 || image==null || !Set.of(".jpg",".png",".webp").contains(image.extension()))
            throw new IllegalArgumentException("用户和图片不能为空或格式不支持");
        Path temporary=null,target=null;
        try {
            Files.createDirectories(root());permissions(root(),"rwx------");
            Path directory=resolve("user-"+userId);Files.createDirectories(directory);permissions(directory,"rwx------");
            temporary=Files.createTempFile(directory,".upload-",".tmp");
            try(var output=Files.newOutputStream(temporary)) { image.writeTo(output); }
            target=directory.resolve(UUID.randomUUID()+image.extension());
            try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException ignored) { Files.move(temporary,target); }
            permissions(target,"rw-------");
            return root().relativize(target).toString().replace('\\','/');
        } catch(java.io.IOException error) {
            try { if(temporary!=null)Files.deleteIfExists(temporary); } catch(java.io.IOException ignored) { }
            throw new IllegalStateException("图片存储失败，请稍后重试",error);
        }
    }
    @Override public Resource resource(String reference) { return new FileSystemResource(resolve(reference)); }
    @Override public void delete(String reference) {
        try { Files.deleteIfExists(resolve(reference)); }
        catch(java.io.IOException error) { throw new IllegalStateException("图片删除失败",error); }
    }
    @Override public void scanOlder(Instant cutoff,Consumer<List<String>> batch) {
        if(!Files.isDirectory(root()))return;
        try(var paths=Files.walk(root())) {
            var iterator=paths.iterator();List<String> pending=new ArrayList<>(100);
            while(iterator.hasNext()) {
                Path path=iterator.next();
                if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                    pending.add(root().relativize(path).toString().replace('\\','/'));
                    if(pending.size()==100){batch.accept(List.copyOf(pending));pending.clear();}
                }
            }
            if(!pending.isEmpty())batch.accept(List.copyOf(pending));
        } catch(java.io.IOException error) { throw new IllegalStateException("扫描孤儿图片失败",error); }
    }
    private void permissions(Path path,String mode) {
        try { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(mode)); }
        catch(UnsupportedOperationException | java.io.IOException ignored) { }
    }
}
