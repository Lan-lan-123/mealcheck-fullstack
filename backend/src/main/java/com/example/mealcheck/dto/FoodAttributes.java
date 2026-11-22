package com.example.mealcheck.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Independent risk estimates. Null means unknown, not an assertion that a risk is absent. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FoodAttributes {
    private Boolean fried;
    private Boolean highOil;

    public FoodAttributes() { }
    public FoodAttributes(Boolean fried, Boolean highOil) { this.fried = fried; this.highOil = highOil; }
    public Boolean getFried() { return fried; }
    public void setFried(Boolean value) { fried = value; }
    public Boolean getHighOil() { return highOil; }
    public void setHighOil(Boolean value) { highOil = value; }
}
