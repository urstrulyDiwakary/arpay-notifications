# =============================================================================
# ARPAY Notifications Microservice - Production Dockerfile
# Multi-stage build with security best practices
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

# Create directories for SSL certificates and logs
RUN mkdir -p /app/certs /app/logs && \
    chown -R appuser:appgroup /app

# Copy SSL certificates if they exist (optional)
COPY --chown=appuser:appgroup certs/* /app/certs/ 2>/dev/null || true

# Copy Firebase service account key (will be overridden by volume mount in production)
COPY --chown=appuser:appgroup src/main/resources/firebase/* /app/firebase/ 2>/dev/null || true

# Switch to non-root user (security best practice)
USER appuser

# Expose application port
EXPOSE 8086

# Health check configuration
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8086/actuator/health || exit 1

# JVM security and performance options
# -XX:+UseContainerResource: Respect Docker memory limits
# -XX:MaxRAMPercentage: Use percentage of available memory (safer than fixed -Xmx)
# -XX:+UseG1GC: Modern garbage collector for better performance
# -XX:MaxGCPauseMillis: Target max GC pause time
# -XX:+HeapDumpOnOutOfMemoryError: Generate heap dump on OOM for debugging
# -XX:HeapDumpPath: Where to store heap dumps
# -Djava.security.egd: Faster entropy source for non-blocking random
# -Dserver.port: Application port
# -Dspring.profiles.active: Active Spring profile

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerResource \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/app/logs/heap_dump.hprof \
    -Djava.security.egd=file:/dev/./urandom \
    -Dserver.port=8086 \
    -Dspring.profiles.active=production"

# Entry point with graceful shutdown support
ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar app.jar"]
