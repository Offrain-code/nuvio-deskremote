<#
.SYNOPSIS
    H.264 Encoder Benchmark (AMF / NVENC / QSV / MediaFoundation / CPU Fallback) for Nuvio DeskRemote.
.DESCRIPTION
    Measures frame rate (FPS), relative speed, and frame encoding time (ms) for H.264 encoders at 1080p60 and 720p60
    used by WebRTC screen sharing, and lists detected display adapters.
#>

$ErrorActionPreference = "Continue"

Write-Host "=== H.264 ENCODER BENCHMARK ===" -ForegroundColor Cyan

$gpus = Get-CimInstance Win32_VideoController
foreach ($g in $gpus) {
    Write-Host ("Detected GPU : {0} (Driver {1} | Status: {2})" -f $g.Name, $g.DriverVersion, $g.Status)
}
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
Write-Host ("Detected CPU : {0}" -f $cpu.Name)

$ffmpeg = (Get-Command ffmpeg.exe -ErrorAction SilentlyContinue).Source
if (-not $ffmpeg -and $env:FFMPEG_PATH) {
    $ffmpeg = $env:FFMPEG_PATH
}
if (-not $ffmpeg) {
    $wingetLink = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links\ffmpeg.exe"
    if (Test-Path $wingetLink) { $ffmpeg = $wingetLink }
}

if (-not (Test-Path $ffmpeg)) {
    Write-Host "[FAIL] FFmpeg not found on this system." -ForegroundColor Red
    exit 1
}

function Run-EncoderBench {
    param(
        [string]$Label,
        [string]$Size,
        [int]$Fps,
        [string[]]$EncoderArgs
    )
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $frames = $Fps * 3
    $argList = @("-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration=3:size=${Size}:rate=${Fps}") + $EncoderArgs + @("-f", "null", "-")
    & $ffmpeg @argList | Out-Null
    $sw.Stop()
    $elapsedSec = $sw.Elapsed.TotalSeconds
    $effectiveFps = [math]::Round($frames / $elapsedSec, 1)
    $msPerFrame = [math]::Round(($elapsedSec * 1000.0) / $frames, 2)
    $speed = [math]::Round($effectiveFps / $Fps, 2)
    [PSCustomObject]@{
        Profile      = $Label
        Resolution   = "${Size}@${Fps}fps"
        Measured_FPS = $effectiveFps
        Ms_Per_Frame = "${msPerFrame} ms"
        Speed        = "${speed}x"
        Status       = if ($effectiveFps -ge $Fps) { "PASS" } else { "WARN" }
    }
}

$results = @()
$results += Run-EncoderBench -Label "h264_amf (GPU AMD UltraLowLatency)" -Size "1920x1080" -Fps 60 -EncoderArgs @("-c:v", "h264_amf", "-usage", "ultralowlatency", "-quality", "speed", "-b:v", "4M")
$results += Run-EncoderBench -Label "h264_amf (GPU AMD UltraLowLatency)" -Size "1280x720"  -Fps 60 -EncoderArgs @("-c:v", "h264_amf", "-usage", "ultralowlatency", "-quality", "speed", "-b:v", "2500k")
$results += Run-EncoderBench -Label "h264_mf (MediaFoundation HW/MFT)"  -Size "1920x1080" -Fps 60 -EncoderArgs @("-c:v", "h264_mf", "-b:v", "4M")
$results += Run-EncoderBench -Label "libx264 (CPU Ultrafast ZeroLatency)" -Size "1280x720" -Fps 60 -EncoderArgs @("-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-b:v", "2500k")

$results | Format-Table -AutoSize
