package com.example.mealcheck;

import com.example.mealcheck.util.AppTime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MealCheckApplication {
    public static void main(String[] args) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(AppTime.storageZone()));
        SpringApplication.run(MealCheckApplication.class, args);
    }
}
