# GitHub Environments Configuration

# =============================================================================
# STAGING Environment
# =============================================================================
# Name: staging
# URL: https://staging-api.arpay.anantarealty.in
# 
# Required Secrets:
# - COOLIFY_API_URL: https://coolify.arpay.anantarealty.in
# - COOLIFY_API_TOKEN: <your-coolify-api-token>
# - COOLIFY_PROJECT_ID: <staging-project-id>
# - SLACK_WEBHOOK_URL: <staging-slack-webhook>
#
# Required Variables:
# - DEPLOYMENT_BRANCH: develop
# - HEALTH_CHECK_URL: https://staging-api.arpay.anantarealty.in/actuator/health
# =============================================================================

# =============================================================================
# PRODUCTION Environment
# =============================================================================
# Name: production
# URL: https://api.arpay.anantarealty.in
#
# Required Secrets:
# - COOLIFY_API_URL: https://coolify.arpay.anantarealty.in
# - COOLIFY_API_TOKEN: <your-coolify-api-token>
# - COOLIFY_PROJECT_ID: <production-project-id>
# - SLACK_WEBHOOK_URL: <production-slack-webhook>
#
# Required Variables:
# - DEPLOYMENT_BRANCH: main
# - HEALTH_CHECK_URL: https://api.arpay.anantarealty.in/actuator/health
#
# Deployment Protection Rules:
# - Required reviewers: Enable
# - Reviewers: @tech-lead, @devops-team
# - Wait timer: 5 minutes
# =============================================================================
