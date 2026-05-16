package com.example.mealcheck.service;

import com.example.mealcheck.dto.AdminAuditLogPageResponse;
import com.example.mealcheck.dto.AdminAuditLogResponse;
import com.example.mealcheck.entity.AdminAuditLog;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.AdminAuditLogRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditService {
    private final AdminAuditLogRepository auditLogRepository;
    private final UserAccountRepository userAccountRepository;

    public AdminAuditService(AdminAuditLogRepository auditLogRepository,
                             UserAccountRepository userAccountRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional
    public void log(UserPrincipal principal, String action, String targetType, Long targetId, String detail) {
        AdminAuditLog log = new AdminAuditLog();
        if (principal != null) {
            userAccountRepository.findByUsername(principal.getUsername()).ifPresent(log::setAdmin);
            log.setAdminUsername(principal.getUsername());
        } else {
            log.setAdminUsername("unknown");
        }
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail == null ? "" : detail);
        auditLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public AdminAuditLogPageResponse list(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        Page<AdminAuditLog> logs = auditLogRepository.findAll(
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return new AdminAuditLogPageResponse(
                logs.getContent().stream().map(this::toResponse).toList(),
                logs.getTotalElements(),
                logs.getTotalPages(),
                logs.getNumber(),
                logs.getSize()
        );
    }

    private AdminAuditLogResponse toResponse(AdminAuditLog log) {
        return new AdminAuditLogResponse(
                log.getId(),
                log.getAdminUsername(),
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                log.getDetail(),
                log.getCreatedAt()
        );
    }
}
