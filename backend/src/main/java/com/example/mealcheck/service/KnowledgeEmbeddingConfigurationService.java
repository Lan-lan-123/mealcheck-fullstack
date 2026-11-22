package com.example.mealcheck.service;

import com.example.mealcheck.security.UserPrincipal;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class KnowledgeEmbeddingConfigurationService {
    public record Update(@NotBlank @Size(max=200) String model, @NotBlank @Size(max=1000) String baseUrl,
                         @Size(max=4096) String apiKey, @Min(1) @Max(2000) int dimension,
                         boolean sendDimensions, @Min(0) long version) {
        @Override public String toString() { return "Embedding settings update [credentials redacted]"; }
    }
    public record Version(@Min(0) long version,String previewToken) {
        public Version(long version) { this(version,null); }
    }
    public record ConnectionView(String model, String baseUrl, boolean apiKeyConfigured, int dimension,
                                 boolean sendDimensions, String source, OffsetDateTime testedAt) { }
    public record View(long version, String activeMode, ConnectionView active, ConnectionView draft, boolean rebuildRequired) { }
    public record TestResult(boolean success, String model, Integer dimension, long elapsedMs, String message) { }
    public record ApplyResult(boolean rebuilding, Map<String,Object> job, View settings) { }
    private record State(long version, Long activeId, Long draftId) { }
    private final JdbcTemplate jdbc;
    private final KnowledgeEmbeddingProfileStore profiles;
    private final KnowledgeEmbeddingService embeddings;
    private final KnowledgeRebuildStore jobs;
    private final VectorCompressionService compression;
    private final AdminAuditService audit;
    private KnowledgeRebuildPreviewService previews;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPreviews(KnowledgeRebuildPreviewService value) { previews=value; }

    public KnowledgeEmbeddingConfigurationService(JdbcTemplate jdbc, KnowledgeEmbeddingProfileStore profiles,
            KnowledgeEmbeddingService embeddings, KnowledgeRebuildStore jobs, VectorCompressionService compression, AdminAuditService audit) {
        this.jdbc=jdbc; this.profiles=profiles; this.embeddings=embeddings; this.jobs=jobs; this.compression=compression; this.audit=audit;
    }
    private State state(boolean lock) {
        return jdbc.queryForObject("SELECT configuration_version,active_profile_id,draft_profile_id FROM knowledge_embedding_settings WHERE id=1"
                + (lock ? " FOR UPDATE" : ""), (rs,n) -> new State(rs.getLong(1),rs.getObject(2,Long.class),rs.getObject(3,Long.class)));
    }
    public View view() {
        var state=state(false);
        var active=state.activeId()==null ? null : profiles.find(state.activeId());
        var draft=state.draftId()==null ? null : profiles.find(state.draftId());
        var current=active==null ? embeddings.environmentProfile() : active.profile();
        boolean rebuild=draft!=null && (!"SEMANTIC".equals(embeddings.activeMode())
                || !embeddings.signatureForProfile(current).equals(embeddings.signatureForProfile(draft.profile())));
        return new View(state.version(),embeddings.activeMode(),describe(current,active==null?"ENVIRONMENT":active.source(),active==null?null:active.testedAt()),
                draft==null?null:describe(draft.profile(),draft.source(),draft.testedAt()),rebuild);
    }
    private ConnectionView describe(KnowledgeEmbeddingProfileStore.Profile profile, String source, OffsetDateTime testedAt) {
        return new ConnectionView(profile.model(),profile.baseUrl(),profile.configured(),profile.dimension(),profile.sendDimensions(),source,testedAt);
    }
    private void checkVersion(long expected, State state) {
        if (expected!=state.version()) throw new ResponseStatusException(HttpStatus.CONFLICT,"向量配置已变化，请刷新后重试。");
    }
    @Transactional
    public View save(Update request, UserPrincipal principal) {
        jdbc.execute("SELECT pg_advisory_xact_lock(7302020)");
        var state=state(true); checkVersion(request.version(),state);
        String model=request.model().trim();
        if (!model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}")) bad("模型名称包含无效字符。");
        String url=AiSettingsService.normalizeUrl(request.baseUrl());
        if (request.dimension()<1 || request.dimension()>2000) bad("向量维度须为 1～2000。");
        if (KnowledgeEmbeddingService.QWEN_FLASH.equals(model) && !java.util.List.of(256,512,768,1024).contains(request.dimension()))
            bad("该模型的向量维度须为 256、512、768 或 1024。");
        var baseline=embeddings.semanticProfile();
        var previous=state.draftId()==null ? baseline : profiles.find(state.draftId()).profile();
        String key=request.apiKey()==null ? "" : request.apiKey().trim();
        if (key.isBlank()) {
            if (!url.equals(previous.baseUrl())) bad("更换向量接口地址时，请填写对应的 API Key。");
            key=previous.apiKey();
        }
        if (key==null || key.isBlank() || key.length()>4096 || key.chars().anyMatch(Character::isWhitespace)) bad("请填写有效的 API Key。");
        // Freeze deployment defaults before introducing a draft, including inherited chat credentials.
        Long activeId=state.activeId();
        if (activeId==null && baseline.configured()) activeId=profiles.insert(baseline,"ENVIRONMENT");
        long draft=profiles.insert(new KnowledgeEmbeddingProfileStore.Profile(model,url,key,request.dimension(),request.sendDimensions()),"ADMIN");
        jdbc.update("UPDATE knowledge_embedding_settings SET active_profile_id=?,draft_profile_id=?,configuration_version=configuration_version+1,"
                + "mode=COALESCE(mode,?),signature=COALESCE(signature,?),updated_at=CURRENT_TIMESTAMP WHERE id=1",
                activeId,draft,embeddings.activeMode(),embeddings.signature()+":"+compression.signatureSuffix());
        audit.log(principal,"UPDATE_EMBEDDING_SETTINGS","EMBEDDING_SETTINGS",1L,"保存待用向量配置");
        return view();
    }
    public TestResult test(Version request, UserPrincipal principal) {
        var state=state(false); checkVersion(request.version(),state);
        if (state.draftId()==null) bad("请先保存待用向量配置。");
        var draft=profiles.find(state.draftId());
        long start=System.nanoTime();
        int dimension;
        try (var scope=AiCallContext.open(principal.getId(),Duration.ofSeconds(20))) {
            dimension=embeddings.embedWithProfile("向量连接测试：蔬菜和蛋白质。",draft.profile()).length;
        } catch (AiBudgetService.BudgetExceededException | AiBudgetService.BudgetUnavailableException failure) { throw failure;
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE knowledge_embedding_profiles SET tested_at=NULL WHERE id=?",draft.id());
            return new TestResult(false,draft.profile().model(),null,(System.nanoTime()-start)/1_000_000,
                    KnowledgeJobFailure.classify(failure).summary());
        }
        int changed=jdbc.update("UPDATE knowledge_embedding_profiles SET tested_at=CURRENT_TIMESTAMP WHERE id=? AND EXISTS "
                + "(SELECT 1 FROM knowledge_embedding_settings WHERE id=1 AND configuration_version=? AND draft_profile_id=?)",
                draft.id(),state.version(),draft.id());
        if (changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"测试期间配置已变化，请刷新后重新测试。");
        return new TestResult(true,draft.profile().model(),dimension,(System.nanoTime()-start)/1_000_000,"连接成功，返回维度与配置一致。");
    }
    @Transactional
    public ApplyResult apply(Version request, UserPrincipal principal) {
        jdbc.execute("SELECT pg_advisory_xact_lock(7302020)");
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("LOCK TABLE knowledge_chunks IN SHARE ROW EXCLUSIVE MODE");
        var state=state(true); checkVersion(request.version(),state);
        if (state.draftId()==null) bad("请先保存待用向量配置。");
        var draft=profiles.find(state.draftId());
        if (draft.testedAt()==null) bad("请先测试已保存的向量配置，测试通过后再应用。");
        String signature=embeddings.signatureForProfile(draft.profile())+":"+compression.signatureSuffix();
        if ("SEMANTIC".equals(embeddings.activeMode()) && signature.equals(embeddings.signature()+":"+compression.signatureSuffix())) {
            if (jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_jobs WHERE status IN ('PENDING','RUNNING','RETRY')",Long.class)>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"已有向量任务运行中，请等待完成后应用配置。");
            jdbc.update("UPDATE knowledge_embedding_settings SET active_profile_id=?,configuration_version=configuration_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=1",draft.id());
            audit.log(principal,"APPLY_EMBEDDING_SETTINGS","EMBEDDING_SETTINGS",1L,"应用同一向量空间的连接配置");
            return new ApplyResult(false,null,view());
        }
        long id=previews.submitProfile(draft.id(),request.previewToken(),principal==null?"unknown":principal.getUsername());
        audit.log(principal,"REBUILD_EMBEDDING_SETTINGS","KNOWLEDGE_JOB",id,"使用待用配置重建知识向量");
        return new ApplyResult(true,jobs.status(id),view());
    }
    public Map<String,Object> preview(Version request) {
        var state=state(false);checkVersion(request.version(),state);
        if(state.draftId()==null||profiles.find(state.draftId()).testedAt()==null) bad("请先保存并测试待用配置。");
        return previews.previewProfile(state.draftId(),null);
    }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
