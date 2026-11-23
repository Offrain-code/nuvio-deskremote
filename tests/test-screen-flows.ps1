<#
.SYNOPSIS
    End-to-End Screen Flow Script (Home -> Detail -> Player -> Search) and Full Suite for Nuvio DeskRemote.
.DESCRIPTION
    Runs the Win32 latency check, H.264 encoder benchmark, and nuvio.log state machine audit,
    and prints the screen-by-screen manual/automated verification checklist.
#>
param(
    [ValidateSet("Suite", "FlowDryRun", "FlowLive")]
    [string]$Action = "Suite"
)

$ErrorActionPreference = "Continue"
$testsDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "=====================================================================" -ForegroundColor Cyan
Write-Host "   NUVIO DESKREMOTE - END-TO-END VALIDATION SUITE                    " -ForegroundColor Cyan
Write-Host "=====================================================================" -ForegroundColor Cyan

if ($Action -eq "Suite") {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $testsDir "test-focus-and-input.ps1")
    Write-Host ""
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $testsDir "test-h264-encoders.ps1")
    Write-Host ""
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $testsDir "test-log-state-machine.ps1") -Mode Audit
    exit 0
}

# Per-screen test checklist (official Nuvio v1.16.1 mapping from SPEC.md)
$flowSteps = @(
    [PSCustomObject]@{ Step = 1;  Screen = "HOME";   Key = "H (VK 0x48)";          Action = "Go to Home";                         ExpectedLog = "(HomeHeroTrailer) homeActive=true | [nuvio-discord] Browsing Nuvio" },
    [PSCustomObject]@{ Step = 2;  Screen = "HOME";   Key = "Right/Down (0x27/28)"; Action = "Navigate carousel and grid";         ExpectedLog = "(HeroLogoRace) FOCUSED enrich start/done" },
    [PSCustomObject]@{ Step = 3;  Screen = "DETAIL"; Key = "Enter (VK 0x0D)";      Action = "Open focused title details";         ExpectedLog = "(HomeHeroTrailer) homeActive=false | [nuvio-discord] subtitle=Details" },
    [PSCustomObject]@{ Step = 4;  Screen = "DETAIL"; Key = "Enter (VK 0x0D)";      Action = "Select episode / stream source";     ExpectedLog = "(PlaybackStartTrace) beginPending: playClicked / open:streamPicker" },
    [PSCustomObject]@{ Step = 5;  Screen = "PLAYER"; Key = "Space / K (0x20/4B)";  Action = "Play / Pause in mpv";                ExpectedLog = "(PlaybackStartTrace) fileLoaded | [nuvio-discord] type=Playback" },
    [PSCustomObject]@{ Step = 6;  Screen = "PLAYER"; Key = "Right / Left";         Action = "Seek +5s / -5s";                     ExpectedLog = "mpv OSD seek" },
    [PSCustomObject]@{ Step = 7;  Screen = "PLAYER"; Key = "A / S / C / I";        Action = "Cycle Audio, Subtitles, Zoom, Stats"; ExpectedLog = "mpv track/aspect switch" },
    [PSCustomObject]@{ Step = 8;  Screen = "PLAYER"; Key = "Escape (VK 0x1B)";     Action = "Exit Player back to Detail";         ExpectedLog = "[nuvio-discord] type=Browsing subtitle=Details | POST /scrobble/stop" },
    [PSCustomObject]@{ Step = 9;  Screen = "SEARCH"; Key = "S (VK 0x53)";          Action = "Open Search screen";                 ExpectedLog = "[nuvio-discord] title=Searching / A pesquisar" },
    [PSCustomObject]@{ Step = 10; Screen = "SEARCH"; Key = "KEYEVENTF_UNICODE";    Action = "Inject search text ('vinland')";     ExpectedLog = "[nuvio-discord] title='Searching for vinland'" },
    [PSCustomObject]@{ Step = 11; Screen = "SEARCH"; Key = "Escape (VK 0x1B)";     Action = "Clear / Close Search -> Home";       ExpectedLog = "[nuvio-discord] title='Browsing Nuvio'" }
)

Write-Host "--- SCREEN FLOW TEST SCRIPT (HOME -> DETAIL -> PLAYER -> SEARCH) ---" -ForegroundColor Yellow
$flowSteps | Format-Table -AutoSize
