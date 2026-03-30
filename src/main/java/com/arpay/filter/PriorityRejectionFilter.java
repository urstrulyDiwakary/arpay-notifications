package com.arpay.filter;

import com.arpay.entity.OutboxStatus;
import com.arpay.repository.NotificationDlqEntryRepository;
import com.arpay.repository.NotificationOutboxRepository;
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

/**
 * Priority-based rejection filter.
 * <p>
 * Instead of rejecting all traffic when overloaded, implements graceful degradation:
 * - Queue > 70%: Reject LOW priority notifications
 * - Queue > 85%: Reject LOW + NORMAL priority notifications
 * - Queue > 90%: Reject ALL except CRITICAL
 * - CRITICAL: Always accepted (unless system completely down)
 * <p>
 * Priority is determined by:
 * - X-Priority header (CRITICAL, NORMAL, LOW)
 * - Entity type (PAYMENT=CRITICAL, APPROVAL=NORMAL, GENERAL=LOW)
 * - Default: NORMAL
 */
@Component
@Order(1) // Run before RateLimitFilter
@Slf4j
public class PriorityRejectionFilter extends OncePerRequestFilter {

    @Value("${notifications.system.max-queue-size:10000}")
    private int maxQueueSize;

    @Value("${notifications.priority.rejection.low-threshold-percent:70}")
    private int lowPriorityThresholdPercent;

    @Value("${notifications.priority.rejection.normal-threshold-percent:85}")
    private int normalPriorityThresholdPercent;

    @Value("${notifications.priority.rejection.enabled:true}")
    private boolean priorityRejectionEnabled;

    private final NotificationOutboxRepository outboxRepository;

    public PriorityRejectionFilter(NotificationOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!priorityRejectionEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only apply to notification creation endpoints
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/notifications") || !uri.contains("/send")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only apply to POST requests
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Determine notification priority
        String priority = determinePriority(request);
        long queueDepth = outboxRepository.countByStatus(OutboxStatus.PENDING);
        double queueUsagePercent = (double) queueDepth / maxQueueSize * 100;

        // Priority-aware rejection logic
        if (shouldReject(priority, queueUsagePercent)) {
            log.warn("Priority-based rejection: priority={}, queueDepth={}, usagePercent={}",
                    priority, queueDepth, queueUsagePercent);

            response.setStatus(429);  // HTTP 429 Too Many Requests
            response.setHeader("X-RateLimit-Reason", "PRIORITY_REJECTION");
            response.setHeader("X-RateLimit-Priority", priority);
            response.setHeader("X-RateLimit-Queue-Usage", String.format("%.1f", queueUsagePercent));
            response.setHeader("Retry-After", "30");

            String message = String.format(
                "Service temporarily unavailable: %s priority notifications rejected due to queue pressure (%.1f%%). " +
                "CRITICAL notifications still accepted. Retry after 30 seconds.",
                priority, queueUsagePercent
            );
            response.getWriter().write(message);
            return;
        }

        // Priority check passed, continue to next filter
        filterChain.doFilter(request, response);
    }

    /**
     * Determine notification priority from request
     */
    private String determinePriority(HttpServletRequest request) {
        // Check explicit priority header
        String priorityHeader = request.getHeader("X-Priority");
        if (priorityHeader != null && !priorityHeader.isBlank()) {
            return priorityHeader.trim().toUpperCase();
        }

        // Infer priority from entity type
        String entityType = request.getHeader("X-Entity-Type");
        if (entityType == null) {
            entityType = request.getParameter("entityType");
        }

        if (entityType != null) {
            String type = entityType.toUpperCase();
            if (type.contains("PAYMENT") || type.contains("FRAUD") || type.contains("SECURITY")) {
                return "CRITICAL";
            } else if (type.contains("APPROVAL") || type.contains("TRANSACTION")) {
                return "NORMAL";
            } else if (type.contains("MARKETING") || type.contains("PROMO") || type.contains("GENERAL")) {
                return "LOW";
            }
        }

        // Default priority
        return "NORMAL";
    }

    /**
     * Determine if request should be rejected based on priority and queue usage
     */
    private boolean shouldReject(String priority, double queueUsagePercent) {
        return switch (priority) {
            case "LOW" -> queueUsagePercent >= lowPriorityThresholdPercent;
            case "NORMAL" -> queueUsagePercent >= normalPriorityThresholdPercent;
            case "CRITICAL" -> false; // Always accept CRITICAL (unless queue is 100% full)
            default -> queueUsagePercent >= normalPriorityThresholdPercent;
        };
    }
}
