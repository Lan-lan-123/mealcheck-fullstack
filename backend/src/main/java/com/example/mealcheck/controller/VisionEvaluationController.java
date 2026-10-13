package com.example.mealcheck.controller;

import com.example.mealcheck.service.*;
import com.example.mealcheck.dto.RecognitionResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/admin/vision-evaluation")
@PreAuthorize("hasRole('ADMIN')")
public class VisionEvaluationController {
    private final ImageUploadValidationService validation;
    private final VisionFoodRecognitionService vision;
    private final AiChatClient ai;
    @Value("${mealcheck.ai.model:unknown}") private String model;
    public VisionEvaluationController(ImageUploadValidationService validation,VisionFoodRecognitionService vision,AiChatClient ai) {
        this.validation=validation;this.vision=vision;this.ai=ai;
    }
    public record Prediction(String model,long durationMs,RecognitionResult recognition,AiChatClient.Usage usage) { }
    @PostMapping("/recognize")
    public Prediction recognize(@RequestParam("image") MultipartFile image) {
        long start=System.nanoTime();
        try {
            var result=vision.recognize(validation.validateAndNormalize(image));
            return new Prediction(model,(System.nanoTime()-start)/1_000_000,result,ai.lastUsage());
        } finally { ai.clearUsage(); }
    }
}
