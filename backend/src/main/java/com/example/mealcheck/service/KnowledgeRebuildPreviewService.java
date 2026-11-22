package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A preview never generates embeddings. The token binds content, source bytes and both vector spaces. */
@Service
public class KnowledgeRebuildPreviewService {
    private final JdbcTemplate jdbc;
    private final MarkdownKnowledgeChunker chunker;
    private final KnowledgeEmbeddingService embeddings;
    private final VectorCompressionService compression;
    private final KnowledgeIndexService index;
    private final KnowledgeRebuildStore store;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final KnowledgeEmbeddingProfileStore profiles;
    public KnowledgeRebuildPreviewService(JdbcTemplate jdbc,MarkdownKnowledgeChunker chunker,KnowledgeEmbeddingService embeddings,
            VectorCompressionService compression,KnowledgeIndexService index,KnowledgeRebuildStore store,AppProperties properties,ObjectMapper mapper,KnowledgeEmbeddingProfileStore profiles) {
        this.jdbc=jdbc;this.chunker=chunker;this.embeddings=embeddings;this.compression=compression;
        this.index=index;this.store=store;this.properties=properties;this.mapper=mapper;this.profiles=profiles;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(String mode,List<Long> ids,String strategy) {
        return compute(mode,ids,strategy,null);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> previewProfile(long profileId,String strategy) {
        return compute("SEMANTIC",null,strategy,profiles.find(profileId));
    }
    private Map<String,Object> compute(String mode,List<Long> ids,String strategy,KnowledgeEmbeddingProfileStore.Stored profile) {
        if(!List.of("LOCAL","SEMANTIC").contains(mode==null?"":mode)) throw bad("请选择有效的向量生成方式。");
        if(profile==null&&"SEMANTIC".equals(mode)&&!embeddings.isSemanticAvailable()) throw bad("语义向量模型尚未配置。");
        boolean selected=ids!=null;
        if(selected&&(ids.isEmpty()||ids.size()>500||ids.stream().anyMatch(id->id==null||id<=0))) throw bad("请选择 1～500 条有效知识。");
        if(selected&&strategy!=null) throw bad("选中知识不会重新切分。");
        if(selected&&!mode.equals(embeddings.activeMode())) throw conflict("选中知识只能使用当前生效的向量方式。");
        String splitter=selected?null:MarkdownKnowledgeChunker.normalizeStrategy(strategy==null?index.activeSplitterStrategy():strategy);
        List<Long> selectedIds=selected?ids.stream().distinct().sorted().toList():List.of();
        var rows=jdbc.queryForList("SELECT id,source,chunk_key,title,content,content_hash,content_revision,source_version,embedding_signature FROM knowledge_chunks ORDER BY id");
        if(selected&&rows.stream().filter(r->selectedIds.contains(((Number)r.get("id")).longValue())).count()!=selectedIds.size())
            throw conflict("所选知识已删除，请刷新列表。");
        String active=embeddings.signature()+":"+compression.signatureSuffix();
        String target=(profile==null?embeddings.signatureForMode(mode):embeddings.signatureForProfile(profile.profile()))+":"+compression.signatureSuffix();
        byte[] source;
        try(var in=new ClassPathResource("knowledge/diet_guides.md").getInputStream()) { source=in.readAllBytes(); }
        catch(java.io.IOException failure) { throw new IllegalStateException("Cannot read knowledge source",failure); }
        var chunks=selected?List.<PgVectorKnowledgeService.KnowledgeChunk>of():chunker.split(new String(source,StandardCharsets.UTF_8),splitter);
        Map<String,Map<String,Object>> byKey=new HashMap<>();
        rows.stream().filter(r->"diet_guides.md".equals(r.get("source"))).forEach(r->byKey.put((String)r.get("chunk_key"),r));
        int added=0,updated=0,removed=0,sourceCalls=0;
        long sourceUnits=0,targetUnits=0;
        List<Map<String,Object>> affected=new ArrayList<>();
        Set<String> keys=new HashSet<>();
        for(var c:chunks) {
            keys.add(c.chunkKey());
            var old=byKey.get(c.chunkKey());
            boolean changed=old==null||!Objects.equals(old.get("title"),c.title())||!Objects.equals(old.get("content"),c.content());
            if(old==null) added++; else if(changed) updated++;
            if(old==null||!Objects.equals(old.get("content_hash"),c.contentHash())||!Objects.equals(old.get("embedding_signature"),active)) {
                sourceCalls++; sourceUnits+=units(c.title(),c.content());
            }
            if(changed&&old!=null&&"manual".equals(old.get("source_version"))) affected.add(Map.of("id",old.get("id"),"title",old.get("title"),"action","覆盖"));
            targetUnits+=units(c.title(),c.content());
        }
        for(var r:rows) {
            boolean system="diet_guides.md".equals(r.get("source"));
            if(!selected&&system&&!keys.contains(r.get("chunk_key"))) {
                removed++;
                if("manual".equals(r.get("source_version"))) affected.add(Map.of("id",r.get("id"),"title",r.get("title"),"action","删除"));
            }
            if((selected&&selectedIds.contains(((Number)r.get("id")).longValue()))||(!selected&&!system))
                targetUnits+=units((String)r.get("title"),(String)r.get("content"));
        }
        long targetCount=selected?selectedIds.size():chunks.size()+rows.stream().filter(r->!"diet_guides.md".equals(r.get("source"))).count();
        var inputs=new LinkedHashMap<String,Object>();
        inputs.put("mode",mode);inputs.put("ids",selectedIds);inputs.put("selected",selected);inputs.put("splitter",splitter);
        inputs.put("profileId",profile==null?null:profile.id());
        inputs.put("sourceSha",sha(source));inputs.put("sourceVersion",properties.getKnowledge().getSourceVersion());
        inputs.put("chunkMin",properties.getKnowledge().getChunkMinTokens());inputs.put("chunkMax",properties.getKnowledge().getChunkMaxTokens());
        inputs.put("overlap",properties.getKnowledge().getChunkOverlapTokens());inputs.put("active",active);inputs.put("target",target);
        inputs.put("revision",jdbc.queryForObject("SELECT revision FROM knowledge_cache_state WHERE id=1",Long.class));
        inputs.put("chunks",chunks);
        var result=new LinkedHashMap<String,Object>();
        try { result.put("previewToken",sha(mapper.writeValueAsBytes(inputs))); }
        catch(java.io.IOException error) { throw new IllegalStateException(error); }
        result.put("mode",mode);result.put("chunkIds",selected?selectedIds:null);result.put("splitterStrategy",splitter);
        result.put("currentMode",embeddings.activeMode());result.put("currentDimension",embeddings.activeDimension());
        result.put("targetDimension",profile==null?embeddings.dimensionForMode(mode):profile.profile().dimension());
        result.put("targetModel",profile!=null?profile.profile().model():"SEMANTIC".equals(mode)?embeddings.semanticModel():"本地哈希向量");
        result.put("currentChunks",rows.size());result.put("targetChunks",targetCount);
        result.put("inserted",added);result.put("updated",updated);result.put("deleted",removed);
        result.put("manualChanges",affected.size());result.put("affectedManualChanges",affected.stream().limit(100).toList());
        result.put("sourceEmbeddingRequests","SEMANTIC".equals(embeddings.activeMode())?sourceCalls:0);
        result.put("targetEmbeddingRequests","SEMANTIC".equals(mode)?targetCount:0);
        result.put("estimatedInputUpperBound",("SEMANTIC".equals(embeddings.activeMode())?sourceUnits:0)+("SEMANTIC".equals(mode)?targetUnits:0));
        result.put("sourceRevision",jdbc.queryForObject("SELECT COALESCE((SELECT data_revision FROM knowledge_source_sync WHERE source='diet_guides.md'),0)",Long.class));
        result.put("sourceSha256",sha(source));
        return result;
    }
    private long units(String title,String content) { return ((title==null?"":title)+"\n\n"+content).getBytes(StandardCharsets.UTF_8).length+128L; }
    private String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    @Transactional(timeout=15)
    public long submit(String mode,List<Long> ids,String strategy,String token,String actor) {
        return submitPlan(mode,ids,strategy,token,actor,null);
    }
    @Transactional(timeout=15)
    public long submitProfile(long profileId,String token,String actor) {
        return submitPlan("SEMANTIC",null,null,token,actor,profiles.find(profileId));
    }
    private long submitPlan(String mode,List<Long> ids,String strategy,String token,String actor,KnowledgeEmbeddingProfileStore.Stored profile) {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SELECT pg_advisory_xact_lock(7302020)");
        jdbc.execute("LOCK TABLE knowledge_chunks IN SHARE ROW EXCLUSIVE MODE");
        var plan=compute(mode,ids,strategy,profile);
        if(token==null||!token.equals(plan.get("previewToken"))) throw conflict("知识、源文件或配置已变化，请重新预览后提交。");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM knowledge_embedding_rebuild_jobs WHERE status IN ('PENDING','RUNNING','RETRY'))",Boolean.class)))
            throw conflict("已有向量任务运行中，请等待完成或终止后重新预览。");
        String signature=(profile==null?embeddings.signatureForMode(mode):embeddings.signatureForProfile(profile.profile()))+":"+compression.signatureSuffix();
        long id=profile!=null?store.requestProfileBuild(signature,profile.profile().dimension(),compression.storageType().sqlName(),profile.id(),(String)plan.get("splitterStrategy"))
                :ids==null?store.requestBuild(signature,embeddings.dimensionForMode(mode),compression.storageType().sqlName(),mode,(String)plan.get("splitterStrategy"))
                :store.requestSelected(signature,embeddings.dimensionForMode(mode),compression.storageType().sqlName(),mode,ids);
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET preview_token=?,source_synced=CASE WHEN scope='ALL' THEN FALSE ELSE TRUE END WHERE id=?",token,id);
        jdbc.update("INSERT INTO knowledge_embedding_job_events(job_id,code,summary) VALUES (?,'PREVIEW_CONFIRMED',?)",id,actor+" 已确认影响预览。");
        return id;
    }
    public record SourceGuard(long revision,String corpusSha256) {}
    public SourceGuard verifyBeforeSourceSync(KnowledgeRebuildStore.Work work) {
        String token=jdbc.queryForObject("SELECT preview_token FROM knowledge_embedding_rebuild_jobs WHERE id=?",String.class,work.id());
        if(token==null) return null; // Historical/automatic jobs retain their existing recovery path.
        var plan=compute(work.mode(),"SELECTED".equals(store.status(work.id()).get("scope"))?store.targets(work.id()):null,work.splitterStrategy(),work.profileId()==null?null:profiles.find(work.profileId()));
        if(!token.equals(plan.get("previewToken"))) throw new KnowledgeJobFailure.EmbeddingException(
            new KnowledgeJobFailure("PREVIEW_STALE","预览后知识或配置发生变化，请重新预览并提交。",false));
        return new SourceGuard(((Number)plan.get("sourceRevision")).longValue(),(String)plan.get("sourceSha256"));
    }
    public void verifySelected(KnowledgeRebuildStore.Work work) {
        if("SELECTED".equals(store.status(work.id()).get("scope"))) verifyBeforeSourceSync(work);
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
