package com.arpay.filter;

import com.arpay.entity.OutboxStatus;
import com.arpay.repository.NotificationDlqEntryRepository;
import com.arpay.repository.NotificationOutboxRepository;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Rate limiting filter for API backpressure.
 * Implements per-endpoint and global rate limiting with HARD LIMIT enforcement.
 *
 * This is REAL backpressure - rejects requests at API layer when system is overloaded.
 * Returns HTTP 429 when:
 * - Global rate limit exceeded
 * - Queue depth exceeds hard limit
 * - DLQ size exceeds hard stop threshold
 */
@Component
@Order(2)
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${notifications.ratelimit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${notifications.ratelimit.global-permits-per-second:1000}")
    private int globalPermitsPerSecond;

    @Value("${notifications.ratelimit.per-endpoint-permits-per-second:100}")
    private int perEndpointPermitsPerSecond;

    @Value("${notifications.ratelimit.per-client-permits-per-second:10}")
    private int perClientPermitsPerSecond;

    // Hard limits from configuration
    @Value("${notifications.system.max-queue-size:10000}")
    private int maxQueueSize;

    @Value("${notifications.system.max-dlq-size-hard-stop:200}")
    private int maxDlqSizeHardStop;

    @Value("${notifications.system.ingestion-pause-threshold-percent:90}")
    private int ingestionPauseThresholdPercent;

    // Global rate limiter — initialised in @PostConstruct so it respects the @Value field
    private RateLimiter globalRateLimiter;

    // Per-endpoint rate limiters
    private final ConcurrentHashMap<String, RateLimiter> endpointLimiters = new ConcurrentHashMap<>();

    // Per-client (IP) rate limiters
    private final ConcurrentHashMap<String, RateLimiter> clientLimiters = new ConcurrentHashMap<>();

    // Repository injections for hard limit checks
    private final NotificationOutboxRepository outboxRepository;
    private final NotificationDlqEntryRepository dlqRepository;

    public RateLimitFilter(NotificationOutboxRepository outboxRepository,
                           NotificationDlqEntryRepository dlqRepository) {
        this.outboxRepository = outboxRepository;
        this.dlqRepository = dlqRepository;
    }

    @PostConstruct
    public void init() {
        globalRateLimiter = RateLimiter.create(globalPermitsPerSecond);
        log.info("RateLimitFilter initialised: globalRate={}/s, endpointRate={}/s, clientRate={}/s, maxQueueSize={}, maxDlq={}",
                 globalPermitsPerSecond, perEndpointPermitsPerSecond, perClientPermitsPerSecond,
                 maxQueueSize, maxDlqSizeHardStop);
    }

    /**
     * Dynamically adjust the global rate limit based on downstream backpressure.
     * Called periodically by {@link com.arpay.worker.BackpressureMonitor}.
     *
     * @param pressureRatio 0.0 = no pressure (full rate), 1.0 = maximum pressure (10 % of base rate)
     */
    public void adjustForBackpressure(double pressureRatio) {
        double scale = Math.max(0.1, 1.0 - pressureRatio);
        double newRate = globalPermitsPerSecond * scale;
        if (Math.abs(globalRateLimiter.getRate() - newRate) > 1.0) {
            globalRateLimiter.setRate(newRate);
            if (pressureRatio > 0.3) {
                log.warn("Backpressure: adjusted global rate limit to {:.1f} req/s (pressure={:.0f}%)",
                         newRate, pressureRatio * 100);
            }
        }
    }
    
    // Endpoints to rate limit
    private static final String[] RATE_LIMITED_ENDPOINTS = {
        "/api/notifications/send/user",
        "/api/notifications/send/role",
        "/api/notifications"
    };
    
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!rateLimitEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        String method = request.getMethod();
        String clientIp = getClientIp(request);

        // Only rate limit POST requests to notification endpoints
        if (!"POST".equalsIgnoreCase(method) || !isRateLimitedEndpoint(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        // HARD LIMIT CHECK 1: DLQ hard stop
        long dlqSize = dlqRepository.countByResolvedFalse();
        if (dlqSize >= maxDlqSizeHardStop) {
            log.error("HARD STOP: DLQ size {} exceeds hard stop limit {} - rejecting request", dlqSize, maxDlqSizeHardStop);
            response.setStatus(429);  // HTTP 429 Too Many Requests
            response.setHeader("X-RateLimit-Reason", "DLQ_HARD_STOP");
            response.setHeader("X-RateLimit-Retry-After", "60");
            response.getWriter().write("Service temporarily unavailable: Too many failed deliveries. Retry after 60 seconds.");
            return;
        }

        // HARD LIMIT CHECK 2: Queue depth hard stop
        long queueDepth = outboxRepository.countByStatus(OutboxStatus.PENDING);
        int hardStopThreshold = (int) (maxQueueSize * ingestionPauseThresholdPercent / 100);
        if (queueDepth >= hardStopThreshold) {
            log.error("HARD STOP: Queue depth {} exceeds pause threshold {} - rejecting request", queueDepth, hardStopThreshold);
            response.setStatus(429);  // HTTP 429 Too Many Requests
            response.setHeader("X-RateLimit-Reason", "QUEUE_HARD_STOP");
            response.setHeader("X-RateLimit-Retry-After", "30");
            response.getWriter().write("Service temporarily unavailable: Queue capacity exceeded. Retry after 30 seconds.");
            return;
        }

        // Check global rate limit
        if (!globalRateLimiter.tryAcquire(100, TimeUnit.MILLISECONDS)) {
            log.warn("Global rate limit exceeded for endpoint={}", uri);
            response.setStatus(429);  // HTTP 429 Too Many Requests
            response.setHeader("X-RateLimit-Reason", "GLOBAL_RATE_LIMIT");
            response.getWriter().write("Rate limit exceeded. Please retry later.");
            return;
        }

        // Check per-endpoint rate limit
        RateLimiter endpointLimiter = endpointLimiters.computeIfAbsent(
            uri,
            k -> RateLimiter.create(perEndpointPermitsPerSecond)
        );

        if (!endpointLimiter.tryAcquire(100, TimeUnit.MILLISECONDS)) {
            log.warn("Endpoint rate limit exceeded for endpoint={}", uri);
            response.setStatus(429);  // HTTP 429 Too Many Requests
            response.setHeader("X-RateLimit-Reason", "ENDPOINT_RATE_LIMIT");
            response.getWriter().write("Rate limit exceeded for this endpoint.");
            return;
        }

        // Check per-client rate limit (optional - can be disabled for internal APIs)
        RateLimiter clientLimiter = clientLimiters.computeIfAbsent(
            clientIp,
            k -> RateLimiter.create(perClientPermitsPerSecond)
        );

        if (!clientLimiter.tryAcquire(100, TimeUnit.MILLISECONDS)) {
            log.warn("Client rate limit exceeded for clientIp={}, endpoint={}", clientIp, uri);
            response.setStatus(429);  // HTTP 429 Too Many Requests
            response.setHeader("X-RateLimit-Reason", "CLIENT_RATE_LIMIT");
            response.getWriter().write("Rate limit exceeded. Please slow down.");
            return;
        }

        // All rate limits and hard limits passed
        filterChain.doFilter(request, response);
    }
    
    /**
     * Check if endpoint should be rate limited
     */
    private boolean isRateLimitedEndpoint(String uri) {
        for (String endpoint : RATE_LIMITED_ENDPOINTS) {
            if (uri.startsWith(endpoint)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get client IP address
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
    
    /**
     * Cleanup old rate limiters (prevent memory leak)
     * Called periodically by scheduler
     */
    public void cleanupStaleLimiters() {
        // This is a simple implementation - in production, use Caffeine cache with expiry
        log.debug("Rate limiter cleanup not implemented - using unbounded maps");
    }
}
