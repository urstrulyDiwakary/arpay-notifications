# ARPAY Notifications - Local Development Environment Setup
# Run this script to set up environment variables for local development

# -----------------------------------------------------------------------------
# IMPORTANT: These are DEVELOPMENT ONLY values
# For production, use strong random values as documented in .env.example
# -----------------------------------------------------------------------------

# Database (update with your actual PostgreSQL password)
$env:DB_PASSWORD = "postgres"  # Change to your DB password

# Redis (leave empty if no password in local Redis)
$env:REDIS_PASSWORD = ""

# JWT Secret (64+ characters recommended for production)
$env:JWT_SECRET = "dev-jwt-secret-for-local-testing-only-change-in-production-1234567890"

# Internal API Keys (comma-separated list for service-to-service auth)
# This is what the backend service will use to authenticate
$env:INTERNAL_API_KEYS = "dev-api-key-1234567890abcdef,backend-service-key-0987654321fedcba"

# Firebase (set to false if you don't have Firebase configured)
$env:FIREBASE_ENABLED = "true"

# CORS (allow local frontend)
$env:CORS_ORIGINS = "http://localhost:5173,http://localhost:5174,http://localhost:8080,http://localhost:3000"

# Logging (enable debug for troubleshooting)
$env:LOGGING_LEVEL_COM_ARPAY = "DEBUG"

Write-Host "Environment variables set for local development" -ForegroundColor Green
Write-Host ""
Write-Host "To start the application, run:" -ForegroundColor Yellow
Write-Host "  mvn spring-boot:run" -ForegroundColor Cyan
Write-Host ""
Write-Host "To test the API, use these headers:" -ForegroundColor Yellow
Write-Host "  X-API-Key: dev-api-key-1234567890abcdef" -ForegroundColor Cyan
Write-Host ""
Write-Host "Or use the backend-service-key for backend calls:" -ForegroundColor Yellow
Write-Host "  X-API-Key: backend-service-key-0987654321fedcba" -ForegroundColor Cyan
