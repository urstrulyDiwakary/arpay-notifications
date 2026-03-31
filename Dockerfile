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

# Copy Firebase service account key from builder stage (will be overridden by env var in Coolify)
COPY --from=builder --chown=appuser:appgroup /build/src/main/resources/firebase/ /app/firebase/

# Switch to non-root user (security best practice)
USER appuser

# Expose application port (Coolify expects 8080)
EXPOSE 8080

# Health check configuration
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1

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
