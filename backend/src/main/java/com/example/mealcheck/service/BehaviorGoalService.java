package com.example.mealcheck.service;

import com.example.mealcheck.dto.JournalDtos.BehaviorGoal;
import com.example.mealcheck.dto.TrackingDtos.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BehaviorGoalService {
    private final BehaviorGoalRepository goals;
    private final UserAccountRepository users;
    public BehaviorGoalService(BehaviorGoalRepository goals, UserAccountRepository users) { this.goals = goals; this.users = users; }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page<Goal> page(long userId, int page, int size) {
        JournalValidation.page(page, size); return goals.page(userId, page, size);
    }
    @Transactional
    public Goal save(UserPrincipal user, Long id, BehaviorGoal body) {
        users.findByIdForUpdate(user.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        JournalValidation.range(body.startDate(), body.endDate());
        if (body.kind().equals("MEALS_DAILY") && body.target() > 4) throw new IllegalArgumentException("每天记录目标最多 4 个餐次。");
        if (id != null && goals.owned(user.getId(), id).orElseThrow(() -> JournalValidation.missing("目标")).version() != body.expectedVersion())
            throw JournalValidation.conflict();
        if (body.active() && goals.overlaps(user.getId(), id, body))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同类型目标周期有重叠，请先停用原目标。");
        long saved = id == null ? goals.insert(user.getId(), body) : id;
        if (id != null) goals.update(user.getId(), id, body);
        // Behavior progress is attached at read time; changing a target does not erase narrative reports.
        return goals.owned(user.getId(), saved).orElseThrow();
    }
}
