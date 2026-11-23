package com.example.mealcheck.controller;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.KnowledgeContentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/knowledge/content-history")
@PreAuthorize("hasRole('ADMIN')")
public class KnowledgeContentController {
    private final KnowledgeContentService content;
    public KnowledgeContentController(KnowledgeContentService content) { this.content=content; }
    @GetMapping public Map<String,Object> history(@RequestParam(required=false) Long chunkId,
            @RequestParam(defaultValue="false") boolean deletedOnly,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="10") int size) { return content.history(chunkId,deletedOnly,page,size); }
    @GetMapping("/{id}") public Map<String,Object> version(@PathVariable long id) { return content.version(id); }
    public record RestoreRequest(@NotNull @Positive Long expectedRevision,@NotBlank @Size(max=400) String reason) {}
    @PostMapping("/{id}/restore") public Map<String,Object> restore(@PathVariable long id,@Valid @RequestBody RestoreRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        content.restore(id,request.expectedRevision(),principal==null?"unknown":principal.getUsername(),request.reason());
        return Map.of("restored",true);
    }
}
