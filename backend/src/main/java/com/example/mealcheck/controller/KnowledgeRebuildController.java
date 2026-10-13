package com.example.mealcheck.controller;

import com.example.mealcheck.service.KnowledgeMaintenanceService;
import com.example.mealcheck.service.KnowledgeRebuildStore;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge/rebuilds")
@PreAuthorize("hasRole('ADMIN')")
public class KnowledgeRebuildController {
    private final KnowledgeMaintenanceService maintenance;
    private final KnowledgeRebuildStore store;
    public KnowledgeRebuildController(KnowledgeMaintenanceService maintenance, KnowledgeRebuildStore store) {
        this.maintenance = maintenance; this.store = store;
    }
    @PostMapping
    public ResponseEntity<Map<String,Object>> create() {
        long id = maintenance.requestRebuild();
        return ResponseEntity.accepted().header("Location", "/api/knowledge/rebuilds/"+id).body(store.status(id));
    }
    @GetMapping("/{id}")
    public Map<String,Object> status(@PathVariable long id) { return store.status(id); }
    @PostMapping("/{id}/retry")
    public ResponseEntity<Map<String,Object>> retry(@PathVariable long id) {
        maintenance.retry(id);
        return ResponseEntity.accepted().body(store.status(id));
    }
}
