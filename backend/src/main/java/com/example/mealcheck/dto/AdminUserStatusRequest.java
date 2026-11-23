package com.example.mealcheck.dto;

import jakarta.validation.constraints.NotNull;

public record AdminUserStatusRequest(@NotNull Boolean enabled) {}
