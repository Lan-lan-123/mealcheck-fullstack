package com.example.mealcheck.security;

import com.example.mealcheck.util.AppTime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.example.mealcheck.service.TokenBlacklistService;
import com.example.mealcheck.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;
    private final ObjectMapper mapper;
    private final ObjectProvider<MeterRegistry> registries;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   CustomUserDetailsService userDetailsService,
                                   TokenBlacklistService tokenBlacklistService,
                                   ObjectMapper mapper, ObjectProvider<MeterRegistry> registries) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.mapper = mapper;
        this.registries = registries;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7);
            JwtService.TokenIdentity identity;
            try {
                identity = jwtService.extractIdentity(token);
            } catch (JwtException | IllegalArgumentException invalid) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }
            try {
                if (tokenBlacklistService.isBlacklisted(token)) {
                    throw new BadCredentialsException("Token has been revoked");
                }
                UserPrincipal userDetails = userDetailsService.loadUserById(identity.userId());
                if (!identity.username().equals(userDetails.getUsername())
                        || !userDetails.isEnabled() || !userDetails.isAccountNonLocked()
                        || !userDetails.isAccountNonExpired() || !userDetails.isCredentialsNonExpired()) {
                    throw new BadCredentialsException("Token does not match an active account");
                }
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (BadCredentialsException | UsernameNotFoundException invalid) {
                SecurityContextHolder.clearContext();
            } catch (RuntimeException unavailable) {
                SecurityContextHolder.clearContext();
                MeterRegistry registry = registries.getIfAvailable();
                if (registry != null) registry.counter("mealcheck.auth.dependency.failures").increment();
                log.warn("Authentication dependency unavailable type={}", unavailable.getClass().getSimpleName());
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Retry-After", "3");
                mapper.writeValue(response.getWriter(), new ApiErrorResponse(503, "Service Unavailable",
                        "AUTH_DEPENDENCY_UNAVAILABLE", "认证服务暂时不可用，请稍后重试。", AppTime.now()));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
