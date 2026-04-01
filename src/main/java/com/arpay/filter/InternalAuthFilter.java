package com.arpay.filter;

import com.arpay.service.InternalAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.springframework.lang.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * Filter for internal API authentication.
 * Validates X-API-Key header for protected endpoints.
 *
 * <p>After a valid API key is confirmed, an {@link UsernamePasswordAuthenticationToken}
 * with ROLE_INTERNAL is set in {@link SecurityContextHolder} so that Spring Security's
 * {@code anyRequest().authenticated()} authorisation check passes.  The context is
 * cleared in a {@code finally} block to prevent leaking between requests.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class InternalAuthFilter extends OncePerRequestFilter {

    private final InternalAuthService internalAuthService;

    /**
     * Path prefixes that are excluded from internal API-key authentication.
     * Note: checked with startsWith(), so "/api/notifications/tokens" covers
     * both "/api/notifications/tokens" and "/api/notifications/tokens/…".
     */
    private static final List<String> EXCLUDED_PATHS = List.of(
        "/actuator",
        "/api/notifications/tokens"
    );

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Skip API-key check for excluded paths (actuator, device-token registration)
        if (isExcludedPath(path)) {
            log.debug("Skipping auth for excluded path: {}", path);
            filterChain.doFilter(request, response);
            return;
        }

        // Extract API key from the configured header
        String apiKeyHeader = internalAuthService.getApiKeyHeader();
        String apiKey = request.getHeader(apiKeyHeader);

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Missing API key for protected endpoint: {} {}", method, path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Missing API key. Header required: " + apiKeyHeader);
            return;
        }

        // Validate API key
        if (!internalAuthService.isValidApiKey(apiKey)) {
            log.warn("Invalid API key for endpoint: {} {}", method, path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Invalid API key");
            return;
        }

        log.debug("API key validated for endpoint: {} {}", method, path);

        // ── KEY FIX ────────────────────────────────────────────────────────────
        // Populate SecurityContextHolder so Spring Security's
        // anyRequest().authenticated() check passes for service-to-service calls.
        // Without this, the filter chain continued but the AuthorizationFilter
        // saw no Authentication and rejected all non-permitAll endpoints with 403.
        // ───────────────────────────────────────────────────────────────────────
        var authentication = new UsernamePasswordAuthenticationToken(
                "internal-service", null,
                List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always clear context to prevent leaking between pooled threads
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isExcludedPath(String path) {
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }
}
