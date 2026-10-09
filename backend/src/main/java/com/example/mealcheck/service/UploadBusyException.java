package com.example.mealcheck.service;

import java.time.Duration;

/** A submission can retry with its original image, goal and idempotency key. */
public class UploadBusyException extends RateLimitExceededException {
    public UploadBusyException(long limit) {
        super("图片处理繁忙，正在等待上传空位，请稍后重试。", limit, 0, Duration.ofSeconds(1));
    }
    @Override public String getCode() { return "UPLOAD_BUSY"; }
}
