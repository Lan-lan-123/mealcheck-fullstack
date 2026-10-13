package com.example.mealcheck.controller;

import com.example.mealcheck.dto.*;
import com.example.mealcheck.dto.JournalDtos.*;
import com.example.mealcheck.dto.TrackingDtos.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api")
public class JournalController {
    private final MealJournalService journal;
    private final BehaviorTrackingService tracking;
    public JournalController(MealJournalService journal,BehaviorTrackingService tracking) { this.journal=journal; this.tracking=tracking; }
    @PostMapping("/meals/manual") public MealRecordResponse manual(@AuthenticationPrincipal UserPrincipal user,@Valid @RequestBody ManualMeal body) { return journal.create(user,body); }
    @PutMapping("/meals/{id}/metadata") public MealRecordResponse metadata(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@Valid @RequestBody Metadata body) { return journal.update(user,id,body); }
    @PutMapping("/meals/skipped") public void skipped(@AuthenticationPrincipal UserPrincipal user,@Valid @RequestBody SkipSlot body) { journal.skipped(user,body,false); }
    @DeleteMapping("/meals/skipped") public void undoSkip(@AuthenticationPrincipal UserPrincipal user,@Valid @RequestBody SkipSlot body) { journal.skipped(user,body,true); }
    @GetMapping("/meals/coverage") public Summary coverage(@AuthenticationPrincipal UserPrincipal user,@RequestParam LocalDate from,@RequestParam LocalDate to) { return tracking.summary(user.getId(),from,to); }
    @GetMapping("/profile/behavior-goals") public Page<Goal> goals(@AuthenticationPrincipal UserPrincipal user,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return tracking.goalPage(user.getId(),page,size); }
    @PostMapping("/profile/behavior-goals") public Goal createGoal(@AuthenticationPrincipal UserPrincipal user,@Valid @RequestBody BehaviorGoal body) { return tracking.saveGoal(user,null,body); }
    @PutMapping("/profile/behavior-goals/{id}") public Goal editGoal(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@Valid @RequestBody BehaviorGoal body) { return tracking.saveGoal(user,id,body); }
    @GetMapping("/plans") public Page<PlanView> plans(@AuthenticationPrincipal UserPrincipal user,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return tracking.planPage(user.getId(),from==null?com.example.mealcheck.util.AppTime.today().minusDays(30):from,to==null?com.example.mealcheck.util.AppTime.today().plusDays(90):to,page,size); }
    @PostMapping("/plans") public PlanView createPlan(@AuthenticationPrincipal UserPrincipal user,@Valid @RequestBody Plan body) { return tracking.createPlan(user,body); }
    @PutMapping("/plans/{id}") public PlanView updatePlan(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id,@Valid @RequestBody PlanUpdate body) { return tracking.updatePlan(user,id,body); }
}
