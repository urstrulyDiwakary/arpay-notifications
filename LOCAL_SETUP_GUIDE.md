# ARPAY Notifications - Local Development Setup Guide

## Quick Start (Local Development)

### Prerequisites
Ensure you have these running locally:
- **PostgreSQL 15+** on port 5432
- **Redis 7+** on port 6379 (no password for dev)
- **Backend API** on port 8080
- **Frontend** on port 5173 (optional for testing)

### Step 1: Copy Environment File
```bash
cd D:\intellij workspace\arpay-notifications
copy .env.example .env
```

Or use the pre-configured `.env` file that was just created with these values:
```env
DB_HOST=localhost
DB_PORT=5432
DB_NAME=ARPAY
DB_USERNAME=postgres
DB_PASSWORD=Razeesh@1

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

JWT_SECRET=mySecretKeyForJWTTokenGenerationARPAY2025ThisIsAVeryLongSecretKey
INTERNAL_API_KEYS=arpay-internal-key-change-in-production

FIREBASE_ENABLED=false
NOTIFICATIONS_RATELIMIT_ENABLED=false
NOTIFICATIONS_SIMPLE_MODE=true

CORS_ORIGINS=http://localhost:5173,http://localhost:5174,http://localhost:8080,http://localhost:8086
```

### Step 2: Start the Notifications Service

**Option A: Using Maven**
```bash
cd D:\intellij workspace\arpay-notifications
mvn spring-boot:run -DskipTests
```

**Option B: Using IntelliJ IDEA**
1. Open the `arpay-notifications` project in IntelliJ
2. Find `ArpayNotificationsApplication.java`
3. Right-click → Run 'ArpayNotificationsApplication'
4. Ensure "Environment Variables" includes the `.env` file values

### Step 3: Verify It's Running

Check the health endpoint:
```bash
curl http://localhost:8086/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

Check logs for:
```
Notification async executor initialised
Redis configured: host=localhost, port=6379
ArpayNotificationsApplication started
```

---

## Common Issues & Solutions

### Issue 1: Redis Connection Failed
**Error:** `Cannot get connection` or `Redis connection failed`

**Solution:**
```bash
# Start Redis locally
# Windows (if using Docker)
docker run -d -p 6379:6379 --name arpay-redis redis:7-alpine

# Or install Redis locally and start:
redis-server
```

### Issue 2: Database Connection Failed
**Error:** `Connection to localhost:5432 refused` or `Authentication failed`

**Solution:**
1. Ensure PostgreSQL is running:
   ```bash
   # Check if PostgreSQL is running
   pg_isready -h localhost -p 5432
   ```

2. Verify database exists:
   ```sql
   psql -U postgres -d ARPAY -c "\dt"
   ```

3. Check credentials in `.env` match your PostgreSQL setup

### Issue 3: Backend Cannot Reach Notifications Service
**Error in backend logs:** `Connection refused to localhost:8086`

**Solution:**
1. Ensure notifications service is running on port 8086
2. Check backend's `application.properties`:
   ```properties
   arpay.notifications.base-url=http://localhost:8086/api
   notifications.external.enabled=true
   notifications.external.api-key=arpay-internal-key-change-in-production
   ```

3. Verify API key matches in both services:
   - Backend: `notifications.external.api-key`
   - Notifications: `INTERNAL_API_KEYS`

### Issue 4: Firebase Errors (in logs)
**Error:** `FirebaseApp not initialized` or `Firebase service account not found`

**Solution:**
For local development, disable Firebase:
```env
FIREBASE_ENABLED=false
```

This is already set in the `.env` file for local development.

### Issue 5: CORS Errors from Frontend
**Error:** `Access-Control-Allow-Origin` header missing

**Solution:**
Ensure `.env` has:
```env
CORS_ORIGINS=http://localhost:5173,http://localhost:5174,http://localhost:8080,http://localhost:8086
```

### Issue 6: Database Tables Missing
**Error:** `Table 'notification' doesn't exist` or `relation does not exist`

**Solution:**
The notifications service uses Flyway migrations. Tables should be created automatically on startup.

Check Flyway is running in logs:
```
Flyway migration started
Executing SQL script: V1__add_device_tokens_and_outbox_tables.sql
```

If tables are missing, verify:
1. `spring.flyway.enabled=true` in `application.properties`
2. Database user has CREATE TABLE permissions
3. Check `src/main/resources/db/migration/` for migration files

---

## Testing the Integration

### Test 1: Send Notification via Backend API
```bash
# First, get a JWT token from backend
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@arpay.com","password":"admin123"}'

# Use the token to create a notification
curl -X POST http://localhost:8080/api/notifications/send \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "USER_UUID_HERE",
    "title": "Test Notification",
    "message": "This is a test",
    "entityType": "PAYMENT",
    "entityId": "ENTITY_UUID"
  }'
```

### Test 2: Direct Notification Creation
```bash
curl -X POST http://localhost:8086/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "USER_UUID_HERE",
    "title": "Direct Test",
    "message": "Direct notification test",
    "severity": "NORMAL",
    "type": "INFO"
  }'
```

### Test 3: Get User Notifications
```bash
curl "http://localhost:8086/api/notifications?userId=USER_UUID_HERE&page=0&size=10"
```

### Test 4: Get Unread Count
```bash
curl "http://localhost:8086/api/notifications/unread-count?userId=USER_UUID_HERE"
```

---

## Production Readiness Checklist

### Configuration
- [ ] Set strong `JWT_SECRET` (64+ characters)
- [ ] Set strong `INTERNAL_API_KEYS` (match with backend)
- [ ] Set strong `DB_PASSWORD` and update backend config
- [ ] Set strong `REDIS_PASSWORD` and update backend config
- [ ] Enable Firebase: `FIREBASE_ENABLED=true`
- [ ] Enable rate limiting: `NOTIFICATIONS_RATELIMIT_ENABLED=true`
- [ ] Update `CORS_ORIGINS` to production domains only
- [ ] Set `ARPAY_BACKEND_URL=https://api.arpay.anantarealty.in`

### Infrastructure
- [ ] PostgreSQL database with proper credentials
- [ ] Redis with password authentication
- [ ] Firebase service account JSON file at `/app/firebase/firebase-service-account.json`
- [ ] SSL certificates for HTTPS (via Nginx or Let's Encrypt)
- [ ] Nginx reverse proxy configured (see `nginx/nginx.conf`)

### Database Migrations
- [ ] Flyway migrations executed successfully (V1-V5)
- [ ] All tables created: `notification`, `notification_outbox`, `user_device_token`, etc.
- [ ] Indexes created for performance

### Monitoring
- [ ] Health check endpoint responding: `/actuator/health`
- [ ] Prometheus metrics available: `/actuator/prometheus`
- [ ] Log aggregation configured (JSON logs in production)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│              Frontend (arpay.anantarealty.in)           │
└────────────────────┬────────────────────────────────────┘
                     │ HTTPS
                     ▼
┌─────────────────────────────────────────────────────────┐
│         Backend API (api.arpay.anantarealty.in)         │
│  - Receives business events (payments, approvals, etc.) │
│  - Writes to notification_outbox table                  │
│  - Calls notifications service via REST                 │
└────────────────────┬────────────────────────────────────┘
                     │ HTTP (internal)
                     │ X-API-Key: arpay-internal-key...
                     ▼
┌─────────────────────────────────────────────────────────┐
│      Notifications (notify.arpay.anantarealty.in)       │
│  - Validates API key                                    │
│  - Creates notification record                          │
│  - Pushes to Firebase Cloud Messaging                   │
│  - Tracks delivery status                               │
└────────────────────┬────────────────────────────────────┘
                     │ HTTPS
                     ▼
┌─────────────────────────────────────────────────────────┐
│          Firebase Cloud Messaging (FCM)                 │
│  - Delivers push to user's device                       │
└─────────────────────────────────────────────────────────┘
```

---

## Logs to Monitor

### Backend Logs (when sending notification)
```
Notification async executor initialised
RestClient configured for notifications service at http://localhost:8086/api
Notification dispatched to userId=xxx
```

### Notifications Service Logs (when receiving)
```
ArpayNotificationsApplication started
Redis configured: host=localhost, port=6379
API key validated for endpoint: POST /api/notifications/send/user
Notification created: id=xxx, status=SENT
Firebase push sent to userId=xxx
```

### Error Patterns to Watch
```
WARN  Missing API key for protected endpoint  → Backend API key mismatch
ERROR Connection to Redis failed            → Redis not running
ERROR Cannot get connection to PostgreSQL   → Database not running
WARN  Firebase push failed                  → FCM not configured (OK for local dev)
```

---

## Support

If you're still having issues:
1. Check application logs for specific error messages
2. Verify all prerequisites are running (PostgreSQL, Redis)
3. Ensure `.env` file has correct values
4. Test each service independently before testing integration

**Quick Health Check Commands:**
```bash
# Backend health
curl http://localhost:8080/actuator/health

# Notifications health
curl http://localhost:8086/actuator/health

# PostgreSQL
pg_isready -h localhost -p 5432

# Redis
redis-cli ping
```
