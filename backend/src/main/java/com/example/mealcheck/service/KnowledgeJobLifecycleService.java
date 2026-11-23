package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeJobLifecycleService {
    private final KnowledgeRebuildStore store;
    private final KnowledgeEmbeddingService embeddings;
    private final VectorCompressionService compression;
    private final AppProperties properties;
    private KnowledgeRebuildPreviewService previews;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPreviews(KnowledgeRebuildPreviewService value) { this.previews=value; }
    public KnowledgeJobLifecycleService(KnowledgeRebuildStore store,KnowledgeEmbeddingService embeddings,
                                        VectorCompressionService compression,AppProperties properties) {
        this.store=store;this.embeddings=embeddings;this.compression=compression;this.properties=properties;
    }
    public String signature(String mode) { return embeddings.signatureForMode(mode)+":"+compression.signatureSuffix(); }
    public void reconcile() {
        store.invalidateIncompatible(signature("LOCAL"),embeddings.isSemanticAvailable()?signature("SEMANTIC"):null,signature(embeddings.activeMode()));
    }
    public void retry(long id) {
        reconcile();
        var row=store.status(id);
        if("PREVIEW_STALE".equals(row.get("last_error")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该任务的预览已失效，请重新预览并提交。");
        if(row.get("profile_id")!=null) {
            store.retry(id,(String)row.get("signature"));
            return;
        }
        String mode=(String)row.get("embedding_mode");
        if(mode==null) mode=embeddings.activeMode();
        if("SEMANTIC".equals(mode) && !embeddings.isSemanticAvailable())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"向量模型配置不可用，请先修复配置。");
        if(!signature(mode).equals(row.get("signature")) || ("SELECTED".equals(row.get("scope")) && !mode.equals(embeddings.activeMode())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"任务配置已变化，请按当前配置重新提交。");
        store.retry(id,signature(mode));
    }
    public long resubmit(long id,String requestedMode) {
        return resubmit(id, requestedMode, null);
    }
    public long resubmit(long id,String requestedMode,String splitterStrategy) {
        return resubmit(id,requestedMode,splitterStrategy,null,"unknown");
    }
    public long resubmit(long id,String requestedMode,String splitterStrategy,String previewToken,String actor) {
        if (splitterStrategy != null && !MarkdownKnowledgeChunker.SELECTABLE_STRATEGIES.contains(splitterStrategy))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择有效的知识切分方式。");
        reconcile();
        var row=store.status(id);
        if(!java.util.List.of("FAILED","CANCELLED","INVALIDATED").contains(row.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"请先终止当前任务，或等待任务结束。");
        String mode=requestedMode==null?embeddings.activeMode():requestedMode;
        if(!java.util.List.of("LOCAL","SEMANTIC").contains(mode))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未知向量生成方式。");
        if ("SELECTED".equals(row.get("scope")) && splitterStrategy != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"选中知识更新不会重新切分。");
        if("SEMANTIC".equals(mode) && !embeddings.isSemanticAvailable())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"向量模型配置不可用，请先修复配置。");
        int dimension=embeddings.dimensionForMode(mode);
        if("SEMANTIC".equals(mode) && KnowledgeEmbeddingService.QWEN_FLASH.equals(embeddings.semanticModel())
                && !java.util.List.of(256,512,768,1024).contains(dimension))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"该模型的向量维度须为 256、512、768 或 1024。");
        if("SELECTED".equals(row.get("scope"))) {
            if(!mode.equals(embeddings.activeMode()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"局部任务须使用当前生效方式，切换方式请整库重建。");
        }
        if (previews==null || previewToken==null || previewToken.isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"请先重新预览知识库变更，再提交重建；使用待用模型请从向量模型配置处预览并应用。");
        // Resubmission must have the same content/configuration guard as a new build.
        // A saved draft never silently replaces the currently selected provider.
        return previews.submit(mode,"SELECTED".equals(row.get("scope"))?store.targets(id):null,
                splitterStrategy,previewToken,actor);
    }
}
