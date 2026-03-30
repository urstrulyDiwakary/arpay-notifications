# ARPAY Notifications Microservice - Production Ready

[![CI/CD Pipeline](https://github.com/arpay/arpay-notifications/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/arpay/arpay-notifications/actions/workflows/ci-cd.yml)
[![Security Scan](https://github.com/arpay/arpay-notifications/actions/workflows/security-scan.yml/badge.svg)](https://github.com/arpay/arpay-notifications/actions/workflows/security-scan.yml)
[![Docker Pulls](https://img.shields.io/docker/pulls/arpay/arpay-notifications)](https://hub.docker.com/r/arpay/arpay-notifications)
[![License](https://img.shields.io/badge/license-Proprietary-blue.svg)](LICENSE)

A production-ready, scalable notification microservice for the Arpay platform. Built with Spring Boot 3, PostgreSQL, Redis, and Firebase Cloud Messaging.

---

## 🚀 Features

### Core Capabilities
- **Multi-Channel Notifications**: Push (FCM), Email, WebSocket support
- **Priority Queues**: Critical, Normal, Low priority with separate thread pools
- **Dead Letter Queue (DLQ)**: Failed notifications with auto-retry
- **Rate Limiting**: Global, per-endpoint, per-client rate limiting
- **Idempotency**: Delivery idempotency keys to prevent duplicates
- **Transactional Outbox**: Reliable event publishing pattern
- **Circuit Breaker**: Push notification circuit breaker
- **Scheduled Notifications**: Support for delayed/scheduled delivery

### Production Features
- **Docker Containerized**: Multi-stage build with security best practices
- **Coolify Ready**: One-click deployment via Coolify
- **Health Checks**: Comprehensive liveness and readiness probes
- **Metrics & Monitoring**: Prometheus metrics via Actuator
- **Structured Logging**: JSON logging for production
- **SSL/TLS**: HTTPS with Let's Encrypt
- **Security Hardened**: OWASP Top 10 mitigations

---

## 📋 Prerequisites

| Component | Version | Required |
|-----------|---------|----------|
| Java | 21+ | ✅ |
| Docker | 24+ | ✅ |
| PostgreSQL | 16+ | ✅ |
| Redis | 7+ | ✅ |
| Coolify | Latest | Optional |

---

## 🛠️ Quick Start

### Local Development

```bash
# Clone repository
git clone https://github.com/arpay/arpay-notifications.git
cd arpay-notifications

# Copy environment template
cp .env.example .env

# Edit .env with your values
# Required: DB_PASSWORD, REDIS_PASSWORD, JWT_SECRET, INTERNAL_API_KEYS

# Start all services
docker compose up -d

# View logs
docker compose logs -f app

# Access application
open http://localhost:8086/actuator/health
```

### Generate Secrets

**Linux/Mac:**
```bash
chmod +x scripts/generate-secrets.sh
./scripts/generate-secrets.sh --save
```

**Windows (PowerShell):**
```powershell
.\scripts\generate-secrets.ps1 -Save
```

---

## 📦 Deployment

### Deploy to Hostinger VPS via Coolify

**1. Set up Hostinger VPS:**
- Create VPS instance (KVM 3 or higher, 4GB+ RAM)
- Install Ubuntu 22.04 LTS or 24.04 LTS
- Configure firewall (allow ports 22, 80, 443, 3000)

**2. Install Coolify:**
```bash
curl -fsSL https://cdn.coollabs.io/coolify/install.sh | bash
```

**3. Deploy Application:**
- Access Coolify: `http://<your-vps-ip>:3000`
- Add your Git repository
- Configure environment variables (see `.env.example`)
- Add domain: `api.arpay.anantarealty.in`
- Enable HTTPS (Let's Encrypt)
- Deploy!

📖 **Full deployment guide:** [DEPLOYMENT.md](DEPLOYMENT.md)

---

## 🔒 Security

### Security Checklist

- ✅ Non-root Docker user
- ✅ Rate limiting enabled
- ✅ CORS restricted to specific domains
- ✅ Security headers (HSTS, CSP, X-Frame-Options)
- ✅ JWT authentication with strong secrets
- ✅ Internal API key authentication
- ✅ SQL injection protection (prepared statements)
- ✅ Input validation
- ✅ Graceful shutdown
- ✅ Resource limits

📖 **Security guide:** [SECURITY.md](SECURITY.md)

### Required Environment Variables

```bash
# Database
DB_PASSWORD=<strong-password>

# Redis
REDIS_PASSWORD=<strong-password>

# JWT (64+ chars)
JWT_SECRET=<random-64-char-string>

# Internal API Key (64+ chars)
INTERNAL_API_KEYS=<random-64-char-hex>

# CORS
CORS_ORIGINS=https://arpay.anantarealty.in,https://api.arpay.anantarealty.in
```

---

## 📊 Monitoring

### Health Endpoints

| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Overall health status |
| `/actuator/health/liveness` | Liveness probe |
| `/actuator/health/readiness` | Readiness probe |
| `/actuator/prometheus` | Prometheus metrics |
| `/actuator/info` | Application info |

### Key Metrics

- HTTP request rates and latencies
- JVM memory and GC stats
- Database connection pool
- Redis connections
- Notification processing rates
- DLQ size
- Circuit breaker status

### Grafana Dashboards

Import these dashboards for monitoring:
- Spring Boot Statistics (ID: 10280)
- JVM Micrometer (ID: 4701)
- PostgreSQL Database (ID: 9628)
- Redis Dashboard (ID: 11835)

---

## 🔧 Configuration

### Application Properties

| File | Environment | Description |
|------|-------------|-------------|
| `application.properties` | Default | Base configuration |
| `application-production.properties` | Production | Security-hardened config |

### Rate Limits (Default)

| Type | Limit |
|------|-------|
| Global | 1000 req/s |
| Per Endpoint | 100 req/s |
| Per Client | 10 req/s |

### Resource Limits

| Component | CPU | Memory |
|-----------|-----|--------|
| Application | 2.0 | 2G |
| PostgreSQL | 1.0 | 1G |
| Redis | 0.5 | 512M |

---

## 📚 API Documentation

### Authentication

**Public Endpoints** (no auth required):
- `/api/notifications/tokens/**` - Device token registration
- `/api/notifications/recent` - Recent notifications
- `/api/notifications/unread-count` - Unread count

**Protected Endpoints** (require `X-API-Key` header):
- All other write operations

### Example Requests

**Register Device Token:**
```bash
curl -X POST https://api.arpay.anantarealty.in/api/notifications/tokens \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-123",
    "token": "fcm-device-token",
    "platform": "FCM"
  }'
```

**Send Notification:**
```bash
curl -X POST https://api.arpay.anantarealty.in/api/notifications \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-internal-api-key" \
  -d '{
    "userId": "user-123",
    "title": "Payment Received",
    "message": "Your payment of ₹1000 has been received",
    "type": "PAYMENT",
    "priority": "HIGH"
  }'
```

---

## 🧪 Testing

```bash
# Run unit tests
mvn test

# Run integration tests
mvn verify

# Run with coverage
mvn clean test jacoco:report
```

---

## 📁 Project Structure

```
arpay-notifications/
├── src/main/
│   ├── java/com/arpay/
│   │   ├── config/          # Security, Redis, Async config
│   │   ├── controller/      # REST controllers
│   │   ├── entity/          # JPA entities
│   │   ├── repository/      # Data repositories
│   │   ├── service/         # Business logic
│   │   ├── worker/          # Async workers
│   │   └── notification/    # Push notification services
│   └── resources/
│       ├── application*.properties
│       └── db/migration/    # Flyway migrations
├── docker-compose*.yml      # Docker configurations
├── Dockerfile              # Production Dockerfile
├── .github/workflows/      # CI/CD pipelines
├── monitoring/             # Prometheus & Grafana configs
├── nginx/                  # Nginx reverse proxy config
└── scripts/                # Utility scripts
```

---

## 🚨 Troubleshooting

### Common Issues

**Application won't start:**
```bash
docker logs arpay-notifications-app --tail 200
```

**Database connection failed:**
- Verify `DATABASE_URL` environment variable
- Check PostgreSQL container is running
- Ensure network connectivity

**High memory usage:**
```bash
docker stats arpay-notifications-app
```

📖 **Full troubleshooting guide:** [DEPLOYMENT.md#troubleshooting](DEPLOYMENT.md#troubleshooting)

---

## 📝 Changelog

### v1.0.0 (March 2026)
- Initial production release
- Docker containerization
- Coolify deployment support
- Security hardening
- Comprehensive monitoring

---

## 🤝 Contributing

This is a private repository. For internal contributions, contact the development team.

---

## 📄 License

Proprietary - Arpay. All rights reserved.

---

## 📞 Support

- **Documentation:** See `/docs` folder
- **Issues:** GitHub Issues
- **Emergency:** Contact DevOps team

---

**Built with ❤️ by the Arpay Team**
