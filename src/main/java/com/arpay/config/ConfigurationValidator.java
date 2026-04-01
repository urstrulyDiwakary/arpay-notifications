package com.arpay.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Validates critical configuration at startup and exposes results as a
 * Spring Boot {@link HealthIndicator}.
 * <p>
 * <b>Design choice</b>: this validator intentionally does <em>not</em> throw
 * during {@code @PostConstruct}.  Throwing would kill the entire Spring
 * context, which means embedded Tomcat never binds its port and the Docker
 * HEALTHCHECK (and therefore Coolify) never gets a response — making it
 * impossible to read the real error in the deploy logs.
 * <p>
 * Instead, errors are:
 * <ol>
 *   <li>Logged prominently with 🔴 markers so they are visible in any log viewer.</li>
 *   <li>Exposed via {@code /actuator/health} → component {@code configValidation}
 *       returns {@code DOWN} with details when config is invalid.</li>
 *   <li>Included in the {@code readiness} health group, so Kubernetes / Coolify
 *       readiness probes correctly report the service as not-ready.</li>
 * </ol>
 * <p>
 * The <b>liveness</b> probe ({@code /actuator/health/liveness}) is unaffected,
 * keeping the container "alive" so you can SSH in and inspect logs.
 */
@Component
@Slf4j
public class ConfigurationValidator implements HealthIndicator {

    private static final List<String> INSECURE_JWT_SECRETS = List.of(
            "mySecretKeyForJWTTokenGenerationARPAY2025ThisIsAVeryLongSecretKey",
            "CHANGE_THIS_IN_PRODUCTION_USE_64_CHAR_RANDOM_STRING",
            "changeme",
            "secret"
    );

    private static final List<String> INSECURE_API_KEYS = List.of(
            "arpay-internal-key-change-in-production",
            "CHANGE_THIS_IN_PRODUCTION",
            "changeme"
    );

    private final Environment environment;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${internal.api.keys:}")
    private String internalApiKeys;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /** Validation errors detected at startup (empty = healthy). */
    @Getter
    private List<String> configErrors = Collections.emptyList();

    /** Validation warnings detected at startup. */
    @Getter
    private List<String> configWarnings = Collections.emptyList();

    public ConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    // ------------------------------------------------------------------
    // Startup validation (runs once)
    // ------------------------------------------------------------------

    @PostConstruct
    public void validate() {
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        boolean isProduction = activeProfiles.contains("production") || activeProfiles.contains("prod");

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // ── Startup diagnostics banner ─────────────────────────────────
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║          ARPAY NOTIFICATIONS — STARTUP DIAGNOSTICS          ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║ Active profiles  : {}", activeProfiles);
        log.info("║ Production mode  : {}", isProduction);
        log.info("║ JWT_SECRET       : {}", describeSecret(jwtSecret));
        log.info("║ INTERNAL_API_KEYS: {}", describeSecret(internalApiKeys));
        log.info("║ DB_PASSWORD      : {}", dbPassword.isBlank() ? "⚠ EMPTY" : "set (" + dbPassword.length() + " chars)");
        log.info("║ REDIS_PASSWORD   : {}", redisPassword.isBlank() ? "⚠ EMPTY" : "set (" + redisPassword.length() + " chars)");
        log.info("╚══════════════════════════════════════════════════════════════╝");

        // ── JWT secret check ───────────────────────────────────────────
        if (jwtSecret.isBlank()) {
            (isProduction ? errors : warnings).add("JWT_SECRET is not set");
        } else if (INSECURE_JWT_SECRETS.stream().anyMatch(jwtSecret::equalsIgnoreCase)) {
            (isProduction ? errors : warnings).add(
                    "JWT_SECRET is using a known default/placeholder value — set a unique 64+ char random string");
        } else if (jwtSecret.length() < 32) {
            warnings.add("JWT_SECRET is shorter than 32 characters — consider using 64+ for production");
        }

        // ── Internal API keys check ────────────────────────────────────
        if (internalApiKeys.isBlank()) {
            (isProduction ? errors : warnings).add("INTERNAL_API_KEYS is not set");
        } else if (INSECURE_API_KEYS.stream().anyMatch(k ->
                Arrays.stream(internalApiKeys.split(","))
                        .map(String::trim)
                        .anyMatch(k::equalsIgnoreCase))) {
            (isProduction ? errors : warnings).add("INTERNAL_API_KEYS contains a known default/placeholder value");
        }

        // ── Database password check ────────────────────────────────────
        if (dbPassword.isBlank() && isProduction) {
            errors.add("DB_PASSWORD / spring.datasource.password is not set");
        }

        // ── Redis password check ───────────────────────────────────────
        if (redisPassword.isBlank() && isProduction) {
            errors.add("REDIS_PASSWORD / spring.data.redis.password is not set");
        }

        // Report warnings
        for (String warning : warnings) {
            log.warn("⚠️  CONFIG WARNING: {}", warning);
        }

        // Report errors — log prominently but DO NOT throw.
        // The health indicator will report DOWN so readiness probes fail.
        if (!errors.isEmpty()) {
            log.error("╔══════════════════════════════════════════════════════════════╗");
            log.error("║  ❌  CONFIGURATION ERRORS — SERVICE WILL REPORT NOT READY   ║");
            log.error("╠══════════════════════════════════════════════════════════════╣");
            for (String error : errors) {
                log.error("║  🔴 {}", error);
            }
            log.error("╠══════════════════════════════════════════════════════════════╣");
            log.error("║  Fix the errors above and redeploy.                         ║");
            log.error("║  The app is running but /actuator/health will report DOWN.   ║");
            log.error("╚══════════════════════════════════════════════════════════════╝");
        }

        // Store results for the health indicator
        this.configErrors = List.copyOf(errors);
        this.configWarnings = List.copyOf(warnings);

        if (warnings.isEmpty() && errors.isEmpty()) {
            log.info("✅ Configuration validation passed (profile={})", activeProfiles);
        }
    }

    // ------------------------------------------------------------------
    // Health indicator (queried on every /actuator/health call)
    // ------------------------------------------------------------------

    @Override
    public Health health() {
        if (configErrors.isEmpty()) {
            Health.Builder builder = Health.up()
                    .withDetail("status", "Configuration valid");
            if (!configWarnings.isEmpty()) {
                builder.withDetail("warnings", configWarnings);
            }
            return builder.build();
        }
        return Health.down()
                .withDetail("errors", configErrors)
                .withDetail("warnings", configWarnings)
                .withDetail("action", "Fix the listed errors in environment variables and redeploy")
                .build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Describe a secret for logging: never print the value, only its shape. */
    private static String describeSecret(String value) {
        if (value == null || value.isBlank()) {
            return "⚠ EMPTY";
        }
        if (INSECURE_JWT_SECRETS.contains(value) || INSECURE_API_KEYS.stream().anyMatch(k ->
                Arrays.stream(value.split(",")).map(String::trim).anyMatch(k::equalsIgnoreCase))) {
            return "⚠ INSECURE DEFAULT (" + value.length() + " chars)";
        }
        return "set (" + value.length() + " chars, starts with '"
                + value.substring(0, Math.min(4, value.length())) + "…')";
    }
}

