package com.example.mealcheck.service;

import com.example.mealcheck.dto.JournalDtos.*;
import com.example.mealcheck.dto.TrackingDtos.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;

@Service
public class ActionPlanService {
    private final ActionPlanRepository plans;
    private final UserAccountRepository users;
    public ActionPlanService(ActionPlanRepository plans, UserAccountRepository users) { this.plans = plans; this.users = users; }
    private void lock(UserPrincipal user) { users.findByIdForUpdate(user.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)); }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page<PlanView> page(long userId, LocalDate from, LocalDate to, int page, int size) {
        JournalValidation.range(from, to); JournalValidation.page(page, size);
        return plans.page(userId, from, to, page, size);
    }
    @Transactional
    public PlanView create(UserPrincipal user, Plan body) {
        lock(user); JournalValidation.range(body.plannedDate(), body.plannedDate());
        switch (body.sourceType()) {
            case "MEAL" -> {
                if (body.sourceRecordId() == null || body.sourceMessageId() != null || !plans.ownsMeal(user.getId(), body.sourceRecordId())) throw JournalValidation.missing("建议来源");
            }
            case "ASSISTANT" -> {
                if (body.sourceMessageId() == null || body.sourceRecordId() != null || !plans.ownsCompletedAnswer(user.getId(), body.sourceMessageId())) throw JournalValidation.missing("建议来源");
            }
            default -> { if (body.sourceRecordId() != null || body.sourceMessageId() != null) throw new IllegalArgumentException("建议来源与关联记录不匹配。"); }
        }
        if (body.sourceType().equals("REPORT")) JournalValidation.range(body.sourcePeriodStart(), body.sourcePeriodEnd());
        else if (body.sourcePeriodStart() != null || body.sourcePeriodEnd() != null) throw new IllegalArgumentException("仅周报建议可以关联报告周期。");
        return plans.owned(user.getId(), plans.insert(user.getId(), body)).orElseThrow();
    }
    @Transactional
    public PlanView update(UserPrincipal user, long id, PlanUpdate body) {
        lock(user); JournalValidation.range(body.plannedDate(), body.plannedDate());
        var old = plans.owned(user.getId(), id).orElseThrow(() -> JournalValidation.missing("计划"));
        if (old.version() != body.expectedVersion()) throw JournalValidation.conflict();
        plans.update(user.getId(), id, body);
        // Execution counts are read live for the report period, independently of narrative snapshots.
        return plans.owned(user.getId(), id).orElseThrow();
    }
}
