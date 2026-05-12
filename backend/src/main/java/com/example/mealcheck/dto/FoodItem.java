package com.example.mealcheck.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FoodItem {
    private String name;
    private String category;
    private double confidence;
    private String note;

    public FoodItem() {}
    public FoodItem(String name, String category, double confidence, String note) {
        this.name = name;
        this.category = category;
        this.confidence = confidence;
        this.note = note;
    }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
