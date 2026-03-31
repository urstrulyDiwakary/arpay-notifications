package com.arpay.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration for the outbox recovery subsystem.
 *
 * <p>Bound from the {@code notifications.recovery.*} prefix in application.properties.
 * Registered as a bean by {@code @ConfigurationPropertiesScan} on
 * {@link com.arpay.ArpayNotificationsApplication}; no {@code @Component} needed.
 */
@ConfigurationProperties(prefix = "notifications.recovery")
@Getter
@Setter
public class RecoveryProperties {

    /**
     * Whether outbox startup recovery is active.
     * Recovers PENDING/QUEUED entries left over from a previous JVM crash.
     */
    private boolean enabled = true;

    /**
     * Minutes after which a QUEUED outbox entry is considered "stuck"
     * and eligible for re-dispatch on startup.
     */
    private int stuckMinutes = 5;

    /**
     * Maximum number of stuck outbox entries to re-dispatch per startup recovery pass.
     */
    private int batchSize = 200;

    /**
     * Seconds after which a QUEUED outbox entry is considered stuck
     * for the periodic (non-startup) recovery scheduler.
     */
    private int stuckSeconds = 120;

    /**
     * Interval in milliseconds between periodic recovery scheduler runs.
     */
    private long checkIntervalMs = 5000;
}
