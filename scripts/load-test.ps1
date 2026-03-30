# =============================================================================
# ARPAY Notifications - Load Test Script
# Tests system capacity with 1000 notifications
# =============================================================================
# Usage:
#   .\load-test.ps1 -BaseUrl "http://localhost:8086" -ApiKey "your-api-key"
#   .\load-test.ps1 -BaseUrl "http://localhost:8086" -ApiKey "your-api-key" -NotificationCount 1000
# =============================================================================

param(
    [Parameter(Mandatory=$true)]
    [string]$BaseUrl,
    
    [Parameter(Mandatory=$true)]
    [string]$ApiKey,
    
    [Parameter(Mandatory=$false)]
    [int]$NotificationCount = 1000,
    
    [Parameter(Mandatory=$false)]
    [int]$BatchSize = 50,
    
    [Parameter(Mandatory=$false)]
    [string]$UserId = "test-user-0000-0000-0000-000000000000"
)

# =============================================================================
# Configuration
# =============================================================================
$Headers = @{
    "Content-Type" = "application/json"
    "X-API-Key" = $ApiKey
    "X-Request-ID" = [Guid]::NewGuid().ToString()
}

$Endpoint = "$BaseUrl/api/notifications/send/user"

# =============================================================================
# Test Functions
# =============================================================================

function Write-TestHeader {
    param([string]$Text)
    Write-Host "`n$('=' * 80)" -ForegroundColor Cyan
    Write-Host $Text -ForegroundColor Cyan
    Write-Host $('=' * 80) -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Text)
    Write-Host "✓ $Text" -ForegroundColor Green
}

function Write-Error-Custom {
    param([string]$Text)
    Write-Host "✗ $Text" -ForegroundColor Red
}

function Write-Warning-Custom {
    param([string]$Text)
    Write-Host "⚠ $Text" -ForegroundColor Yellow
}

function Get-QueueMetrics {
    try {
        $response = Invoke-RestMethod -Uri "$BaseUrl/actuator/metrics" -Headers $Headers -Method Get -ErrorAction Stop
        return $response
    } catch {
        Write-Error-Custom "Failed to get metrics: $_"
        return $null
    }
}

function Get-QueueDepth {
    param(
        [string]$MetricName
    )
    try {
        $response = Invoke-RestMethod -Uri "$BaseUrl/actuator/metrics/$MetricName" -Headers $Headers -Method Get -ErrorAction Stop
        return $response.measurements[0].value
    } catch {
        return 0
    }
}

function Send-Notification {
    param(
        [string]$Title,
        [string]$Message,
        [string]$TestId
    )
    
    $body = @{
        userId = $UserId
        title = $Title
        message = $Message
        entityType = "PAYMENT"
        entityId = (New-Guid).ToString()
        data = @{
            testId = $TestId
            timestamp = (Get-Date).ToString("o")
        }
    } | ConvertTo-Json -Depth 10
    
    try {
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        
        $response = Invoke-RestMethod -Uri $Endpoint -Headers $Headers -Method Post -Body $body -ErrorAction Stop
        
        $stopwatch.Stop()
        
        return @{
            Success = $true
            DurationMs = $stopwatch.ElapsedMilliseconds
            StatusCode = 200
            Response = $response
        }
    } catch {
        if ($_.Exception.Response -ne $null) {
            $statusCode = $_.Exception.Response.StatusCode.value__
            
            return @{
                Success = $false
                DurationMs = 0
                StatusCode = $statusCode
                Error = $_.Exception.Message
            }
        } else {
            return @{
                Success = $false
                DurationMs = 0
                StatusCode = 0
                Error = $_.Exception.Message
            }
        }
    }
}

# =============================================================================
# Load Test Execution
# =============================================================================

Write-TestHeader "ARPAY NOTIFICATIONS LOAD TEST"
Write-Host "Configuration:"
Write-Host "  Base URL:        $BaseUrl"
Write-Host "  Notification Count: $NotificationCount"
Write-Host "  Batch Size:      $BatchSize"
Write-Host "  User ID:         $UserId"
Write-Host "  Endpoint:        $Endpoint"

# Pre-test health check
Write-TestHeader "PRE-TEST HEALTH CHECK"

try {
    $healthResponse = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Headers $Headers -Method Get -ErrorAction Stop
    Write-Success "Application health: $($healthResponse.status)"
} catch {
    Write-Error-Custom "Application health check failed: $_"
    Write-Host "Ensure the application is running at $BaseUrl"
    exit 1
}

# Get initial queue state
Write-Host "`nInitial queue state:"
$initialPending = Get-QueueDepth -MetricName "notification.queue.pending.count"
$initialQueued = Get-QueueDepth -MetricName "notification.queue.queued.count"
$initialProcessing = Get-QueueDepth -MetricName "notification.queue.processing.count"
$initialDlq = Get-QueueDepth -MetricName "notification.dlq.count"

Write-Host "  Pending:     $initialPending"
Write-Host "  Queued:      $initialQueued"
Write-Host "  Processing:  $initialProcessing"
Write-Host "  DLQ:         $initialDlq"

# Start load test
Write-TestHeader "LOAD TEST EXECUTION"
Write-Host "Sending $NotificationCount notifications in batches of $BatchSize...`n"

$startTime = Get-Date
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

$results = @()
$successCount = 0
$failureCount = 0
$rateLimitedCount = 0
$totalDuration = 0

for ($i = 0; $i -lt $NotificationCount; $i += $BatchSize) {
    $batchNumber = [math]::Floor($i / $BatchSize) + 1
    $totalBatches = [math]::Ceiling($NotificationCount / $BatchSize)
    
    Write-Host "Batch $batchNumber/$totalBatches (notifications $($i + 1) to $([math]::Min($i + $BatchSize, $NotificationCount)))..."
    
    $batchTasks = @()
    
    for ($j = $i; $j -lt [math]::Min($i + $BatchSize, $NotificationCount); $j++) {
        $notificationNum = $j + 1
        $batchTasks += Start-Job -ScriptBlock {
            param($BaseUrl, $ApiKey, $UserId, $NotificationNum)
            
            $Headers = @{
                "Content-Type" = "application/json"
                "X-API-Key" = $ApiKey
                "X-Request-ID" = [Guid]::NewGuid().ToString()
            }
            
            $Endpoint = "$BaseUrl/api/notifications/send/user"
            
            $body = @{
                userId = $UserId
                title = "Load Test Notification #$NotificationNum"
                message = "This is a test notification for load testing. ID: $NotificationNum"
                entityType = "PAYMENT"
                entityId = (New-Guid).ToString()
                data = @{
                    testId = "load-test-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
                    notificationNumber = $NotificationNum
                }
            } | ConvertTo-Json -Depth 10
            
            try {
                $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
                $response = Invoke-RestMethod -Uri $Endpoint -Headers $Headers -Method Post -Body $body -ErrorAction Stop
                $stopwatch.Stop()
                
                return @{
                    Success = $true
                    DurationMs = $stopwatch.ElapsedMilliseconds
                    StatusCode = 200
                    NotificationNum = $NotificationNum
                }
            } catch {
                $stopwatch.Stop()
                $statusCode = 0
                if ($_.Exception.Response -ne $null) {
                    $statusCode = $_.Exception.Response.StatusCode.value__
                }
                
                return @{
                    Success = $false
                    DurationMs = $stopwatch.ElapsedMilliseconds
                    StatusCode = $statusCode
                    Error = $_.Exception.Message
                    NotificationNum = $NotificationNum
                }
            }
        } -ArgumentList $BaseUrl, $ApiKey, $UserId, $notificationNum
    }
    
    # Wait for batch to complete
    $batchResults = $batchTasks | Wait-Job | Receive-Job
    $batchTasks | Remove-Job
    
    # Process batch results
    foreach ($result in $batchResults) {
        $results += $result
        
        if ($result.Success) {
            $successCount++
            $totalDuration += $result.DurationMs
        } else {
            $failureCount++
            if ($result.StatusCode -eq 429) {
                $rateLimitedCount++
                Write-Warning-Custom "Notification $($result.NotificationNum): Rate limited (HTTP 429)"
            } else {
                Write-Error-Custom "Notification $($result.NotificationNum): Failed - $($result.Error)"
            }
        }
    }
    
    # Small delay between batches to avoid overwhelming
    Start-Sleep -Milliseconds 100
}

$stopwatch.Stop()
$endTime = Get-Date
$totalTime = New-TimeSpan -Start $startTime -End $endTime

# Calculate statistics
Write-TestHeader "LOAD TEST RESULTS"

Write-Host "`n📊 Summary:"
Write-Host "  Total Notifications:  $NotificationCount"
Write-Host "  Successful:           $successCount ($([math]::Round($successCount / $NotificationCount * 100, 2))%)"
Write-Host "  Failed:               $failureCount ($([math]::Round($failureCount / $NotificationCount * 100, 2))%)"
Write-Host "  Rate Limited (429):   $rateLimitedCount"
Write-Host "  Total Time:           $($totalTime.Minutes)m $($totalTime.Seconds)s.$($totalTime.Milliseconds)ms"

if ($successCount -gt 0) {
    $avgDuration = $totalDuration / $successCount
    Write-Host "  Avg Response Time:    $([math]::Round($avgDuration, 2))ms"
    $throughput = $NotificationCount / $totalTime.TotalSeconds
    Write-Host "  Throughput:           $([math]::Round($throughput, 2)) notifications/second"
}

# Post-test queue state
Write-Host "`n📈 Post-test queue state:"
$finalPending = Get-QueueDepth -MetricName "notification.queue.pending.count"
$finalQueued = Get-QueueDepth -MetricName "notification.queue.queued.count"
$finalProcessing = Get-QueueDepth -MetricName "notification.queue.processing.count"
$finalDlq = Get-QueueDepth -MetricName "notification.dlq.count"

Write-Host "  Pending:     $finalPending (Δ: $(if ($finalPending -gt $initialPending) {"+"}$($finalPending - $initialPending)))"
Write-Host "  Queued:      $finalQueued (Δ: $(if ($finalQueued -gt $initialQueued) {"+"}$($finalQueued - $initialQueued)))"
Write-Host "  Processing:  $finalProcessing (Δ: $(if ($finalProcessing -gt $initialProcessing) {"+"}$($finalProcessing - $initialProcessing)))"
Write-Host "  DLQ:         $finalDlq (Δ: $(if ($finalDlq -gt $initialDlq) {"+"}$($finalDlq - $initialDlq)))"

# Analysis
Write-TestHeader "ANALYSIS"

$queueGrowth = $finalPending - $initialPending
$processedDuringTest = $NotificationCount - $queueGrowth

if ($successCount -eq $NotificationCount) {
    Write-Success "All notifications accepted by system"
} elseif ($rateLimitedCount -gt 0) {
    Write-Warning-Custom "$rateLimitedCount notifications were rate limited (HTTP 429)"
    Write-Host "  This indicates the system is protecting itself from overload"
}

if ($queueGrowth -gt 0) {
    Write-Host "`n⚠ Queue grew by $queueGrowth messages during test"
    Write-Host "  This means processing couldn't keep up with ingestion"
    if ($queueGrowth -gt ($NotificationCount * 0.5)) {
        Write-Warning-Custom "WARNING: More than 50% of notifications are still queued"
    }
} else {
    Write-Success "Queue depth stable or decreasing - processing keeping up with ingestion"
}

if ($finalDlq -gt $initialDlq) {
    $dlqGrowth = $finalDlq - $initialDlq
    Write-Warning-Custom "DLQ grew by $dlqGrowth messages - check failure reasons"
}

# Hard limits validation
Write-Host "`n🛑 Hard Limits Validation:"

$maxQueueSize = 10000
$maxDlqSize = 100
$queueUsagePercent = ($finalPending / $maxQueueSize) * 100

Write-Host "  Queue Usage: $([math]::Round($queueUsagePercent, 2))% of $maxQueueSize"
if ($queueUsagePercent -gt 90) {
    Write-Error-Custom "CRITICAL: Queue usage exceeded 90% - ingestion should be paused"
} elseif ($queueUsagePercent -gt 70) {
    Write-Warning-Custom "WARNING: Queue usage exceeded 70% - backpressure active"
} else {
    Write-Success "Queue usage within acceptable limits"
}

Write-Host "  DLQ Usage: $([math]::Round(($finalDlq / $maxDlqSize) * 100, 2))% of $maxDlqSize"
if ($finalDlq -gt $maxDlqSize) {
    Write-Error-Custom "CRITICAL: DLQ size exceeded threshold ($finalDlq > $maxDlqSize)"
} else {
    Write-Success "DLQ size within acceptable limits"
}

Write-TestHeader "TEST COMPLETED"

# Export results
$results | Export-Csv -Path "load-test-results-$(Get-Date -Format 'yyyyMMdd-HHmmss').csv" -NoTypeInformation
Write-Host "Results exported to: load-test-results-*.csv"
