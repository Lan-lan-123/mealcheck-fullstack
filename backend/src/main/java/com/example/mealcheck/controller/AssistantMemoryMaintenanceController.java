package com.example.mealcheck.controller;

import com.example.mealcheck.service.AssistantMemoryStore;
import com.example.mealcheck.service.AssistantMemoryMaintenanceService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import java.util.Map;

@RestController
@org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/assistant-memory/rebuilds")
public class AssistantMemoryMaintenanceController {
    private final AssistantMemoryStore store;
    private final AssistantMemoryMaintenanceService maintenance;
    public AssistantMemoryMaintenanceController(AssistantMemoryStore store,AssistantMemoryMaintenanceService maintenance) {
        this.store=store;this.maintenance=maintenance;
    }
    @PostMapping public ResponseEntity<Map<String,Object>> request() {
        long id=maintenance.request();
        return ResponseEntity.accepted().location(URI.create("/api/admin/assistant-memory/rebuilds/"+id)).body(store.status(id));
    }
    @GetMapping("/{id}") public Map<String,Object> status(@PathVariable long id) { return store.status(id); }
    @PostMapping("/{id}/retry") public ResponseEntity<Map<String,Object>> retry(@PathVariable long id) {
        maintenance.retry(id);return ResponseEntity.accepted().body(store.status(id));
    }
}
