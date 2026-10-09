package com.example.mealcheck.controller;

import com.example.mealcheck.dto.TaskFailureDtos.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.TaskFailureManagementService;
import jakarta.validation.Valid;
import com.example.mealcheck.util.AppTime;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/task-failures")
@PreAuthorize("hasRole('ADMIN')")
public class TaskFailureAdminController {
    private final TaskFailureManagementService service;
    public TaskFailureAdminController(TaskFailureManagementService service) { this.service = service; }
    @GetMapping
    public Page list(@RequestParam(defaultValue="ALL") String type,@RequestParam(defaultValue="OPEN") String status,
            @RequestParam(required=false) String code,@RequestParam(required=false) Long userId,
            @RequestParam(required=false) String from,
            @RequestParam(required=false) String to,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.list(type,status,code,userId,AppTime.parseFilterTime(from),AppTime.parseFilterTime(to),page,size);
    }
    @GetMapping("/summary") public Summary summary() { return service.summary(); }
    @GetMapping("/{id}") public Detail detail(@PathVariable long id) { return service.detail(id); }
    @PostMapping("/{id}/retry") public Detail retry(@PathVariable long id,@Valid @RequestBody Action action,
            @AuthenticationPrincipal UserPrincipal admin) { return service.retry(id,action,admin); }
    @PostMapping("/{id}/close") public Detail close(@PathVariable long id,@Valid @RequestBody Action action,
            @AuthenticationPrincipal UserPrincipal admin) { return service.close(id,action,admin); }
}
