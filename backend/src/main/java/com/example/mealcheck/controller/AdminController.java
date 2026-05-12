package com.example.mealcheck.controller;

import com.example.mealcheck.dto.AdminDashboardResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkRequest;
import com.example.mealcheck.dto.AdminKnowledgeChunkResponse;
import com.example.mealcheck.dto.AdminMealPageResponse;
import com.example.mealcheck.dto.AdminMealRecordResponse;
import com.example.mealcheck.dto.AdminStatsResponse;
import com.example.mealcheck.dto.AdminUserResponse;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/stats")
    public AdminStatsResponse stats() {
        return adminService.stats();
    }

    @GetMapping("/dashboard")
    public AdminDashboardResponse dashboard() {
        return adminService.dashboard();
    }

    @GetMapping("/users")
    public List<AdminUserResponse> users() {
        return adminService.users();
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        adminService.deleteUser(id, principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/meals")
    public AdminMealPageResponse meals(@RequestParam(value = "page", defaultValue = "0") int page,
                                       @RequestParam(value = "size", defaultValue = "20") int size,
                                       @RequestParam(value = "username", required = false) String username,
                                       @RequestParam(value = "from", required = false) LocalDate from,
                                       @RequestParam(value = "to", required = false) LocalDate to,
                                       @RequestParam(value = "minScore", required = false) Integer minScore,
                                       @RequestParam(value = "maxScore", required = false) Integer maxScore) {
        return adminService.meals(page, size, username, from, to, minScore, maxScore);
    }

    @DeleteMapping("/meals/{id}")
    public ResponseEntity<Void> deleteMeal(@PathVariable Long id) {
        adminService.deleteMealRecord(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/knowledge/chunks")
    public List<AdminKnowledgeChunkResponse> knowledgeChunks(@RequestParam(value = "title", required = false) String title) {
        return adminService.knowledgeChunks(title);
    }

    @PostMapping("/knowledge/chunks")
    public ResponseEntity<Void> addKnowledgeChunk(@Valid @RequestBody AdminKnowledgeChunkRequest request) {
        adminService.addKnowledgeChunk(request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/knowledge/chunks/{id}")
    public ResponseEntity<Void> deleteKnowledgeChunk(@PathVariable Long id) {
        adminService.deleteKnowledgeChunk(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/knowledge/chunks/{id}")
    public ResponseEntity<Void> updateKnowledgeChunk(@PathVariable Long id,
                                                     @Valid @RequestBody AdminKnowledgeChunkRequest request) {
        adminService.updateKnowledgeChunk(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/knowledge/reindex")
    public Object reindexKnowledge() {
        return adminService.reindexKnowledge();
    }
}
