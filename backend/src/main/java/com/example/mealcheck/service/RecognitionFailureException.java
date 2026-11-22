package com.example.mealcheck.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Stable, user-facing recognition failures; never use a provider message as a code. */
public class RecognitionFailureException extends ResponseStatusException {
    private final String code;
    public RecognitionFailureException(String code, HttpStatus status, String message) {
        super(status, message);
        this.code = code;
    }
    public String code() { return code; }
    public static RecognitionFailureException inconsistent() {
        return new RecognitionFailureException("RECOGNITION_INCONSISTENT", HttpStatus.BAD_GATEWAY,
                "识别结果中的餐食标记与食物列表不一致，请稍后使用原图重试。不能据此判断图片没有食物。");
    }
    public static RecognitionFailureException unclear() {
        return new RecognitionFailureException("IMAGE_UNCLEAR", HttpStatus.BAD_REQUEST,
                "图片不清晰或食物细节不足，暂时无法可靠识别，请重新拍摄。" );
    }
}
