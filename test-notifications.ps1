# ARPAY Notifications - API Test Script
# Tests the notification endpoints to verify everything is working

$baseUrl = "http://localhost:8086"
$apiKey = "dev-api-key-1234567890abcdef"  # Must match INTERNAL_API_KEYS env var

$headers = @{
    "X-API-Key" = $apiKey
    "Content-Type" = "application/json"
}

Write-Host "=== ARPAY Notifications API Test ===" -ForegroundColor Green
Write-Host "Base URL: $baseUrl" -ForegroundColor Cyan
Write-Host ""

# Test 1: Health Check
Write-Host "1. Testing Health Endpoint..." -ForegroundColor Yellow
try {
    $health = Invoke-RestMethod -Uri "$baseUrl/actuator/health" -Method Get
    Write-Host "   ✓ Health: $($health.status)" -ForegroundColor Green
} catch {
    Write-Host "   ✗ Health check failed: $($_.Exception.Message)" -ForegroundColor Red
}

# Test 2: Register Device Token
Write-Host ""
Write-Host "2. Testing Device Token Registration..." -ForegroundColor Yellow
$testUserId = "test-user-$((Get-Date).ToString('yyyyMMddHHmmss'))"
$testToken = "test-device-token-$((Get-Random -Maximum 999999))"

$tokenPayload = @{
    userId = $testUserId
    deviceToken = $testToken
    deviceType = "ANDROID"
    appVersion = "1.0.0"
} | ConvertTo-Json

try {
    $response = Invoke-RestMethod -Uri "$baseUrl/api/notifications/tokens" -Method Post -Headers $headers -Body $tokenPayload
    Write-Host "   ✓ Token registered for userId: $testUserId" -ForegroundColor Green
} catch {
    Write-Host "   ✗ Token registration failed: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails.Message) {
        Write-Host "   Response: $($_.ErrorDetails.Message)" -ForegroundColor Red
    }
}

# Test 3: Send Test Notification
Write-Host ""
Write-Host "3. Testing Notification Creation..." -ForegroundColor Yellow
$notificationPayload = @{
    userId = $testUserId
    title = "Test Notification"
    message = "This is a test notification from the API test script"
    type = "INFO"
    priority = "NORMAL"
    data = @{
        testId = (Get-Date).ToString("yyyyMMddHHmmss")
        source = "api-test-script"
    }
} | ConvertTo-Json

try {
    $response = Invoke-RestMethod -Uri "$baseUrl/api/notifications/send" -Method Post -Headers $headers -Body $notificationPayload
    Write-Host "   ✓ Notification created successfully" -ForegroundColor Green
    Write-Host "   Notification ID: $($response.id)" -ForegroundColor Cyan
} catch {
    Write-Host "   ✗ Notification creation failed: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails.Message) {
        Write-Host "   Response: $($_.ErrorDetails.Message)" -ForegroundColor Red
    }
}

# Test 4: Fetch User Notifications
Write-Host ""
Write-Host "4. Testing Fetch User Notifications..." -ForegroundColor Yellow
try {
    Start-Sleep -Seconds 1  # Give it time to process
    $response = Invoke-RestMethod -Uri "$baseUrl/api/notifications?userId=$testUserId&page=0&size=10" -Method Get
    Write-Host "   ✓ Fetched $($response.content.Count) notifications" -ForegroundColor Green
} catch {
    Write-Host "   ✗ Fetch failed: $($_.Exception.Message)" -ForegroundColor Red
}

# Test 5: Unread Count
Write-Host ""
Write-Host "5. Testing Unread Count..." -ForegroundColor Yellow
try {
    $response = Invoke-RestMethod -Uri "$baseUrl/api/notifications/unread-count?userId=$testUserId" -Method Get
    Write-Host "   ✓ Unread count: $response" -ForegroundColor Green
} catch {
    Write-Host "   ✗ Unread count failed: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Write-Host "=== Test Complete ===" -ForegroundColor Green
Write-Host ""
Write-Host "If all tests passed, your notification service is working correctly!" -ForegroundColor Green
Write-Host "If tests failed, check:" -ForegroundColor Yellow
Write-Host "  1. Application is running on port 8086" -ForegroundColor White
Write-Host "  2. INTERNAL_API_KEYS environment variable is set correctly" -ForegroundColor White
Write-Host "  3. Database connection is working" -ForegroundColor White
Write-Host "  4. Check application logs for detailed error messages" -ForegroundColor White
