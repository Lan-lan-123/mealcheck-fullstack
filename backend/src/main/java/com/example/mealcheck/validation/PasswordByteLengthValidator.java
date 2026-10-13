package com.example.mealcheck.validation;

import com.example.mealcheck.security.StrictBCryptPasswordEncoder;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordByteLengthValidator implements ConstraintValidator<PasswordByteLength, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || StrictBCryptPasswordEncoder.withinLimit(value);
    }
}
