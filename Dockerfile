# =============================================================================
# ARPAY Notifications Microservice - Production Dockerfile
# Multi-stage build with security best practices
# Optimised for Coolify deployment
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Build Stage
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy Maven configuration first for better layer caching
COPY pom.xml .

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# -----------------------------------------------------------------------------
# Stage 2: Production Runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# Labels for documentation
LABEL maintainer="Arpay Team"
LABEL description="Arpay Notifications Microservice"
LABEL version="1.0.0"

# Create non-root user for security (critical for production)
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

# Copy the built jar from builder stage
COPY --from=builder --chown=appuser:appgroup /build/target/arpay-notifications-0.0.1-SNAPSHOT.jar app.jar

# Create directories for SSL certificates, Firebase config, and logs
RUN mkdir -p /app/certs /app/logs /app/firebase && \
    chown -R appuser:appgroup /app

# NOTE: SSL certificates are mounted at runtime via Coolify/Docker volumes into /app/certs

# NOTE: Firebase service account key is NOT bundled in the image (it's in .gitignore).
# It should be provided at runtime via:
#   - Environment variable (FIREBASE_SERVICE_ACCOUNT_JSON)
#   - Volume mount into /app/firebase/
#   - Coolify secret file

# =============================================================================
# ⚠️  SECURITY: NO build-time ARGs for secrets.
# Secrets (JWT_SECRET, DB_PASSWORD, INTERNAL_API_KEYS, etc.) must be injected
# as RUNTIME environment variables via Coolify's "Environment Variables" panel,
# NOT as "Build Variables". Build-time ARG values are visible in `docker history`
# and in plain-text build logs — they get baked into the image layer cache.
# =============================================================================

# Switch to non-root user (security best practice)
USER appuser

# Expose application port.
# ⚠️  This is ALWAYS 8086. Do NOT override SERVER_PORT to a different value in
#     Coolify; the port mapping and healthcheck are fixed to 8086. Setting
#     SERVER_PORT=8080 (or any other value) will cause the app to listen on the
#     wrong port, breaking healthchecks and routing with a 503 error.
EXPOSE 8086

# Health check — port is intentionally hardcoded to 8086 (matches EXPOSE above).
# Do NOT use ${SERVER_PORT} here: if Coolify overrides SERVER_PORT the healthcheck
# would chase the wrong port while the app still binds on 8086.
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8086/actuator/health || exit 1

# JVM security and performance options
# -XX:+UseContainerSupport: Respect Docker memory/CPU limits
# -XX:MaxRAMPercentage: Use percentage of available memory (safer than fixed -Xmx)
# -XX:+UseG1GC: Modern garbage collector for better performance
# -XX:MaxGCPauseMillis: Target max GC pause time
# -XX:+HeapDumpOnOutOfMemoryError: Generate heap dump on OOM for debugging
# -XX:HeapDumpPath: Where to store heap dumps
# -Djava.security.egd: Faster entropy source for non-blocking random
#
# NOTE: server.port and spring.profiles.active are now controlled via
#       environment variables (SERVER_PORT, SPRING_PROFILES_ACTIVE) set in
#       Coolify, so they are NOT hardcoded here.

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/app/logs/heap_dump.hprof \
    -Djava.security.egd=file:/dev/./urandom"

# Entry point with graceful shutdown support
ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar app.jar"]
