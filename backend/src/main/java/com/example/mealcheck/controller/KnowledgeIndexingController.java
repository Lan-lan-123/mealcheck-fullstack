package com.example.mealcheck.controller;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.service.KnowledgeEmbeddingService;
import com.example.mealcheck.service.KnowledgeRebuildStore;
import com.example.mealcheck.service.VectorCompressionService;
import com.example.mealcheck.service.KnowledgeJobLifecycleService;
import com.example.mealcheck.service.KnowledgeIndexService;
import com.example.mealcheck.service.MarkdownKnowledgeChunker;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/knowledge/indexing")
@PreAuthorize("hasRole('ADMIN')")
public class KnowledgeIndexingController {
    private final KnowledgeEmbeddingService embeddings;
    private final KnowledgeRebuildStore store;
    private final AppProperties properties;
    private final VectorCompressionService compression;
    private final KnowledgeJobLifecycleService lifecycle;
    private final KnowledgeIndexService index;
    private com.example.mealcheck.service.KnowledgeRebuildPreviewService previews;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPreviews(com.example.mealcheck.service.KnowledgeRebuildPreviewService value) { previews=value; }

    public KnowledgeIndexingController(KnowledgeEmbeddingService embeddings, KnowledgeRebuildStore store,
                                       AppProperties properties, VectorCompressionService compression,KnowledgeJobLifecycleService lifecycle,KnowledgeIndexService index) {
        this.embeddings=embeddings; this.store=store; this.properties=properties; this.compression=compression;
        this.lifecycle=lifecycle;
        this.index=index;
    }

    public record BuildRequest(String mode, List<Long> chunkIds, String splitterStrategy, String previewToken) {
        public BuildRequest(String mode, List<Long> chunkIds, String splitterStrategy) { this(mode,chunkIds,splitterStrategy,null); }
        public BuildRequest(String mode, List<Long> chunkIds) { this(mode, chunkIds, null); }
        public BuildRequest(String mode) { this(mode, null); }
    }

    @PostMapping("/preview")
    public Map<String,Object> preview(@RequestBody BuildRequest request) {
        return previews.preview(request.mode(),request.chunkIds(),request.splitterStrategy());
    }

    @GetMapping
    public Map<String,Object> status() {
        lifecycle.reconcile();
        var result = new LinkedHashMap<String,Object>();
        result.put("activeMode", embeddings.activeMode());
        result.put("semanticAvailable", embeddings.isSemanticAvailable());
        result.put("semanticModel", embeddings.semanticModel());
        result.put("dimension", embeddings.activeDimension());
        result.put("activeSplitterStrategy", index.activeSplitterStrategy());
        result.put("splitterStrategies", MarkdownKnowledgeChunker.SELECTABLE_STRATEGIES);
        result.put("chunkMinTokens", properties.getKnowledge().getChunkMinTokens());
        result.put("chunkMaxTokens", properties.getKnowledge().getChunkMaxTokens());
        result.put("chunkOverlapTokens", properties.getKnowledge().getChunkOverlapTokens());
        result.put("job", store.latestBuild());
        result.put("inventory", store.inventory(embeddings.signature() + ":" + compression.signatureSuffix()));
        return result;
    }

    @PostMapping
    public ResponseEntity<Map<String,Object>> create(@RequestBody BuildRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.example.mealcheck.security.UserPrincipal principal) {
        lifecycle.reconcile();
        if (request.mode() == null || !List.of("LOCAL", "SEMANTIC").contains(request.mode()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择本地向量或语义向量模型。");
        String splitter = request.splitterStrategy();
        if (splitter != null && !MarkdownKnowledgeChunker.SELECTABLE_STRATEGIES.contains(splitter))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效的知识切分方式。");
        if (request.chunkIds() != null && splitter != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "切分方式仅用于整库重建；选中知识更新不会重新切分。");
        if ("SEMANTIC".equals(request.mode())) {
            if (!embeddings.isSemanticAvailable())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "向量模型尚未配置。请配置向量接口与 API Key，或配置可复用的阿里云百炼聊天接口。");
            if (KnowledgeEmbeddingService.QWEN_FLASH.equals(embeddings.semanticModel())
                    && !List.of(256, 512, 768, 1024).contains(embeddings.dimensionForMode(request.mode())))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该模型的向量维度须为 256、512、768 或 1024。");
        }
        boolean selected = request.chunkIds() != null;
        if (selected && !request.mode().equals(embeddings.activeMode()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "选中知识只能使用当前生效模型生成；切换向量方式请重建整个知识库，避免混用向量空间。");
        long id = previews.submit(request.mode(),request.chunkIds(),splitter,request.previewToken(),principal==null?"unknown":principal.getUsername());
        return ResponseEntity.accepted().header("Location", "/api/knowledge/rebuilds/" + id).body(store.status(id));
    }

    @GetMapping("/jobs")
    public Map<String,Object> history(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size) {
        lifecycle.reconcile();return store.history(page,size);
    }
    @GetMapping("/jobs/{id}")
    public Map<String,Object> details(@PathVariable long id) { lifecycle.reconcile();return store.details(id); }
    @PostMapping("/jobs/{id}/cancel")
    public Map<String,Object> cancel(@PathVariable long id) { store.cancel(id);return store.status(id); }
    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<Map<String,Object>> retry(@PathVariable long id) { lifecycle.retry(id);return ResponseEntity.accepted().body(store.status(id)); }
    public record ResubmitRequest(String mode, String splitterStrategy, String previewToken) { }
    @PostMapping("/jobs/{id}/resubmit")
    public ResponseEntity<Map<String,Object>> resubmit(@PathVariable long id,@RequestBody(required=false) ResubmitRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.example.mealcheck.security.UserPrincipal principal) {
        long job=lifecycle.resubmit(id,request==null?null:request.mode(),request==null?null:request.splitterStrategy(),
                request==null?null:request.previewToken(),principal==null?"unknown":principal.getUsername());
        return ResponseEntity.accepted().body(store.status(job));
    }
}
