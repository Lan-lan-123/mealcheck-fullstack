package com.example.mealcheck.dto;

public class AdminDashboardResponse {

    private AdminStatsResponse overview;
    private AdminSystemResponse system;

    public AdminDashboardResponse() {
    }

    public AdminDashboardResponse(AdminStatsResponse overview, AdminSystemResponse system) {
        this.overview = overview;
        this.system = system;
    }

    public AdminStatsResponse getOverview() {
        return overview;
    }

    public void setOverview(AdminStatsResponse overview) {
        this.overview = overview;
    }

    public AdminSystemResponse getSystem() {
        return system;
    }

    public void setSystem(AdminSystemResponse system) {
        this.system = system;
    }
}