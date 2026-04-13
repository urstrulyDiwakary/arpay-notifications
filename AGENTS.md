# AGENTS.md - AI Coding Agent Guidelines for ARPAY Notifications Microservice

## Quick Architecture Overview

**ARPAY Notifications** is a production-grade Spring Boot 3 microservice for multi-channel notifications (FCM push, email, WebSocket). It's designed for high-throughput, reliable delivery with priority queues, idempotency, and circuit breakers.

### Core Components
- **Controllers** (`controller/`): REST endpoints — NotificationController, NotificationAdminController (health dashboard, DLQ management, delivery tracing), DeviceTokenController, DeviceTokenRefreshController (token drift prevention)
- **Services** (`service/`): Business logic — NotificationService, AlertService (Slack webhook integration), InternalAuthService, plus observability: ActiveMonitoringService, HardLimitsAlertingService, QueueMetricsService (Prometheus gauges), TimeToRecoveryService (SRE overload tracking)
- **Workers** (`worker/`): Async processors — NotificationWorker, NotificationEventListener, DLQRetryScheduler, ScheduledNotificationDispatcher, BackpressureMonitor (dynamic rate-limit adjustment), OutboxStartupRecovery (recovers stuck entries on JVM restart)
- **Entities** (`entity/`): JPA models — Notification, NotificationOutbox, OutboxStatus (enum: PENDING→QUEUED→PUBLISHED/FAILED/DEAD_LETTER), UserDeviceToken, User, DeliveryIdempotencyKey, NotificationDlqEntry, NotificationDeliveryLog, NotificationEngagementLog, NotificationEventState
- **Repositories** (`repository/`): Spring Data JPA for database access
- **Filters** (`filter/`): InternalAuthFilter (API key validation), PriorityRejectionFilter (priority-based load shedding under queue pressure), RateLimitFilter (rate limiting via Guava with backpressure adjustment)
- **Config** (`config/`): AsyncConfig (multi-level thread pools), SecurityConfig (includes CORS), FirebaseConfig, RedisConfig, JpaConfig (JdbcTemplate + transaction management), JsonConfig (Jackson with Java 8 time), ConfigurationValidator (startup config health check exposed via `/actuator/health`), RecoveryProperties (`@ConfigurationProperties` for outbox recovery)
- **Notification** (`notification/`): Channel providers — FirebasePushService, DeduplicationService (Redis SETNX atomic dedup), UnreadCountCacheService (Redis unread count cache), PushResult (FCM result model)

### Data Flow
1. **Notification Creation** → API receives POST request
2. **Atomic Write** → Notification + NotificationOutbox (transactional outbox pattern) written in single transaction
3. **Event Publishing** → NotificationCreatedEvent published AFTER transaction commits
4. **Worker Processing** → NotificationEventListener async handler picks up event and claims outbox entry via optimistic locking
5. **Push Delivery** → NotificationWorker processes claimed entries, checks idempotency, sends to Firebase
6. **DLQ on Failure** → Failed entries moved to DLQ; DLQRetryScheduler retries periodically

---

## Essential Patterns & Conventions

### Transactional Outbox Pattern (Critical)
Located in `worker/NotificationEventListener.java` and `worker/NotificationWorker.java`.

**Why**: Prevents message loss when database writes succeed but queue publishing fails.

**How it works**:
1. Notification and NotificationOutbox created atomically in one transaction
2. Event published AFTER transaction commits (`@TransactionalEventListener(phase = AFTER_COMMIT)`)
3. Worker claims outbox entry via optimistic locking (prevents duplicate processing)
4. Entry marked PUBLISHED only after successful delivery
5. If worker crashes between claim and delivery, DB entry retained for retry

**When modifying**: Always ensure NotificationOutbox is created with Notification; never publish to queue without outbox entry.

### Idempotent Delivery
Located in `entity/DeliveryIdempotencyKey.java` and `worker/NotificationWorker.java` (lines ~150-200).

**How it works**:
- Device token + notification ID + content hash = unique idempotency key
- Key checked BEFORE Firebase call; if exists, skip delivery
- Handles worker crashes mid-delivery by refusing duplicate sends

**When modifying**: Check idempotency key before any external API calls (Firebase, email service).

### Priority-Based Async Processing
Located in `config/AsyncConfig.java`.

**Thread Pools**:
- `criticalNotificationExecutor` (5 threads, queue 500): Critical alerts
- `normalNotificationExecutor` (10 threads, queue 1000): Standard notifications
- `lowNotificationExecutor` (3 threads, queue 300): Batch/low-priority

**When modifying**: Use `@Async("criticalNotificationExecutor")` or respective executor name. Configure pool sizes in `application.properties` via `notification.worker.*-threads` and `notification.worker.queue-capacity`.

### Rate Limiting
Located in `filter/RateLimitFilter.java` using Guava's RateLimiter.

**Levels**: Global (1000 req/s), per-endpoint (100 req/s), per-client (10 req/s). Configured via `application.properties` `notifications.ratelimit.*` properties.

**When modifying**: Add new rate limit rules in filter; maintain backward compatibility with existing clients.

### Backpressure & Priority-Based Load Shedding
Located in `worker/BackpressureMonitor.java` and `filter/PriorityRejectionFilter.java`.

**How it works**:
- `BackpressureMonitor` runs every 5s, samples outbox queue depth (PENDING + QUEUED), computes a pressure ratio (0.0–1.0), and dynamically scales down the global rate limiter via `RateLimitFilter.adjustForBackpressure(ratio)`. Fails open (no throttle on error).
- `PriorityRejectionFilter` (`@Order(1)`, runs before RateLimitFilter) implements graceful degradation on POST `/api/notifications/*/send*`:
  - Queue >70%: reject LOW priority
  - Queue >85%: reject LOW + NORMAL
  - Queue >90%: reject all except CRITICAL
  - Priority determined from `X-Priority` header or `X-Entity-Type` header (PAYMENT/FRAUD→CRITICAL, APPROVAL→NORMAL, MARKETING→LOW; default NORMAL)

**When modifying**: Threshold percentages are configurable via `notifications.priority.rejection.*` properties. Always allow CRITICAL through.

### Outbox Startup Recovery
Located in `worker/OutboxStartupRecovery.java` (implements `ApplicationRunner`).

**How it works**: On JVM startup, finds outbox entries stuck in PENDING (never dispatched) or QUEUED (dispatched but never completed due to crash), and re-dispatches them via `NotificationWorker.processOutboxEntry()`. Safe because delivery is idempotent via `DeliveryIdempotencyKey`.

**Configuration**: `notifications.recovery.*` properties (enabled, stuck-minutes, batch-size) bound to `config/RecoveryProperties.java`.

### Internal API Authentication
Located in `filter/InternalAuthFilter.java` and `service/InternalAuthService.java`.

**How it works**: X-API-Key header validated against `INTERNAL_API_KEYS` (comma-separated hex strings in env). Protects write operations; reads permitted.

**When modifying**: Only protect POST/PUT/PATCH/DELETE; GET/HEAD remain open.

---

## Build & Development Workflow

### Local Build
```bash
# From project root (where pom.xml lives)
mvn clean package -DskipTests
```

### Local Run with Docker Compose
```bash
# From project root
docker compose --profile standalone up -d
# App on http://localhost:8086
# Check health: http://localhost:8086/actuator/health
```

### Local Run Direct (Maven)
```powershell
# Set environment variables (or use the convenience script)
.\set-dev-env.ps1

mvn spring-boot:run
```

### Test Notifications (from workspace root)
```powershell
.\test-notifications.ps1  # Runs API tests against http://localhost:8086
```

### Key Maven Profiles
- Default: Development profile (uses application.properties)
- `production`: Production hardened config (from application-production.properties)

### Database Migrations
Located in `src/main/resources/db/migration/` (Flyway convention).
- V1: Device tokens and outbox tables
- V2: Performance indexes
- V3: Delivery idempotency keys
- V4: Scheduled notifications support
- V5: QUEUED outbox status, DLQ exponential backoff (`next_retry_at`), recovery indexes

---

## Critical Debugging & Monitoring

### Health Endpoints
- `/actuator/health` - Overall health
- `/actuator/health/liveness` - Kubernetes liveness probe
- `/actuator/health/readiness` - Kubernetes readiness probe
- `/actuator/prometheus` - Prometheus metrics

### Key Metrics to Watch
- `notification_queue_size` (critical, normal, low) - Queue depth; backpressure triggers if exceeds max-queue-depth
- `notification_dlq_size` - Failed notifications; DLQRetryScheduler runs every 5 minutes
- `firebase_circuit_breaker_state` - Open/Half-Open/Closed; circuit opens after 5 consecutive failures
- `in_flight_notifications` - Currently processing; should not exceed `notification.worker.max-inflight`
- JVM memory/GC metrics via Micrometer

### Common Issues & Remediation
| Issue | Root Cause | Fix |
|-------|-----------|-----|
| "Queue full" warnings | Worker throughput < ingestion rate | Increase `*-threads` pool size; check Firebase latency |
| High DLQ count | Network/Firebase issues | Check circuit breaker status; verify Firebase config; retry via admin endpoint |
| Duplicate notifications | Worker crash between idempotency check and delivery | Normal (at-least-once semantics); idempotency key prevents re-delivery |
| Memory spike | Large batch creation | Check application.properties `spring.jpa.properties.hibernate.jdbc.batch_size` (default 50) |

---

## Configuration Hierarchy

**Environment Variable > application-{profile}.properties > application.properties**

### Key Properties
```properties
# Thread pools (tune for throughput/latency tradeoff)
notification.worker.critical-threads=5
notification.worker.normal-threads=10
notification.worker.max-inflight=200

# Retry behavior
notification.retry.max-attempts=3
notification.dlq.retry-interval-minutes=5

# Rate limits (per-second) — note: prefix is notifications.ratelimit.*
notifications.ratelimit.global-permits-per-second=1000
notifications.ratelimit.per-endpoint-permits-per-second=100
notifications.ratelimit.per-client-permits-per-second=10

# Backpressure & priority rejection
notifications.worker.backpressure.max-queue-depth=10000
notifications.priority.rejection.enabled=true
notifications.priority.rejection.low-threshold-percent=70
notifications.priority.rejection.normal-threshold-percent=85

# Startup recovery
notifications.recovery.enabled=true
notifications.recovery.stuck-minutes=5
notifications.recovery.batch-size=200

# Firebase
firebase.enabled=true
firebase.service-account-key-path=classpath:firebase/firebase-service-account.json

# Database
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.open-in-view=false
```

---

## Code Organization Rules

### Package Structure
- **`config/`**: Spring configurations (Async, Security, Redis, Firebase)
- **`controller/`**: HTTP endpoints (NotificationController, AdminController)
- **`service/`**: Business logic interfaces and implementations
- **`service/impl/`**: Service implementations
- **`worker/`**: Async workers and event listeners (NOT in service package)
- **`entity/`**: JPA entities (follows Spring Data convention)
- **`repository/`**: Spring Data JPA repositories
- **`dto/`**: Data transfer objects for API payloads
- **`filter/`**: Servlet filters (Auth, Rate Limiting)
- **`event/`**: Spring application events (NotificationCreatedEvent)
- **`notification/`**: Notification channel providers (FirebasePushService, etc.)

### Naming Conventions
- Controllers: `*Controller` (e.g., NotificationController)
- Services: `*Service` interface + `*ServiceImpl` implementation (unless single implementation)
- Entities: PascalCase (e.g., Notification, UserDeviceToken)
- Repositories: `*Repository` extending JpaRepository
- DTOs: `*DTO` (e.g., NotificationDTO, PageResponse<T>)
- Events: `*Event` (e.g., NotificationCreatedEvent)

---

## Deployment & Infrastructure

### Docker & Coolify
- Multi-stage build in `Dockerfile` (Maven builder + JRE-alpine runtime; uses .dockerignore to exclude unnecessary files)
- Environment-based configuration via `.env` file
- Healthcheck configured for container orchestration (liveness endpoint on port 8086)
- Non-root user `appuser` for security
- Docker Compose profiles: `standalone` (local PostgreSQL + Redis), `monitoring` (Prometheus + Grafana), `with-nginx`

### Required Secrets (Environment Variables)
```bash
DB_PASSWORD           # PostgreSQL password (64+ chars recommended)
REDIS_PASSWORD        # Redis password
JWT_SECRET            # JWT signing secret (64+ chars)
INTERNAL_API_KEYS     # Comma-separated API keys for internal auth
FIREBASE_ENABLED      # true/false
```

### Deployment Via Coolify
1. Push code to GitHub
2. In Coolify: Add repository → Configure env vars → Deploy
3. Coolify automatically handles Docker build, SSL, reverse proxy

---

## External Dependencies & Integration Points

### Primary Dependencies
- **Spring Boot 3.4** (3.4.5) - Web framework and core (Java 21)
- **PostgreSQL 16+** - Persistent storage (JPA/Hibernate)
- **Redis 7+** - Queue and cache (Spring Data Redis + Lettuce pool)
- **Firebase Admin SDK 9.8** - FCM push notifications
- **Guava 33** - Rate limiting (RateLimiter)
- **Micrometer + Prometheus** - Metrics and observability
- **JJWT 0.12** - JWT token handling
- **Logstash Logback Encoder** - Structured JSON logging in production
- **Lombok** - Code generation (getters, constructors, logging)
- **Spotless / PMD** - Code formatting enforcement and static analysis (Maven plugins)

### Integration Points
- **Backend Service** - Calls `/api/notifications/send/*` endpoints to trigger notifications
- **Frontend** - Calls `/api/notifications` to fetch user notifications; WebSocket support for real-time
- **Firebase Cloud Messaging** - Sends push to mobile apps via device tokens
- **Email Service** (future) - SMTP configuration in application.properties

### Security Assumptions
- Only backend service has valid `INTERNAL_API_KEYS`
- Public endpoints (device tokens, read notifications) are open; POST/DELETE require API key
- CORS restricted to specific frontend domains
- PostgreSQL and Redis are in private network (not internet-exposed)

---

## For New Features

### Adding a New Notification Type
1. Create DTOs in `dto/` package
2. Create controller endpoint in `controller/NotificationController.java`
3. Update `NotificationService` interface and implementation
4. Add entity fields to `Notification` if schema change needed
5. Create migration script in `db/migration/V{N}__*.sql`
6. Test with `test-notifications.ps1`

### Adding a New Channel (e.g., SMS)
1. Create `notification/SMSService.java` similar to `FirebasePushService`
2. Add logic to `NotificationWorker.processOutboxEntry()` to route to SMS handler
3. Update configuration with SMS provider credentials
4. Add retry/circuit-breaker logic for reliability

### Performance Tuning
1. Monitor metrics via `/actuator/prometheus`
2. Increase thread pool sizes if queue depth > 50% of capacity
3. Adjust batch size (`hibernate.jdbc.batch_size`) if memory-constrained
4. Add database indexes for frequently queried fields (already done for outbox status, event type)

---

## Useful References
- **README.md**: Feature overview, quick start, deployment steps
- **LOCAL_SETUP_GUIDE.md**: Local development setup instructions
- **docker-compose.yml**: Local development stack with PostgreSQL, Redis, Prometheus, Grafana
- **set-dev-env.ps1**: Convenience script to set all dev environment variables
- **test-notifications.ps1**: API integration tests against local instance
- **AsyncConfig.java**: Thread pool configuration and tuning
- **NotificationWorker.java**: Core processing logic with idempotency
- **InternalAuthFilter.java**: API key validation for internal endpoints
- **BackpressureMonitor.java**: Dynamic rate-limit adjustment based on queue depth
- **ConfigurationValidator.java**: Startup config validation and health indicator
- **logback-spring.xml**: Profile-based logging (colored console in dev, JSON in prod)

