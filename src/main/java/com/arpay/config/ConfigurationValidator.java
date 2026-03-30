package com.arpay.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Validates critical configuration at startup.
 * <p>
 * In production profiles, this validator will FAIL FAST if:
 * - JWT secret is a known default / placeholder
 * - Internal API keys are a known default / placeholder
 * - Database password is missing
 * - Redis password is missing
 */
@Component
@Slf4j
public class ConfigurationValidator {

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

    public ConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        boolean isProduction = activeProfiles.contains("production") || activeProfiles.contains("prod");

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // JWT secret check
        if (jwtSecret.isBlank()) {
            (isProduction ? errors : warnings).add("JWT_SECRET is not set");
        } else if (INSECURE_JWT_SECRETS.stream().anyMatch(jwtSecret::equalsIgnoreCase)) {
            (isProduction ? errors : warnings).add("JWT_SECRET is using a known default/placeholder value — set a unique 64+ char random string");
        } else if (jwtSecret.length() < 32) {
            warnings.add("JWT_SECRET is shorter than 32 characters — consider using 64+ for production");
        }

        // Internal API keys check
        if (internalApiKeys.isBlank()) {
            (isProduction ? errors : warnings).add("INTERNAL_API_KEYS is not set");
        } else if (INSECURE_API_KEYS.stream().anyMatch(k ->
                Arrays.stream(internalApiKeys.split(","))
                        .map(String::trim)
                        .anyMatch(k::equalsIgnoreCase))) {
            (isProduction ? errors : warnings).add("INTERNAL_API_KEYS contains a known default/placeholder value");
        }

        // Database password check
        if (dbPassword.isBlank() && isProduction) {
            errors.add("DB_PASSWORD / spring.datasource.password is not set");
        }

        // Redis password check (empty is OK for local dev, not for production)
        if (redisPassword.isBlank() && isProduction) {
            errors.add("REDIS_PASSWORD / spring.data.redis.password is not set");
        }

        // Report warnings
        for (String warning : warnings) {
            log.warn("⚠️  CONFIG WARNING: {}", warning);
        }

        // Report errors — fail fast in production
        if (!errors.isEmpty()) {
            for (String error : errors) {
                log.error("🔴 CONFIG ERROR: {}", error);
            }
            throw new IllegalStateException(
                    "Application startup blocked — " + errors.size() + " critical configuration error(s) detected. " +
                    "Fix the errors above before deploying to production.");
        }

        if (warnings.isEmpty() && errors.isEmpty()) {
            log.info("✅ Configuration validation passed (profile={})", activeProfiles);
        }
    }
}

