# =============================================================================
# ARPAY Notifications - Secret Generator Script (PowerShell)
# For Windows users
# =============================================================================

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "  ARPAY Notifications Secret Generator" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host ""

# Function to generate random string
function Get-RandomString {
    param(
        [int]$Length = 32,
        [string]$CharacterSet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*"
    )
    $bytes = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RNGCryptoServiceProvider]::Create()
    $rng.GetBytes($bytes)
    $result = ""
    foreach ($byte in $bytes) {
        $result += $CharacterSet[$byte % $CharacterSet.Length]
    }
    $rng.Dispose()
    return $result
}

# Generate Database Password (32 chars)
$DB_PASSWORD = Get-RandomString -Length 32
Write-Host "DB_PASSWORD=$DB_PASSWORD" -ForegroundColor Yellow

# Generate Redis Password (32 chars)
$REDIS_PASSWORD = Get-RandomString -Length 32
Write-Host "REDIS_PASSWORD=$REDIS_PASSWORD" -ForegroundColor Yellow

# Generate JWT Secret (64 chars)
$JWT_SECRET = Get-RandomString -Length 64
Write-Host "JWT_SECRET=$JWT_SECRET" -ForegroundColor Yellow

# Generate Internal API Key (64 chars hex)
$INTERNAL_API_KEY = (New-Object Guid).NewGuid().ToString() + (New-Object Guid).NewGuid().ToString() -replace '-', ''
Write-Host "INTERNAL_API_KEYS=$INTERNAL_API_KEY" -ForegroundColor Yellow

# Generate Grafana Admin Password (24 chars)
$GRAFANA_PASSWORD = Get-RandomString -Length 24
Write-Host "GRAFANA_ADMIN_PASSWORD=$GRAFANA_PASSWORD" -ForegroundColor Yellow

Write-Host ""
Write-Host "# Replace with your actual email app password" -ForegroundColor Gray
Write-Host "MAIL_PASSWORD=your-gmail-app-password-here" -ForegroundColor Yellow

Write-Host ""
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "  IMPORTANT: Save these secrets securely!" -ForegroundColor Red
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Next steps:" -ForegroundColor White
Write-Host "1. Copy these values to your password manager"
Write-Host "2. Add them to Coolify environment variables"
Write-Host "3. NEVER commit them to version control"
Write-Host "4. Rotate secrets every 90 days"
Write-Host ""

# Optional: Save to .env file
if ($args[0] -eq "--save") {
    $ENV_FILE = ".env.generated"
    $content = @"
# ARPAY Notifications - Generated Secrets
# Generated: $(Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ")
# WARNING: Keep this file secure!

DB_PASSWORD=$DB_PASSWORD
REDIS_PASSWORD=$REDIS_PASSWORD
JWT_SECRET=$JWT_SECRET
INTERNAL_API_KEYS=$INTERNAL_API_KEY
GRAFANA_ADMIN_PASSWORD=$GRAFANA_PASSWORD
MAIL_PASSWORD=your-gmail-app-password-here
"@
    
    $content | Out-File -FilePath $ENV_FILE -Encoding UTF8
    Write-Host "Secrets saved to: $ENV_FILE" -ForegroundColor Green
    Write-Host "Encrypt this file or delete it after copying secrets!" -ForegroundColor Red
}
