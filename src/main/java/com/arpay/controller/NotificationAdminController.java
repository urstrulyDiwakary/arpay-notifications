package com.arpay.controller;

import com.arpay.dto.NotificationDeliveryTraceDTO;
import com.arpay.dto.NotificationHealthDTO;
import com.arpay.entity.*;
import com.arpay.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin dashboard controller for notification health monitoring.
 * Simple visibility into what matters: failures, DLQ, and token health.
 */
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationAdminController {
    
    private final NotificationRepository notificationRepository;
    private final DeliveryIdempotencyKeyRepository idempotencyKeyRepository;
    private final NotificationDlqEntryRepository dlqRepository;
    private final UserDeviceTokenRepository deviceTokenRepository;
    private final NotificationOutboxRepository outboxRepository;
    
    @Value("${internal.api.header:X-API-Key}")
    private String apiKeyHeader;
    
    /**
     * Get notification health dashboard data
     * Requires API key authentication
     */
    @GetMapping("/health")
    public NotificationHealthDTO getHealthDashboard() {
        log.info("Admin: Fetching notification health dashboard");
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime twentyFourHoursAgo = now.minusHours(24);
        
        // Calculate summary statistics
        NotificationHealthDTO.Summary summary = new NotificationHealthDTO.Summary();
        
        // Count deliveries in last 24h
        long totalDeliveries = idempotencyKeyRepository.countByStatus(
            DeliveryIdempotencyKey.DeliveryStatus.SENT);
        long failedDeliveries = idempotencyKeyRepository.countByStatus(
            DeliveryIdempotencyKey.DeliveryStatus.FAILED);
        long duplicateDeliveries = idempotencyKeyRepository.countByStatus(
            DeliveryIdempotencyKey.DeliveryStatus.DUPLICATE);
        long permanentFailures = idempotencyKeyRepository.countByStatus(
            DeliveryIdempotencyKey.DeliveryStatus.PERMANENT_FAILURE);
        
        summary.setSuccessfulDeliveries(totalDeliveries);
        summary.setFailedDeliveries(failedDeliveries + permanentFailures);
        summary.setDuplicateDeliveries(duplicateDeliveries);
        summary.setTotalNotificationsLast24h(totalDeliveries + failedDeliveries + duplicateDeliveries + permanentFailures);
        
        // Calculate success rate
        long total = totalDeliveries + failedDeliveries + duplicateDeliveries + permanentFailures;
        if (total > 0) {
            summary.setSuccessRatePercent((double) totalDeliveries / total * 100);
        } else {
            summary.setSuccessRatePercent(100.0);
        }
        
        // DLQ size
        summary.setDlqSize((int) dlqRepository.countByResolvedFalse());
        
        // Pending outbox
        summary.setPendingOutboxSize((int) outboxRepository.countByStatus(OutboxStatus.PENDING));
        
        // Invalidated tokens
        summary.setInvalidatedTokenCount(deviceTokenRepository.findAllByIsActiveFalse().size());
        
        summary.setLastRefreshAt(now);
        
        // Build response
        NotificationHealthDTO response = new NotificationHealthDTO();
        response.setSummary(summary);
        response.setRecentFailures(getRecentFailures(100));
        response.setDlqEntries(getPendingDlqEntries(50));
        response.setInvalidatedTokens(getRecentlyInvalidatedTokens(50));
        
        return response;
    }
    
    /**
     * Get recent failed deliveries
     */
    private List<NotificationHealthDTO.FailedDeliveryDTO> getRecentFailures(int limit) {
        List<DeliveryIdempotencyKey> failed = idempotencyKeyRepository.findPendingDeliveries()
            .stream()
            .filter(k -> k.getStatus() == DeliveryIdempotencyKey.DeliveryStatus.FAILED ||
                        k.getStatus() == DeliveryIdempotencyKey.DeliveryStatus.PERMANENT_FAILURE)
            .limit(limit)
            .toList();
        
        return failed.stream().map(key -> {
            NotificationHealthDTO.FailedDeliveryDTO dto = 
                new NotificationHealthDTO.FailedDeliveryDTO();
            dto.setNotificationId(key.getNotificationId());
            dto.setUserId(null); // Would need join to get this
            dto.setTitle("Notification"); // Would need join to get this
            dto.setDeviceTokenPrefix(maskToken(key.getDeviceToken()));
            dto.setErrorCode(key.getFcmErrorCode());
            dto.setErrorMessage(truncate(key.getErrorMessage(), 100));
            dto.setAttemptCount(key.getAttemptCount());
            dto.setCreatedAt(key.getCreatedAt());
            return dto;
        }).collect(Collectors.toList());
    }
    
    /**
     * Get pending DLQ entries
     */
    private List<NotificationHealthDTO.DLQEntryDTO> getPendingDlqEntries(int limit) {
        List<NotificationDlqEntry> entries = dlqRepository.findUnresolved()
                .stream().limit(limit).toList();
        
        return entries.stream().map(entry -> {
            NotificationHealthDTO.DLQEntryDTO dto = 
                new NotificationHealthDTO.DLQEntryDTO();
            dto.setId(entry.getId());
            dto.setEventId(entry.getEventId());
            dto.setErrorMessage(truncate(entry.getErrorMessage(), 100));
            dto.setRetryCount(entry.getRetryCount());
            dto.setCreatedAt(entry.getCreatedAt());
            dto.setLastRetryAt(entry.getLastRetryAt());
            return dto;
        }).collect(Collectors.toList());
    }
    
    /**
     * Get recently invalidated tokens
     */
    private List<NotificationHealthDTO.InvalidatedTokenDTO> getRecentlyInvalidatedTokens(int limit) {
        List<UserDeviceToken> tokens = deviceTokenRepository.findAllByIsActiveFalse()
            .stream()
            .limit(limit)
            .toList();
        
        return tokens.stream().map(token -> {
            NotificationHealthDTO.InvalidatedTokenDTO dto = 
                new NotificationHealthDTO.InvalidatedTokenDTO();
            dto.setTokenId(token.getId());
            dto.setUserId(token.getUserId());
            dto.setDeviceType(token.getDeviceType() != null ? token.getDeviceType().name() : "UNKNOWN");
            dto.setInvalidationReason(token.getInvalidationReason());
            dto.setErrorMessagePrefix(truncate(token.getFcmErrorMessage(), 50));
            dto.setInvalidatedAt(token.getInvalidatedAt());
            return dto;
        }).collect(Collectors.toList());
    }
    
    /**
     * Mask device token for security (show first 8 chars only)
     */
    private String maskToken(String token) {
        if (token == null || token.length() < 8) {
            return "***";
        }
        return token.substring(0, 8) + "...";
    }
    
    /**
     * Truncate string to max length
     */
    private String truncate(String str, int max) {
        if (str == null) {
            return null;
        }
        return str.length() > max ? str.substring(0, max) : str;
    }
    
    /**
     * Manual DLQ retry trigger (for ops team)
     */
    @PostMapping("/dlq/retry")
    public void triggerDlqRetry(@RequestParam int limit) {
        log.info("Admin: Triggering DLQ retry for {} entries", limit);
        // Would call DLQRetryScheduler here
        // For now, just log
    }

    /**
     * Get DLQ entries with pagination and filtering
     */
    @GetMapping("/dlq")
    public List<NotificationDlqEntry> getDlqEntries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(required = false) String eventId) {
        
        log.info("Admin: Fetching DLQ entries - page={}, size={}, resolved={}, eventId={}", page, size, resolved, eventId);
        
        if (eventId != null) {
            // Search by specific event ID
            try {
                UUID eventUuid = UUID.fromString(eventId);
                return dlqRepository.findByEventId(eventUuid)
                    .stream()
                    .limit(size)
                    .toList();
            } catch (IllegalArgumentException e) {
                log.warn("Invalid UUID format: {}", eventId);
                return List.of();
            }
        }
        
        if (resolved != null) {
            return dlqRepository.findByResolved(resolved)
                .stream()
                .skip((long) page * size)
                .limit(size)
                .toList();
        }
        
        // Return all DLQ entries (paginated)
        return dlqRepository.findAll()
            .stream()
            .skip((long) page * size)
            .limit(size)
            .toList();
    }

    /**
     * Manually replay a specific DLQ entry
     */
    @PostMapping("/dlq/{dlqId}/replay")
    public DlqReplayResponse replayDlqEntry(@PathVariable UUID dlqId) {
        log.info("Admin: Manually replaying DLQ entry: id={}", dlqId);
        
        try {
            var dlqEntryOpt = dlqRepository.findById(dlqId);
            if (dlqEntryOpt.isEmpty()) {
                return DlqReplayResponse.error("DLQ entry not found: " + dlqId);
            }
            
            NotificationDlqEntry dlqEntry = dlqEntryOpt.get();
            
            if (Boolean.TRUE.equals(dlqEntry.getResolved())) {
                return DlqReplayResponse.error("DLQ entry already resolved: " + dlqId);
            }
            
            // Find the original outbox entry
            var outboxOpt = outboxRepository.findByNotificationEventId(dlqEntry.getEventId());
            if (outboxOpt.isEmpty()) {
                return DlqReplayResponse.error("Outbox entry not found for event: " + dlqEntry.getEventId());
            }
            
            NotificationOutbox outbox = outboxOpt.get();
            
            // Reset outbox to PENDING
            outbox.setStatus(OutboxStatus.PENDING);
            outbox.setErrorMessage("Manual replay triggered by admin");
            outboxRepository.save(outbox);
            
            // Mark DLQ entry as resolved
            dlqEntry.setResolved(true);
            dlqEntry.setErrorMessage("Manually replayed by admin");
            dlqRepository.save(dlqEntry);
            
            log.info("DLQ entry manually replayed: id={}, eventId={}", dlqId, dlqEntry.getEventId());
            
            return DlqReplayResponse.success("DLQ entry replayed successfully", dlqEntry.getEventId());
            
        } catch (Exception e) {
            log.error("Failed to replay DLQ entry: id={}", dlqId, e);
            return DlqReplayResponse.error("Replay failed: " + e.getMessage());
        }
    }

    /**
     * Batch replay multiple DLQ entries
     */
    @PostMapping("/dlq/batch-replay")
    public DlqBatchReplayResponse batchReplayDlqEntries(@RequestBody List<UUID> dlqIds) {
        log.info("Admin: Batch replaying {} DLQ entries", dlqIds.size());
        
        int success = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        
        for (UUID dlqId : dlqIds) {
            try {
                var dlqEntryOpt = dlqRepository.findById(dlqId);
                if (dlqEntryOpt.isEmpty()) {
                    failed++;
                    errors.add("DLQ entry not found: " + dlqId);
                    continue;
                }
                
                NotificationDlqEntry dlqEntry = dlqEntryOpt.get();
                
                if (Boolean.TRUE.equals(dlqEntry.getResolved())) {
                    failed++;
                    errors.add("Already resolved: " + dlqId);
                    continue;
                }
                
                var outboxOpt = outboxRepository.findByNotificationEventId(dlqEntry.getEventId());
                if (outboxOpt.isEmpty()) {
                    failed++;
                    errors.add("Outbox not found for event: " + dlqEntry.getEventId());
                    continue;
                }
                
                NotificationOutbox outbox = outboxOpt.get();
                outbox.setStatus(OutboxStatus.PENDING);
                outbox.setErrorMessage("Batch replay triggered by admin");
                outboxRepository.save(outbox);
                
                dlqEntry.setResolved(true);
                dlqEntry.setErrorMessage("Batch replayed by admin");
                dlqRepository.save(dlqEntry);
                
                success++;
                
            } catch (Exception e) {
                failed++;
                errors.add("Failed to replay " + dlqId + ": " + e.getMessage());
            }
        }
        
        log.info("Batch replay completed: success={}, failed={}", success, failed);
        return new DlqBatchReplayResponse(success, failed, errors);
    }

    /**
     * Auto-drain DLQ when system is healthy (success rate > threshold)
     */
    @PostMapping("/dlq/auto-drain")
    public DlqAutoDrainResponse autoDrainDlq(
            @RequestParam(defaultValue = "95.0") double successRateThreshold,
            @RequestParam(defaultValue = "50") int maxEntries) {
        
        log.info("Admin: Auto-draining DLQ if success rate > {}%", successRateThreshold);
        
        try {
            // Calculate current success rate
            long sent = idempotencyKeyRepository.countByStatus(DeliveryIdempotencyKey.DeliveryStatus.SENT);
            long failed = idempotencyKeyRepository.countByStatus(DeliveryIdempotencyKey.DeliveryStatus.FAILED);
            long total = sent + failed;
            
            if (total == 0) {
                return new DlqAutoDrainResponse(false, 0, "No delivery data available");
            }
            
            double successRate = (double) sent / total * 100;
            
            if (successRate < successRateThreshold) {
                return new DlqAutoDrainResponse(
                    false, 0,
                    String.format("Success rate %.1f%% is below threshold %.1f%% - aborting auto-drain",
                        successRate, successRateThreshold)
                );
            }
            
            // System is healthy - drain DLQ
            log.info("System healthy (success rate: %.1f%%) - draining DLQ", successRate);
            
            var dueEntries = dlqRepository.findDueForRetry(LocalDateTime.now(), org.springframework.data.domain.PageRequest.of(0, maxEntries))
                .stream()
                .toList();
            
            int drained = 0;
            for (NotificationDlqEntry entry : dueEntries) {
                try {
                    var outboxOpt = outboxRepository.findByNotificationEventId(entry.getEventId());
                    if (outboxOpt.isPresent()) {
                        NotificationOutbox outbox = outboxOpt.get();
                        outbox.setStatus(OutboxStatus.PENDING);
                        outbox.setErrorMessage("Auto-drained during healthy period");
                        outboxRepository.save(outbox);
                        
                        entry.setResolved(true);
                        entry.setErrorMessage("Auto-drained: success rate " + String.format("%.1f", successRate) + "%");
                        dlqRepository.save(entry);
                        
                        drained++;
                    }
                } catch (Exception e) {
                    log.error("Failed to auto-drain DLQ entry: id={}", entry.getId(), e);
                }
            }
            
            return new DlqAutoDrainResponse(true, drained, 
                String.format("Auto-drained %d entries (success rate: %.1f%%)", drained, successRate));
            
        } catch (Exception e) {
            log.error("Auto-drain failed", e);
            return new DlqAutoDrainResponse(false, 0, "Auto-drain failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Response DTOs
    // -----------------------------------------------------------------------
    
    public record DlqReplayResponse(boolean success, String message, UUID eventId, String error) {
        public static DlqReplayResponse success(String message, UUID eventId) {
            return new DlqReplayResponse(true, message, eventId, null);
        }
        public static DlqReplayResponse error(String error) {
            return new DlqReplayResponse(false, null, null, error);
        }
    }
    
    public record DlqBatchReplayResponse(int success, int failed, List<String> errors) {}
    
    public record DlqAutoDrainResponse(boolean success, int drained, String message) {}
    
    /**
     * Cleanup invalidated tokens (for ops team)
     */
    @DeleteMapping("/tokens/cleanup")
    public int cleanupInvalidatedTokens() {
        log.info("Admin: Cleaning up old invalidated tokens");
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);
        return deviceTokenRepository.deleteOldInvalidatedTokens(threshold);
    }
    
    /**
     * Trace a specific notification's delivery status
     * CRITICAL: This saves hours of debugging when users report missing notifications
     */
    @GetMapping("/{notificationId}/delivery")
    public NotificationDeliveryTraceDTO getNotificationDeliveryTrace(@PathVariable UUID notificationId) {
        log.info("Admin: Tracing delivery for notificationId={}", notificationId);
        
        NotificationDeliveryTraceDTO trace = new NotificationDeliveryTraceDTO();
        trace.setNotificationId(notificationId);
        
        // Get notification details
        try {
            java.util.Optional<com.arpay.entity.Notification> notificationOpt = notificationRepository.findById(notificationId);
            if (notificationOpt.isPresent()) {
                com.arpay.entity.Notification notif = notificationOpt.get();
                trace.setUserId(notif.getUserId());
                trace.setTitle(notif.getTitle());
                trace.setMessage(notif.getMessage());
                trace.setEntityType(notif.getEntityType());
                trace.setEntityId(notif.getEntityId());
                trace.setCreatedAt(notif.getCreatedAt());
            } else {
                trace.setError("Notification not found");
                return trace;
            }
        } catch (Exception e) {
            trace.setError("Failed to fetch notification: " + e.getMessage());
            return trace;
        }

        // Get delivery attempts
        java.util.Optional<com.arpay.entity.Notification> notificationForEventId = notificationRepository.findById(notificationId);
        var deliveryAttempts = idempotencyKeyRepository.findByNotificationEventId(
            notificationForEventId.map(n -> n.getNotificationEventId()).orElse(null)
        );
        
        trace.setDeliveryAttempts(deliveryAttempts.stream().map(key -> {
            NotificationDeliveryTraceDTO.DeliveryAttemptDTO attempt = 
                new NotificationDeliveryTraceDTO.DeliveryAttemptDTO();
            attempt.setDeviceTokenPrefix(maskToken(key.getDeviceToken()));
            attempt.setStatus(key.getStatus().name());
            attempt.setErrorCode(key.getFcmErrorCode());
            attempt.setErrorMessage(truncate(key.getErrorMessage(), 200));
            attempt.setAttemptCount(key.getAttemptCount());
            attempt.setCreatedAt(key.getCreatedAt());
            attempt.setCompletedAt(key.getCompletedAt());
            
            // Calculate latency if delivered
            if (key.getCompletedAt() != null && key.getCreatedAt() != null) {
                long latencyMs = java.time.Duration.between(key.getCreatedAt(), key.getCompletedAt()).toMillis();
                attempt.setLatencyMs(latencyMs);
            }
            
            return attempt;
        }).collect(java.util.stream.Collectors.toList()));
        
        // Calculate summary
        long sent = trace.getDeliveryAttempts().stream()
            .filter(a -> "SENT".equals(a.getStatus())).count();
        long failed = trace.getDeliveryAttempts().stream()
            .filter(a -> "FAILED".equals(a.getStatus()) || "PERMANENT_FAILURE".equals(a.getStatus())).count();
        
        trace.setSentCount((int) sent);
        trace.setFailedCount((int) failed);
        trace.setTotalAttempts(trace.getDeliveryAttempts().size());
        
        // Calculate average latency
        var latencies = trace.getDeliveryAttempts().stream()
            .filter(a -> a.getLatencyMs() != null)
            .mapToLong(NotificationDeliveryTraceDTO.DeliveryAttemptDTO::getLatencyMs)
            .toArray();

        if (latencies.length > 0) {
            trace.setAvgLatencyMs((long) java.util.Arrays.stream(latencies).average().orElse(0.0));
            java.util.Arrays.sort(latencies);
            int p95Index = (int) Math.floor(latencies.length * 0.95);
            trace.setP95LatencyMs(latencies[p95Index]);
        }
        
        return trace;
    }
}
