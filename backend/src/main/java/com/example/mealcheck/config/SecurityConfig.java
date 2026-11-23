package com.example.mealcheck.config;

import com.example.mealcheck.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import com.example.mealcheck.security.StrictBCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    public com.example.mealcheck.security.AuthenticationCookies authenticationCookies(AppProperties properties) {
        return new com.example.mealcheck.security.AuthenticationCookies(properties);
    }
    @Bean
    public org.springframework.security.web.csrf.CookieCsrfTokenRepository csrfTokenRepository(com.example.mealcheck.security.AuthenticationCookies cookies) {
        return cookies.csrfRepository();
    }
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   AuthenticationProvider authenticationProvider,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   AppProperties properties,
                                                   org.springframework.security.web.csrf.CookieCsrfTokenRepository csrfTokenRepository,
                                                   com.fasterxml.jackson.databind.ObjectMapper mapper,
                                                   @org.springframework.beans.factory.annotation.Value("${spring.servlet.multipart.location:${java.io.tmpdir}/mealcheck-multipart}") String uploadTemp,
                                                   @org.springframework.beans.factory.annotation.Value("${spring.servlet.multipart.max-request-size:12MB}") String uploadMaxRequest) throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> response.sendError(401))
                        .accessDeniedHandler((request, response, error) -> {
                            response.setStatus(403);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
                            boolean csrf=error instanceof org.springframework.security.web.csrf.CsrfException;
                            mapper.writeValue(response.getWriter(),new com.example.mealcheck.dto.ApiErrorResponse(403,"Forbidden",csrf?"CSRF_INVALID":"ACCESS_DENIED",
                                    csrf?"安全校验已失效，请刷新页面后重试。":"没有权限执行此操作。",com.example.mealcheck.util.AppTime.now()));
                        }))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        .requestMatchers(request -> request.getLocalPort()==9091 && request.getRequestURI().equals("/actuator/prometheus")).permitAll()
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers("/api/auth/**", "/actuator/health", "/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new com.example.mealcheck.security.AiOperationFilter(properties), JwtAuthenticationFilter.class)
                .addFilterAfter(new com.example.mealcheck.security.UploadAdmissionFilter(properties, uploadTemp,
                        org.springframework.util.unit.DataSize.parse(uploadMaxRequest).toBytes(), mapper),
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(UserDetailsService userDetailsService, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new StrictBCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(properties.getCors().getAllowedOrigins().split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Request-ID", "Idempotency-Key", "X-XSRF-TOKEN"));
        configuration.setExposedHeaders(List.of("X-Request-ID", "X-Assistant-Request-ID", "Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
