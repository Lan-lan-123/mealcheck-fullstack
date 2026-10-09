package com.example.mealcheck.service;

import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class UserAccountDeletionService {
    private final UserAccountRepository userAccountRepository;
    private final MealRecordRepository mealRecordRepository;
    private final ImageStorageService imageStorageService;
    private final RedisCacheService redisCacheService;
    private final PasswordEncoder passwordEncoder;

    public UserAccountDeletionService(UserAccountRepository userAccountRepository,
                                      MealRecordRepository mealRecordRepository,
                                      ImageStorageService imageStorageService,
                                      RedisCacheService redisCacheService,
                                      PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.mealRecordRepository = mealRecordRepository;
        this.imageStorageService = imageStorageService;
        this.redisCacheService = redisCacheService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void deleteCurrentAccount(UserPrincipal principal, String password) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if ("ADMIN".equalsIgnoreCase(principal.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "管理员账号不能通过用户端自助注销");
        }
        if (!passwordEncoder.matches(password, principal.getPassword())) {
            throw new BadCredentialsException("密码不正确");
        }

        UserAccount user = userAccountRepository.findByIdForUpdate(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        List<String> imagePaths = mealRecordRepository.findByUser(user).stream()
                .map(MealRecord::getStoredImagePath)
                .filter(path -> path != null && !path.isBlank())
                .toList();
        String username = user.getUsername();

        userAccountRepository.delete(user);
        userAccountRepository.flush();
        imagePaths.forEach(imageStorageService::deleteAfterCommit);
        afterCommit(() -> clearUserCache(username));
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void clearUserCache(String username) {
        redisCacheService.deleteByPrefix("assistant:history:" + username + ":");
        redisCacheService.delete("upload:nonfood:count:" + username);
        redisCacheService.delete("upload:nonfood:alert:" + username);
        redisCacheService.delete("upload:nonfood:block:" + username);
        redisCacheService.delete("upload:nonfood:allow:" + username);
    }
}
