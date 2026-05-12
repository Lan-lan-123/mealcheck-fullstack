package com.example.mealcheck.service;

import com.example.mealcheck.dto.AuthDtos;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.JwtService;
import com.example.mealcheck.security.UserPrincipal;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserAccountRepository userRepository, PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String username = request.getUsername().trim();
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName() == null || request.getDisplayName().isBlank()
                ? username : request.getDisplayName().trim());
        userRepository.save(user);
        UserPrincipal principal = new UserPrincipal(user);
        return new AuthDtos.AuthResponse(
                jwtService.generateToken(principal),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole()
        );
    }

    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        return new AuthDtos.AuthResponse(
                jwtService.generateToken(principal),
                principal.getUsername(),
                principal.getDisplayName(),
                principal.getRole()
        );
    }
}
