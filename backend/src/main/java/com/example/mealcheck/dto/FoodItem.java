package com.example.mealcheck.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FoodItem {
    private String name;
    private String category;
    private double confidence;
    private String note;
    private FoodAttributes attributes;
    private String identityEvidence;
    private java.util.List<FoodRoleEvidence> structureRoles=java.util.List.of();
    public java.util.List<FoodRoleEvidence> getStructureRoles() {return structureRoles;}
    public void setStructureRoles(java.util.List<FoodRoleEvidence> value) {structureRoles=value==null?java.util.List.of():java.util.List.copyOf(value);}
    private java.util.List<FoodReviewIssue> reviewIssues = java.util.List.of();

    public FoodItem() {}
    public FoodItem(String name, String category, double confidence, String note) {
        this.name = name;
        this.category = category;
        this.confidence = confidence;
        this.note = note;
    }
    public FoodItem(String name, String category, double confidence, String note, FoodAttributes attributes) {
        this(name, category, confidence, note);
        this.attributes = attributes;
    }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public FoodAttributes getAttributes() { return attributes; }
    public String getIdentityEvidence() { return identityEvidence; }
    public void setIdentityEvidence(String value) { identityEvidence = value; }
    public void setAttributes(FoodAttributes attributes) { this.attributes = attributes; }
    public java.util.List<FoodReviewIssue> getReviewIssues() { return reviewIssues; }
    public void setReviewIssues(java.util.List<FoodReviewIssue> value) { reviewIssues = value == null ? java.util.List.of() : java.util.List.copyOf(value); }
}
