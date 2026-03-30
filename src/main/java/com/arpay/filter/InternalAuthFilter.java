package com.arpay.filter;

import com.arpay.service.InternalAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Filter for internal API authentication.
 * Validates X-API-Key header for protected endpoints.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class InternalAuthFilter extends OncePerRequestFilter {
    
    private final InternalAuthService internalAuthService;
    
    /**
     * Paths that are excluded from internal authentication
     */
    private static final List<String> EXCLUDED_PATHS = List.of(
        "/actuator/",
        "/api/notifications/tokens/",
        "/api/notifications/recent",
        "/api/notifications/unread-count"
    );
    
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        
        String path = request.getRequestURI();
        String method = request.getMethod();
        
        // Skip authentication for excluded paths
        if (isExcludedPath(path)) {
            log.debug("Skipping auth for excluded path: {}", path);
            filterChain.doFilter(request, response);
            return;
        }
        
        // Only protect write operations (POST, PUT, PATCH, DELETE)
        if (isReadOperation(method)) {
            log.debug("Skipping auth for read operation: {} {}", method, path);
            filterChain.doFilter(request, response);
            return;
        }
        
        // Extract API key from header
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
        filterChain.doFilter(request, response);
    }
    
    /**
     * Check if path is excluded from authentication
     */
    private boolean isExcludedPath(String path) {
        return EXCLUDED_PATHS.stream()
            .anyMatch(excluded -> path.startsWith(excluded));
    }
    
    /**
     * Check if HTTP method is a read operation
     */
    private boolean isReadOperation(String method) {
        return "GET".equalsIgnoreCase(method) || 
               "HEAD".equalsIgnoreCase(method) || 
               "OPTIONS".equalsIgnoreCase(method);
    }
}
