package com.example.mealcheck.service;

import com.example.mealcheck.dto.JournalDtos.*;
import com.example.mealcheck.dto.TrackingDtos.*;
import com.example.mealcheck.security.UserPrincipal;
import org.springframework.stereotype.Service;
import java.time.LocalDate;

/** Compatibility facade; business logic lives in the three focused services. */
@Service
public class BehaviorTrackingService {
    private final BehaviorGoalService goals;
    private final ActionPlanService plans;
    private final TrackingStatisticsService statistics;
    public BehaviorTrackingService(BehaviorGoalService goals, ActionPlanService plans, TrackingStatisticsService statistics) {
        this.goals = goals; this.plans = plans; this.statistics = statistics;
    }
    public Page<Goal> goalPage(long userId, int page, int size) { return goals.page(userId, page, size); }
    public Goal saveGoal(UserPrincipal user, Long id, BehaviorGoal body) { return goals.save(user, id, body); }
    public Summary summary(long userId, LocalDate from, LocalDate to) { return statistics.summary(userId, from, to); }
    public Page<PlanView> planPage(long userId, LocalDate from, LocalDate to, int page, int size) { return plans.page(userId, from, to, page, size); }
    public PlanView createPlan(UserPrincipal user, Plan body) { return plans.create(user, body); }
    public PlanView updatePlan(UserPrincipal user, long id, PlanUpdate body) { return plans.update(user, id, body); }
}
