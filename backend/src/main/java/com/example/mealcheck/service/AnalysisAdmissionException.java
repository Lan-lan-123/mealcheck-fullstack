package com.example.mealcheck.service;

public class AnalysisAdmissionException extends RuntimeException {
    private final String code;
    public AnalysisAdmissionException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
