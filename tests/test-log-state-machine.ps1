<#
.SYNOPSIS
    State machine parser and validator against %LOCALAPPDATA%\NuvioHTPC\logs\nuvio.log.
.DESCRIPTION
    Validates non-blocking reads (FileShare.ReadWrite) on nuvio.log and nuvio.log.1, extracting:
    - Exact transitions to HOME, SEARCH, DETAIL, and PLAYER (via [nuvio-discord], HomeHeroTrailer, and PlaybackStartTrace).
    - Supports historical audit (-Mode Audit) or real-time tailing (-Mode LiveTail).
#>
param(
    [ValidateSet("Audit", "LiveTail")]
    [string]$Mode = "Audit",
    [int]$TailLines = 500
)

$ErrorActionPreference = "Stop"

$logDir = "$env:LOCALAPPDATA\NuvioHTPC\logs"
$mainLog = Join-Path $logDir "nuvio.log"
$rotLog = Join-Path $logDir "nuvio.log.1"

function Parse-NuvioLogLine {
    param([string]$Line)

    $timestamp = if ($Line.Length -ge 23) { $Line.Substring(0, 23) } else { "" }

    # 1. [nuvio-discord] IPC events (Deterministico para Home, Search, Detail, Player)
    if ($Line -match '\[nuvio-discord\] activity updated title="([^"]*)" subtitle="([^"]*)" type=([^\s]+) playing=([^\s]+)') {
        $title = $matches[1]
        $sub = $matches[2]
        $type = $matches[3]
        $playing = $matches[4]

        if ($type -eq "Playback") {
            return [PSCustomObject]@{ Timestamp = $timestamp; State = "PLAYER"; Trigger = "nuvio-discord"; Detail = "$title ($sub) [playing=$playing]" }
        }
        if ($title -match '^(Searching|A pesquisar)') {
            return [PSCustomObject]@{ Timestamp = $timestamp; State = "SEARCH"; Trigger = "nuvio-discord"; Detail = $title }
        }
        if ($sub -in @("Details", "Sources", "Person") -or $title -match '^(Choosing a stream|Starting stream|A iniciar stream|Viewing details)') {
            return [PSCustomObject]@{ Timestamp = $timestamp; State = "DETAIL"; Trigger = "nuvio-discord"; Detail = "$title [$sub]" }
        }
        if ($title -match '^(Browsing Nuvio|A explorar o Nuvio|Browsing Discover|A explorar Descobrir|Viewing Library|A ver a Biblioteca|Viewing Calendar|Browsing Live TV|A explorar TV em direto|Changing settings|A alterar)') {
            return [PSCustomObject]@{ Timestamp = $timestamp; State = "HOME"; Trigger = "nuvio-discord"; Detail = $title }
        }
    }

    # 2. (PlaybackStartTrace) - Entrada e ciclo de vida do Player mpv (independe de Discord)
    if ($Line -match '\(PlaybackStartTrace\) id=(\d+) (beginPending: playClicked|open:streamPicker|navigate:streamRoute)') {
        return [PSCustomObject]@{ Timestamp = $timestamp; State = "DETAIL"; Trigger = "PlaybackStartTrace"; Detail = "id=$($matches[1]) $($matches[2])" }
    }
    if ($Line -match '\(PlaybackStartTrace\) id=(\d+) (playerAttach|begin: playerAttach|fileLoaded|profileRendered|summary: fileLoaded)') {
        return [PSCustomObject]@{ Timestamp = $timestamp; State = "PLAYER"; Trigger = "PlaybackStartTrace"; Detail = "id=$($matches[1]) $($matches[2])" }
    }

    # 3. (HomeHeroTrailer) - Confirmacao de Home ativa vs inativa (independe de Discord)
    if ($Line -match '\(HomeHeroTrailer\) gate .* homeActive=true .* key=([^\s]+)') {
        return [PSCustomObject]@{ Timestamp = $timestamp; State = "HOME"; Trigger = "HomeHeroTrailer"; Detail = "homeActive=true ($($matches[1]))" }
    }

    return $null
}

Write-Host "=== LOG & STATE MACHINE VALIDATION (%LOCALAPPDATA%\NuvioHTPC\logs) ===" -ForegroundColor Cyan

if (-not (Test-Path $mainLog)) {
    Write-Host "[FAIL] File $mainLog not found." -ForegroundColor Red
    exit 1
}

# Validate concurrent non-blocking open with FileShare.ReadWrite (never locks Nuvio)
$fs = [System.IO.File]::Open($mainLog, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
$lenMB = [math]::Round($fs.Length / 1MB, 2)
$fs.Close()
Write-Host "[PASS] Concurrent read (FileShare.ReadWrite) validated on nuvio.log ($lenMB MB)." -ForegroundColor Green

if ($Mode -eq "Audit") {
    $targetFile = if (Test-Path $rotLog) { $rotLog } else { $mainLog }
    Write-Host "[INFO] Extracting state transitions from $(Split-Path $targetFile -Leaf)..." -ForegroundColor Cyan

    $transitions = New-Object System.Collections.Generic.List[object]
    $lastState = ""
    foreach ($line in [System.IO.File]::ReadLines($targetFile)) {
        $ev = Parse-NuvioLogLine -Line $line
        if ($ev -and ($ev.State -ne $lastState -or $ev.Trigger -eq "nuvio-discord")) {
            if ($ev.State -ne $lastState) {
                $transitions.Add($ev)
                $lastState = $ev.State
            }
        }
    }

    $summary = $transitions | Group-Object State | Select-Object @{N='Detected_State';E={$_.Name}}, @{N='Verified_Transitions';E={$_.Count}}
    $summary | Format-Table -AutoSize

    Write-Host "--- Last 15 Flow Transitions (Home -> Search -> Detail -> Player) ---" -ForegroundColor Cyan
    $transitions | Select-Object -Last 15 | Format-Table -AutoSize
} else {
    Write-Host "[INFO] Tailing $mainLog in real time (Press Ctrl+C to stop)..." -ForegroundColor Yellow
    Get-Content -Path $mainLog -Wait -Tail 10 | ForEach-Object {
        $ev = Parse-NuvioLogLine -Line $_
        if ($ev) {
            Write-Host ("[{0}] STATE:  {1,-7} | Trigger: {2,-18} | {3}" -f $ev.Timestamp, $ev.State, $ev.Trigger, $ev.Detail) -ForegroundColor Green
        }
    }
}
