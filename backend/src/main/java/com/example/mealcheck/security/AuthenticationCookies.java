package com.example.mealcheck.security;

import com.example.mealcheck.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import java.time.Duration;

/** Host-only cookies; production requires HTTPS even behind the trusted reverse proxy. */
public class AuthenticationCookies {
    private final AppProperties properties;
    public AuthenticationCookies(AppProperties properties) { this.properties=properties; }
    public String name() { return properties.getJwt().isCookieSecure()?"__Host-mealcheck_session":"mealcheck_session"; }
    public String read(HttpServletRequest request) {
        String token=null;
        if(request.getCookies()!=null)for(var cookie:request.getCookies())if(name().equals(cookie.getName())) {
            if(token!=null)return "invalid-duplicate-cookie";
            token=cookie.getValue();
        }
        return token;
    }
    public void write(HttpServletResponse response,String token,Duration ttl) {
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(name(),token).httpOnly(true)
                .secure(properties.getJwt().isCookieSecure()).sameSite("Lax").path("/").maxAge(ttl).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
    }
    public void clear(HttpServletResponse response) { write(response,"",Duration.ZERO); }
    public CookieCsrfTokenRepository csrfRepository() {
        var repository=new CookieCsrfTokenRepository();
        repository.setCookieName(properties.getJwt().isCookieSecure()?"__Host-mealcheck_csrf":"mealcheck_csrf");
        repository.setCookieCustomizer(cookie->cookie.httpOnly(true).secure(properties.getJwt().isCookieSecure()).sameSite("Lax").path("/"));
        return repository;
    }
}
