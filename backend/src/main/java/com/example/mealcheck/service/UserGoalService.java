package com.example.mealcheck.service;

import com.example.mealcheck.dto.UserGoalRequest;
import com.example.mealcheck.dto.UserGoalResponse;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.UserGoal;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.repository.UserGoalRepository;
import com.example.mealcheck.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class UserGoalService {
    private final UserGoalRepository goalRepository;
    private final UserAccountRepository userRepository;

    public UserGoalService(UserGoalRepository goalRepository, UserAccountRepository userRepository) {
        this.goalRepository = goalRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserGoalResponse current(UserPrincipal principal) {
        UserAccount user = userRepository.findByUsername(principal.getUsername()).orElseThrow();
        return current(user);
    }

    @Transactional(readOnly = true)
    public UserGoalResponse current(UserAccount user) {
        return goalRepository.findFirstByUserAndActiveTrueOrderByCreatedAtDesc(user)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public UserGoalResponse update(UserPrincipal principal, UserGoalRequest request) {
        UserAccount user = userRepository.findByUsername(principal.getUsername()).orElseThrow();
        goalRepository.findFirstByUserAndActiveTrueOrderByCreatedAtDesc(user)
                .ifPresent(goal -> {
                    goal.setActive(false);
                    goalRepository.save(goal);
                });

        UserGoal goal = new UserGoal();
        goal.setUser(user);
        goal.setGoalType(normalizeGoal(request.getGoalType()));
        goal.setStartDate(request.getStartDate() == null ? LocalDate.now() : request.getStartDate());
        goal.setEndDate(request.getEndDate());
        goal.setNote(request.getNote());
        return toResponse(goalRepository.save(goal));
    }

    @Transactional(readOnly = true)
    public List<UserGoalResponse> history(UserPrincipal principal) {
        UserAccount user = userRepository.findByUsername(principal.getUsername()).orElseThrow();
        return goalRepository.findByUserOrderByCreatedAtDesc(user).stream().map(this::toResponse).toList();
    }

    public String effectiveGoal(UserAccount user, String requestedGoal) {
        if (requestedGoal != null && !requestedGoal.isBlank() && !"current".equalsIgnoreCase(requestedGoal)) {
            return requestedGoal;
        }
        return goalRepository.findFirstByUserAndActiveTrueOrderByCreatedAtDesc(user)
                .map(UserGoal::getGoalType)
                .orElse("balanced");
    }

    private String normalizeGoal(String goalType) {
        return goalType == null || goalType.isBlank() ? "balanced" : goalType.trim().toLowerCase();
    }

    private UserGoalResponse toResponse(UserGoal goal) {
        return new UserGoalResponse(
                goal.getId(),
                goal.getGoalType(),
                goal.getStartDate(),
                goal.getEndDate(),
                goal.getNote(),
                goal.isActive(),
                goal.getCreatedAt()
        );
    }
}
