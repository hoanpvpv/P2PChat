[CmdletBinding()]
param(
    [int]$PeerCount = 6,
    [int]$InitialPeers = 3,
    [int]$TotalRounds = 12,
    [int]$RoundIntervalSec = 6,
    [int]$BootstrapPort = 9000,
    [int]$DashboardPort = 9001,
    [int]$BasePeerPort = 5001,
    [int]$BaseWebPort = 3000,
    [switch]$StartBootstrap = $true,
    [switch]$KeepBootstrapRunning,
    [switch]$KeepPeersRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$bootstrapJar = Join-Path $repoRoot "bootstrap-server\target\bootstrap-server.jar"
$peerJar = Join-Path $repoRoot "peer-node\target\peer-node.jar"
$javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
if (-not (Test-Path $javaExe)) {
    $javaExe = "java"
}

$logRoot = Join-Path $repoRoot "churn-logs"
if (-not (Test-Path $logRoot)) {
    New-Item -ItemType Directory -Path $logRoot | Out-Null
}

$bootstrapProcess = $null
$peerStates = @{}
$sessionTag = Get-Date -Format "yyyyMMdd-HHmmss"

function Write-Log {
    param([string]$Message)
    $timestamp = Get-Date -Format "HH:mm:ss"
    Write-Host "[$timestamp] $Message"
}

function Test-HttpReady {
    param([string]$Url, [int]$TimeoutSec = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2 | Out-Null
            return $true
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    return $false
}

function Start-BootstrapServer {
    if (-not (Test-Path $bootstrapJar)) {
        throw "Missing bootstrap jar: $bootstrapJar"
    }

    $bootstrapLog = Join-Path $logRoot "bootstrap-$sessionTag.out.log"
    $bootstrapErrLog = Join-Path $logRoot "bootstrap-$sessionTag.err.log"
    $bootstrapProcess = Start-Process -FilePath $javaExe `
        -ArgumentList "-jar", $bootstrapJar, "--port", $BootstrapPort, "--dashboard-port", $DashboardPort `
        -WorkingDirectory $repoRoot `
        -RedirectStandardOutput $bootstrapLog `
        -RedirectStandardError $bootstrapErrLog `
        -WindowStyle Hidden `
        -PassThru

    if (-not (Test-HttpReady -Url "http://localhost:$DashboardPort/api/status")) {
        throw "Bootstrap dashboard did not become ready on port $DashboardPort"
    }

    Write-Log "Bootstrap started on ports $BootstrapPort/$DashboardPort (PID $($bootstrapProcess.Id))"
    return $bootstrapProcess
}

function New-PeerState {
    param([int]$Index)
    $name = "peer{0:D2}" -f $Index
    [pscustomobject]@{
        Name = $name
        PeerPort = $BasePeerPort + $Index - 1
        WebPort = $BaseWebPort + $Index - 1
        Process = $null
        Active = $false
        LastAction = "never"
        LogFile = Join-Path $logRoot "$name-$sessionTag.out.log"
        ErrorLogFile = Join-Path $logRoot "$name-$sessionTag.err.log"
    }
}

function Start-Peer {
    param([pscustomobject]$PeerState)

    if ($PeerState.Active -and $PeerState.Process -and -not $PeerState.Process.HasExited) {
        return
    }

    if (-not (Test-Path $peerJar)) {
        throw "Missing peer jar: $peerJar"
    }

    $PeerState.Process = Start-Process -FilePath $javaExe `
        -ArgumentList "-jar", $peerJar, "--port", $PeerState.PeerPort, "--web", $PeerState.WebPort `
        -WorkingDirectory $repoRoot `
        -RedirectStandardOutput $PeerState.LogFile `
        -RedirectStandardError $PeerState.ErrorLogFile `
        -WindowStyle Hidden `
        -PassThru

    if (-not (Test-HttpReady -Url "http://localhost:$($PeerState.WebPort)/api/info")) {
        throw "Peer UI for $($PeerState.Name) did not become ready on web port $($PeerState.WebPort)"
    }

    $registerBody = @{
        username = $PeerState.Name
        host = "localhost"
        bootstrapHost = "localhost"
        bootstrapPort = $BootstrapPort
    } | ConvertTo-Json

    $deadline = (Get-Date).AddSeconds(20)
    $registered = $false
    while ((Get-Date) -lt $deadline -and -not $registered) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing `
                -Method Post `
                -ContentType "application/json" `
                -Uri "http://localhost:$($PeerState.WebPort)/api/register" `
                -Body $registerBody `
                -TimeoutSec 4
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                $registered = $true
            }
        } catch {
            Start-Sleep -Milliseconds 700
        }
    }

    if (-not $registered) {
        throw "Peer $($PeerState.Name) could not register with bootstrap"
    }

    $PeerState.Active = $true
    $PeerState.LastAction = "started"
    Write-Log "Started $($PeerState.Name) on peer:$($PeerState.PeerPort) web:$($PeerState.WebPort) (PID $($PeerState.Process.Id))"
}

function Stop-Peer {
    param(
        [pscustomobject]$PeerState,
        [string]$Reason = "crashed"
    )

    if (-not $PeerState.Active) {
        return
    }

    if ($PeerState.Process -and -not $PeerState.Process.HasExited) {
        Stop-Process -Id $PeerState.Process.Id -Force
    }

    $PeerState.Active = $false
    $PeerState.LastAction = $Reason
    Write-Log "Stopped $($PeerState.Name) ($Reason)"
}

function Get-ActivePeers {
    return @($peerStates.Values | Where-Object { $_.Active })
}

function Get-InactivePeers {
    return @($peerStates.Values | Where-Object { -not $_.Active })
}

function Show-Summary {
    $active = @(Get-ActivePeers)
    $inactive = @(Get-InactivePeers)
    Write-Log ("Active peers: " + (($active.Name | Sort-Object) -join ", "))
    if ($inactive.Count -gt 0) {
        Write-Log ("Inactive peers: " + (($inactive.Name | Sort-Object) -join ", "))
    }
}

function Cleanup {
    if (-not $KeepPeersRunning) {
        foreach ($peer in $peerStates.Values) {
            if ($peer.Process -and -not $peer.Process.HasExited) {
                try {
                    Stop-Process -Id $peer.Process.Id -Force
                } catch {}
            }
        }
    }

    if ($StartBootstrap -and -not $KeepBootstrapRunning -and $bootstrapProcess -and -not $bootstrapProcess.HasExited) {
        try {
            Stop-Process -Id $bootstrapProcess.Id -Force
        } catch {}
    }
}

try {
    if ($InitialPeers -gt $PeerCount) {
        throw "InitialPeers cannot be greater than PeerCount"
    }

    for ($i = 1; $i -le $PeerCount; $i++) {
        $state = New-PeerState -Index $i
        $peerStates[$state.Name] = $state
    }

    if ($StartBootstrap) {
        $bootstrapProcess = Start-BootstrapServer
    } else {
        if (-not (Test-HttpReady -Url "http://localhost:$DashboardPort/api/status" -TimeoutSec 5)) {
            throw "Bootstrap dashboard is not reachable on http://localhost:$DashboardPort/api/status"
        }
        Write-Log "Using existing bootstrap on ports $BootstrapPort/$DashboardPort"
    }

    Write-Log "Starting initial peer set ($InitialPeers of $PeerCount)"
    for ($i = 1; $i -le $InitialPeers; $i++) {
        Start-Peer -PeerState $peerStates["peer{0:D2}" -f $i]
        Start-Sleep -Milliseconds 600
    }
    Show-Summary

    for ($round = 1; $round -le $TotalRounds; $round++) {
        Write-Log "Round $round/$TotalRounds"

        $activePeers = @(Get-ActivePeers)
        $inactivePeers = @(Get-InactivePeers)

        $shouldCrash = $activePeers.Count -gt 1
        $shouldJoin = $inactivePeers.Count -gt 0

        if ($shouldCrash) {
            $crashCount = [Math]::Min((Get-Random -Minimum 1 -Maximum ([Math]::Min(3, $activePeers.Count) + 1)), $activePeers.Count - 1)
            $victims = $activePeers | Get-Random -Count $crashCount
            foreach ($peer in @($victims)) {
                Stop-Peer -PeerState $peer -Reason "crash"
            }
        }

        Start-Sleep -Seconds ([Math]::Max(1, [Math]::Floor($RoundIntervalSec / 2)))

        $inactivePeers = @(Get-InactivePeers)
        if ($shouldJoin -and $inactivePeers.Count -gt 0) {
            $joinCount = [Math]::Min((Get-Random -Minimum 1 -Maximum ([Math]::Min(3, $inactivePeers.Count) + 1)), $inactivePeers.Count)
            $joiners = $inactivePeers | Get-Random -Count $joinCount
            foreach ($peer in @($joiners)) {
                Start-Peer -PeerState $peer
                Start-Sleep -Milliseconds 500
            }
        }

        Show-Summary
        if ($round -lt $TotalRounds) {
            Start-Sleep -Seconds $RoundIntervalSec
        }
    }

    Write-Log "Churn test completed. Dashboard: http://localhost:$DashboardPort"
    Write-Log "Log directory: $logRoot"
} finally {
    Cleanup
}
