#!/bin/bash
# =============================================================================
# ARPAY Notifications - Secret Generator Script
# Generates secure random passwords and keys for production deployment
# =============================================================================

set -e

echo "=============================================="
echo "  ARPAY Notifications Secret Generator"
echo "=============================================="
echo ""

# Generate Database Password (32 chars)
DB_PASSWORD=$(openssl rand -base64 32 | tr -d '\n')
echo "DB_PASSWORD=$DB_PASSWORD"

# Generate Redis Password (32 chars)
REDIS_PASSWORD=$(openssl rand -base64 32 | tr -d '\n')
echo "REDIS_PASSWORD=$REDIS_PASSWORD"

# Generate JWT Secret (64 chars)
JWT_SECRET=$(openssl rand -base64 64 | tr -d '\n')
echo "JWT_SECRET=$JWT_SECRET"

# Generate Internal API Key (64 chars hex)
INTERNAL_API_KEY=$(openssl rand -hex 32 | tr -d '\n')
echo "INTERNAL_API_KEYS=$INTERNAL_API_KEY"

# Generate Grafana Admin Password (24 chars)
GRAFANA_PASSWORD=$(openssl rand -base64 24 | tr -d '\n')
echo "GRAFANA_ADMIN_PASSWORD=$GRAFANA_PASSWORD"

# Generate Mail Password Placeholder (user should replace)
echo ""
echo "# Replace with your actual email app password"
echo "MAIL_PASSWORD=your-gmail-app-password-here"

echo ""
echo "=============================================="
echo "  IMPORTANT: Save these secrets securely!"
echo "=============================================="
echo ""
echo "Next steps:"
echo "1. Copy these values to your password manager"
echo "2. Add them to Coolify environment variables"
echo "3. NEVER commit them to version control"
echo "4. Rotate secrets every 90 days"
echo ""

# Optional: Save to .env file (encrypted)
if [ "$1" == "--save" ]; then
    ENV_FILE=".env.generated"
    cat > $ENV_FILE << EOF
# ARPAY Notifications - Generated Secrets
# Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
# WARNING: Keep this file secure!

DB_PASSWORD=$DB_PASSWORD
REDIS_PASSWORD=$REDIS_PASSWORD
JWT_SECRET=$JWT_SECRET
INTERNAL_API_KEYS=$INTERNAL_API_KEY
GRAFANA_ADMIN_PASSWORD=$GRAFANA_PASSWORD
MAIL_PASSWORD=your-gmail-app-password-here
EOF
    
    echo "Secrets saved to: $ENV_FILE"
    echo "Encrypt this file or delete it after copying secrets!"
fi
