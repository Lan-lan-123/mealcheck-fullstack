package com.example.mealcheck.service;

public class UsernameConflictException extends RuntimeException {
    public UsernameConflictException() { super("用户名已存在"); }
}
