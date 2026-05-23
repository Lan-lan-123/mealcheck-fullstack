package com.example.mealcheck.dto;

import java.util.List;

public class AdminUploadTrendResponse {
    private List<MetricItem> dailyNormalUploads;
    private List<MetricItem> dailyAbnormalUploads;
    private List<MetricItem> dailyAlertTriggers;
    private List<MetricItem> dailyBlockedUsers;

    public AdminUploadTrendResponse(List<MetricItem> dailyNormalUploads,
                                    List<MetricItem> dailyAbnormalUploads,
                                    List<MetricItem> dailyAlertTriggers,
                                    List<MetricItem> dailyBlockedUsers) {
        this.dailyNormalUploads = dailyNormalUploads;
        this.dailyAbnormalUploads = dailyAbnormalUploads;
        this.dailyAlertTriggers = dailyAlertTriggers;
        this.dailyBlockedUsers = dailyBlockedUsers;
    }

    public List<MetricItem> getDailyNormalUploads() { return dailyNormalUploads; }
    public List<MetricItem> getDailyAbnormalUploads() { return dailyAbnormalUploads; }
    public List<MetricItem> getDailyAlertTriggers() { return dailyAlertTriggers; }
    public List<MetricItem> getDailyBlockedUsers() { return dailyBlockedUsers; }

    public static class MetricItem {
        private String label;
        private int value;

        public MetricItem() {
        }

        public MetricItem(String label, int value) {
            this.label = label;
            this.value = value;
        }

        public String getLabel() { return label; }
        public int getValue() { return value; }
    }
}
