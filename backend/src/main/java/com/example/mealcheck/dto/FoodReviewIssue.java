package com.example.mealcheck.dto;

/** Locally derived conflicts in model output; never accepted as model-authored instructions. */
public record FoodReviewIssue(String code, String message) { }
