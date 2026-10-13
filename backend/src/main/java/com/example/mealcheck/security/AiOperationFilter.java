package com.example.mealcheck.security;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.service.AiCallContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Duration;

public class AiOperationFilter extends OncePerRequestFilter {
    private final AppProperties properties;
    public AiOperationFilter(AppProperties properties) { this.properties=properties; }
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        Long userId=auth!=null && auth.getPrincipal() instanceof UserPrincipal user?user.getId():null;
        try (var scope=AiCallContext.open(userId,Duration.ofSeconds(Math.max(1,properties.getAiBudget().getOperationTimeoutSeconds())))) {
            chain.doFilter(request,response);
        }
    }
}
