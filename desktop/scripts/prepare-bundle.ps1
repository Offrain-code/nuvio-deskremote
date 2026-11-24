<#
.SYNOPSIS
    Stages the portable Node.js Companion server inside desktop/src-tauri/resources/server before Tauri v2 builds.
#>
param(
    [string]$NodeExe = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$serverDir = Join-Path $repoRoot "server"
$resServer = Join-Path $repoRoot "desktop\src-tauri\resources\server"

if (-not $NodeExe) {
    $cmd = Get-Command node.exe -ErrorAction SilentlyContinue
    if (-not $cmd) { throw "node.exe not found in PATH." }
    $NodeExe = $cmd.Source
}

Write-Host "[1/4] Building Companion TypeScript server..." -ForegroundColor Cyan
Push-Location $serverDir
try {
    if (-not (Test-Path (Join-Path $serverDir "node_modules"))) {
        npm ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed." }
    }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "npm run build failed." }
} finally {
    Pop-Location
}

Write-Host "[2/4] Cleaning resources/server..." -ForegroundColor Cyan
if (Test-Path $resServer) { Remove-Item $resServer -Recurse -Force }
New-Item -ItemType Directory -Path $resServer -Force | Out-Null

Write-Host "[3/4] Copying dist, compiled WASAPI helper, bin, package.json, and portable node.exe..." -ForegroundColor Cyan
Copy-Item -Path (Join-Path $serverDir "dist") -Destination (Join-Path $resServer "dist") -Recurse -Force
$resBin = Join-Path $resServer "bin"
New-Item -ItemType Directory -Path $resBin -Force | Out-Null

# Compile WasapiLoopback.cs into nuvio-audio-capture.exe if csc.exe is available on Windows
$wasapiSrc = Join-Path $serverDir "bin\WasapiLoopback.cs"
$wasapiOut = Join-Path $resBin "nuvio-audio-capture.exe"
$cscPath = @(
    "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
    "$env:WINDIR\Microsoft.NET\Framework\v4.0.30319\csc.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1

if ((Test-Path $wasapiSrc) -and $cscPath) {
    Write-Host "      Compiling WasapiLoopback.cs -> nuvio-audio-capture.exe..." -ForegroundColor DarkCyan
    & $cscPath /nologo /optimize+ /target:exe /out:$wasapiOut $wasapiSrc
    if ($LASTEXITCODE -ne 0) { throw "Failed to compile WasapiLoopback.cs with csc.exe." }
} elseif (Test-Path (Join-Path $serverDir "bin\nuvio-audio-capture.exe")) {
    Copy-Item -Path (Join-Path $serverDir "bin\nuvio-audio-capture.exe") -Destination $wasapiOut -Force
}

Get-ChildItem -Path (Join-Path $serverDir "bin") -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notin @("cloudflared.exe", "ffmpeg.exe", "WasapiLoopback.cs", "nuvio-audio-capture.exe") } |
    ForEach-Object { Copy-Item -Path $_.FullName -Destination $resBin -Force }
Copy-Item -Path (Join-Path $serverDir "package.json") -Destination $resServer -Force
Copy-Item -Path (Join-Path $serverDir "package-lock.json") -Destination $resServer -Force
Copy-Item -Path $NodeExe -Destination (Join-Path $resServer "node.exe") -Force

Write-Host "[4/4] Installing production dependencies into resources/server..." -ForegroundColor Cyan
Push-Location $resServer
try {
    npm ci --omit=dev
    if ($LASTEXITCODE -ne 0) { throw "npm ci --omit=dev failed." }
} finally {
    Pop-Location
}

Write-Host "[OK] Bundle ready at $resServer" -ForegroundColor Green
