package com.example.mealcheck.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PasswordByteLengthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordByteLength {
    String message() default "密码不能超过 72 个 UTF-8 字节";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
