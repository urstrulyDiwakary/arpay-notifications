# =============================================================================
# ARPAY Notifications - BURST Load Test Script
# Tests system with 5000 requests in 5 seconds (1000 msg/s burst)
# =============================================================================
# Usage:
#   .\burst-load-test.ps1 -BaseUrl "http://localhost:8086" -ApiKey "your-api-key"
#   .\burst-load-test.ps1 -BaseUrl "http://localhost:8086" -ApiKey "your-api-key" -RequestCount 5000
# =============================================================================

param(
    [Parameter(Mandatory=$true)]
    [string]$BaseUrl,
    
    [Parameter(Mandatory=$true)]
    [string]$ApiKey,
    
    [Parameter(Mandatory=$false)]
    [int]$RequestCount = 5000,
    
    [Parameter(Mandatory=$false)]
    [int]$DurationSeconds = 5,
    
    [Parameter(Mandatory=$false)]
    [string]$UserId = "test-user-burst-0000-0000-00000000"
)

# =============================================================================
# Configuration
# =============================================================================
$Endpoint = "$BaseUrl/api/notifications/send/user"

# Test different priority distributions
$PriorityDistribution = @{
    "CRITICAL" = 0.10  # 10% critical
    "NORMAL" = 0.60    # 60% normal
    "LOW" = 0.30       # 30% low
}

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

function Get-QueueDepth {
    param([string]$MetricName)
    try {
        $response = Invoke-RestMethod -Uri "$BaseUrl/actuator/metrics/$MetricName" -ErrorAction Stop
        return $response.measurements[0].value
    } catch {
        return 0
    }
}

function Get-QueueMetrics {
    return @{
        Pending = Get-QueueDepth -MetricName "notification.queue.pending.count"
        Queued = Get-QueueDepth -MetricName "notification.queue.queued.count"
        Processing = Get-QueueDepth -MetricName "notification.queue.processing.count"
        Dlq = Get-QueueDepth -MetricName "notification.dlq.count"
    }
}

function Send-BurstRequest {
    param(
        [int]$RequestNum,
        [string]$Priority
    )
    
    $entityTypes = @{
        "CRITICAL" = "PAYMENT_FRAUD_ALERT"
        "NORMAL" = "TRANSACTION_APPROVAL"
        "LOW" = "MARKETING_PROMO"
    }
    
    $body = @{
        userId = $UserId
        title = "BURST TEST #$RequestNum - $Priority"
        message = "Burst load test notification. Priority: $Priority. ID: $RequestNum"
        entityType = $entityTypes[$Priority]
        entityId = (New-Guid).ToString()
        data = @{
            testId = "burst-test-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
            priority = $Priority
            requestNumber = $RequestNum
        }
    } | ConvertTo-Json -Depth 10
    
    $headers = @{
        "Content-Type" = "application/json"
        "X-API-Key" = $ApiKey
        "X-Priority" = $Priority
        "X-Request-ID" = [Guid]::NewGuid().ToString()
    }
    
    try {
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $response = Invoke-RestMethod -Uri $Endpoint -Headers $headers -Method Post -Body $body -ErrorAction Stop
        $stopwatch.Stop()
        
        return @{
            Success = $true
            DurationMs = $stopwatch.ElapsedMilliseconds
            StatusCode = 200
            Priority = $Priority
            RequestNum = $RequestNum
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
            Priority = $Priority
            RequestNum = $RequestNum
            Error = $_.Exception.Message
        }
    }
}

# =============================================================================
# BURST TEST EXECUTION
# =============================================================================

Write-TestHeader "ARPAY NOTIFICATIONS - BURST LOAD TEST"
Write-Host "Configuration:"
Write-Host "  Base URL:         $BaseUrl"
Write-Host "  Request Count:    $RequestCount"
Write-Host "  Duration:         $DurationSeconds seconds"
Write-Host "  Target Rate:      $([math]::Round($RequestCount / $DurationSeconds, 0)) requests/second"
Write-Host "  User ID:          $UserId"
Write-Host "  Endpoint:         $Endpoint"

# Priority distribution
Write-Host "`nPriority Distribution:"
$PriorityDistribution.GetEnumerator() | ForEach-Object {
    $count = [math]::Round($RequestCount * $_.Value)
    Write-Host "  $($_.Key): $count ($([math]::Round($_.Value * 100))%)"
}

# Pre-test health check
Write-TestHeader "PRE-TEST HEALTH CHECK"

try {
    $healthResponse = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -ErrorAction Stop
    Write-Success "Application health: $($healthResponse.status)"
} catch {
    Write-Error-Custom "Application health check failed: $_"
    Write-Host "Ensure the application is running at $BaseUrl"
    exit 1
}

# Get initial queue state
Write-Host "`nInitial queue state:"
$initialMetrics = Get-QueueMetrics
$initialMetrics.GetEnumerator() | ForEach-Object {
    Write-Host "  $($_.Key): $($_.Value)"
}

# Calculate request distribution by priority
$criticalCount = [math]::Round($RequestCount * $PriorityDistribution["CRITICAL"])
$normalCount = [math]::Round($RequestCount * $PriorityDistribution["NORMAL"])
$lowCount = [math]::Round($RequestCount * $PriorityDistribution["LOW"])

# Start burst test
Write-TestHeader "BURST TEST EXECUTION"
Write-Host "Sending $RequestCount requests over $DurationSeconds seconds...`n"

$startTime = Get-Date
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

$results = @()
$successCount = 0
$failureCount = 0
$rateLimitedCount = 0
$priorityRejectedCount = 0

# Create request plan
$requestPlan = @()
for ($i = 1; $i -le $criticalCount; $i++) { $requestPlan += @{ Priority = "CRITICAL"; Num = $i } }
for ($i = 1; $i -le $normalCount; $i++) { $requestPlan += @{ Priority = "NORMAL"; Num = $criticalCount + $i } }
for ($i = 1; $i -le $lowCount; $i++) { $requestPlan += @{ Priority = "LOW"; Num = $criticalCount + $normalCount + $i } }

# Shuffle requests to mix priorities
$requestPlan = $requestPlan | Sort-Object { Get-Random }

# Calculate delay between requests to spread over duration
$delayMs = ($DurationSeconds * 1000) / $RequestCount
Write-Host "Delay between requests: $([math]::Round($delayMs, 2))ms"

# Execute burst
$batchSize = 100
$batchCount = [math]::Ceiling($RequestCount / $batchSize)

for ($batch = 0; $batch -lt $batchCount; $batch++) {
    $batchStart = $batch * $batchSize
    $batchEnd = [math]::Min(($batch + 1) * $batchSize, $RequestCount)
    $batchRequests = $requestPlan[$batchStart..($batchEnd - 1)]
    
    Write-Host "Batch $($batch + 1)/$batchCount (requests $($batchStart + 1) to $batchEnd)..."
    
    $batchTasks = @()
    foreach ($req in $batchRequests) {
        $batchTasks += Start-Job -ScriptBlock {
            param($BaseUrl, $ApiKey, $UserId, $Priority, $RequestNum)
            
            $entityTypes = @{
                "CRITICAL" = "PAYMENT_FRAUD_ALERT"
                "NORMAL" = "TRANSACTION_APPROVAL"
                "LOW" = "MARKETING_PROMO"
            }
            
            $body = @{
                userId = $UserId
                title = "BURST TEST #$RequestNum - $Priority"
                message = "Burst load test notification. Priority: $Priority. ID: $RequestNum"
                entityType = $entityTypes[$Priority]
                entityId = (New-Guid).ToString()
                data = @{
                    testId = "burst-test"
                    priority = $Priority
                    requestNumber = $RequestNum
                }
            } | ConvertTo-Json -Depth 10
            
            $headers = @{
                "Content-Type" = "application/json"
                "X-API-Key" = $ApiKey
                "X-Priority" = $Priority
                "X-Request-ID" = [Guid]::NewGuid().ToString()
            }
            
            try {
                $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
                $response = Invoke-RestMethod -Uri "$BaseUrl/api/notifications/send/user" -Headers $headers -Method Post -Body $body -ErrorAction Stop
                $stopwatch.Stop()
                
                return @{
                    Success = $true
                    DurationMs = $stopwatch.ElapsedMilliseconds
                    StatusCode = 200
                    Priority = $Priority
                    RequestNum = $RequestNum
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
                    Priority = $Priority
                    RequestNum = $RequestNum
                    Error = $_.Exception.Message
                }
            }
        } -ArgumentList $BaseUrl, $ApiKey, $UserId, $req.Priority, $req.Num
    }
    
    # Wait for batch and collect results
    $batchResults = $batchTasks | Wait-Job | Receive-Job
    $batchTasks | Remove-Job
    
    foreach ($result in $batchResults) {
        $results += $result
        
        if ($result.Success) {
            $successCount++
        } else {
            $failureCount++
            if ($result.StatusCode -eq 429) {
                $rateLimitedCount++
            }
        }
    }
    
    # Small delay between batches
    Start-Sleep -Milliseconds ($delayMs * $batchSize)
}

$stopwatch.Stop()
$endTime = Get-Date
$totalTime = New-TimeSpan -Start $startTime -End $endTime

# Calculate statistics
Write-TestHeader "BURST TEST RESULTS"

Write-Host "`n📊 Summary:"
Write-Host "  Total Requests:     $RequestCount"
Write-Host "  Successful:         $successCount ($([math]::Round($successCount / $RequestCount * 100, 2))%)"
Write-Host "  Failed:             $failureCount ($([math]::Round($failureCount / $RequestCount * 100, 2))%)"
Write-Host "  Rate Limited (429): $rateLimitedCount"
Write-Host "  Total Time:         $($totalTime.Minutes)m $($totalTime.Seconds)s.$($totalTime.Milliseconds)ms"

$actualRate = $RequestCount / $totalTime.TotalSeconds
Write-Host "  Actual Rate:        $([math]::Round($actualRate, 2)) requests/second"
Write-Host "  Target Rate:        $([math]::Round($RequestCount / $DurationSeconds, 2)) requests/second"

# Breakdown by priority
Write-Host "`n📈 Results by Priority:"
$priorityGroups = $results | Group-Object -Property Priority
foreach ($group in $priorityGroups) {
    $groupSuccess = ($group.Group | Where-Object { $_.Success }).Count
    $groupTotal = $group.Count
    $group429 = ($group.Group | Where-Object { $_.StatusCode -eq 429 }).Count
    
    Write-Host "  $($group.Name):"
    Write-Host "    Total:      $groupTotal"
    Write-Host "    Success:    $groupSuccess ($([math]::Round($groupSuccess / $groupTotal * 100, 2))%)"
    Write-Host "    Rate Limited: $group429"
}

# Post-test queue state
Write-Host "`n📈 Post-burst queue state:"
$finalMetrics = Get-QueueMetrics
Write-Host "  Pending:     $($finalMetrics.Pending) (Δ: $(if ($finalMetrics.Pending -gt $initialMetrics.Pending) {"+"}$([math]::Round($finalMetrics.Pending - $initialMetrics.Pending))))"
Write-Host "  Queued:      $($finalMetrics.Queued) (Δ: $(if ($finalMetrics.Queued -gt $initialMetrics.Queued) {"+"}$([math]::Round($finalMetrics.Queued - $initialMetrics.Queued))))"
Write-Host "  Processing:  $($finalMetrics.Processing) (Δ: $(if ($finalMetrics.Processing -gt $initialMetrics.Processing) {"+"}$([math]::Round($finalMetrics.Processing - $initialMetrics.Processing))))"
Write-Host "  DLQ:         $($finalMetrics.Dlq) (Δ: $(if ($finalMetrics.Dlq -gt $initialMetrics.Dlq) {"+"}$([math]::Round($finalMetrics.Dlq - $initialMetrics.Dlq))))"

# Analysis
Write-TestHeader "ANALYSIS"

# Priority rejection analysis
Write-Host "`n🛑 Priority Rejection Analysis:"

$lowRejected = ($results | Where-Object { $_.Priority -eq "LOW" -and $_.StatusCode -eq 429 }).Count
$normalRejected = ($results | Where-Object { $_.Priority -eq "NORMAL" -and $_.StatusCode -eq 429 }).Count
$criticalRejected = ($results | Where-Object { $_.Priority -eq "CRITICAL" -and $_.StatusCode -eq 429 }).Count

$lowTotal = ($results | Where-Object { $_.Priority -eq "LOW" }).Count
$normalTotal = ($results | Where-Object { $_.Priority -eq "NORMAL" }).Count
$criticalTotal = ($results | Where-Object { $_.Priority -eq "CRITICAL" }).Count

Write-Host "  CRITICAL rejected: $criticalRejected / $criticalTotal ($([math]::Round($criticalRejected / [math]::Max(1, $criticalTotal) * 100, 2))%)"
Write-Host "  NORMAL rejected:   $normalRejected / $normalTotal ($([math]::Round($normalRejected / [math]::Max(1, $normalTotal) * 100, 2))%)"
Write-Host "  LOW rejected:      $lowRejected / $lowTotal ($([math]::Round($lowRejected / [math]::Max(1, $lowTotal) * 100, 2))%)"

if ($lowRejected -gt 0 -and $normalRejected -eq 0 -and $criticalRejected -eq 0) {
    Write-Success "Priority rejection working correctly: LOW rejected first"
} elseif ($lowRejected -gt 0 -and $normalRejected -gt 0 -and $criticalRejected -eq 0) {
    Write-Success "Priority rejection working correctly: LOW + NORMAL rejected, CRITICAL accepted"
} elseif ($rateLimitedCount -gt 0) {
    Write-Warning-Custom "Priority rejection may not be working as expected"
}

# Queue behavior analysis
$queueSpike = $finalMetrics.Pending - $initialMetrics.Pending
Write-Host "`n📈 Queue Behavior:"
Write-Host "  Queue spike: $queueSpike messages"

if ($queueSpike -gt ($RequestCount * 0.8)) {
    Write-Warning-Custom "Large queue spike (>80% of requests queued)"
} else {
    Write-Success "Queue spike within acceptable range"
}

# Recovery monitoring
Write-Host "`n⏱️  Recovery Monitoring (watching for 60 seconds)..."

$recoveryStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$recoveryDuration = 60 # seconds
$checkInterval = 5 # seconds

$recoveryData = @()

while ($recoveryStopwatch.Elapsed.TotalSeconds -lt $recoveryDuration) {
    Start-Sleep -Seconds $checkInterval
    
    $pending = Get-QueueDepth -MetricName "notification.queue.pending.count"
    $processing = Get-QueueDepth -MetricName "notification.queue.processing.count"
    $rate = (Get-QueueDepth -MetricName "notification.processing.rate.per_second")
    
    $recoveryData += @{
        Time = $recoveryStopwatch.Elapsed.TotalSeconds
        Pending = $pending
        Processing = $processing
        Rate = $rate
    }
    
    Write-Host "  T+$([math]::Round($recoveryStopwatch.Elapsed.TotalSeconds, 0))s: Pending=$pending, Processing=$processing, Rate=$([math]::Round($rate, 2)) msg/s"
}

# Calculate time to recovery
Write-Host "`n⏱️  Time to Recovery Analysis:"

$baselinePending = $initialMetrics.Pending
$recoveryThreshold = $baselinePending + 10 # Within 10 messages of baseline

$recovered = $false
$timeToRecovery = $null

foreach ($dataPoint in $recoveryData) {
    if ($dataPoint.Pending -le $recoveryThreshold -and -not $recovered) {
        $recovered = $true
        $timeToRecovery = $dataPoint.Time
        Write-Success "System recovered at T+${timeToRecovery}s"
        break
    }
}

if (-not $recovered) {
    $lastPending = $recoveryData[-1].Pending
    Write-Warning-Custom "System did not fully recover within ${recoveryDuration}s (pending: $lastPending)"
}

# Export results
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$results | Export-Csv -Path "burst-test-results-$timestamp.csv" -NoTypeInformation
$recoveryData | Export-Csv -Path "burst-test-recovery-$timestamp.csv" -NoTypeInformation

Write-Host "`n📁 Results exported to:"
Write-Host "  burst-test-results-$timestamp.csv"
Write-Host "  burst-test-recovery-$timestamp.csv"

Write-TestHeader "BURST TEST COMPLETED"
