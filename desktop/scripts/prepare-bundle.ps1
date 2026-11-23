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
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "npm run build failed." }
} finally {
    Pop-Location
}

Write-Host "[2/4] Cleaning resources/server..." -ForegroundColor Cyan
if (Test-Path $resServer) { Remove-Item $resServer -Recurse -Force }
New-Item -ItemType Directory -Path $resServer -Force | Out-Null

Write-Host "[3/4] Copying dist, bin, package.json, and portable node.exe..." -ForegroundColor Cyan
Copy-Item -Path (Join-Path $serverDir "dist") -Destination (Join-Path $resServer "dist") -Recurse -Force
$resBin = Join-Path $resServer "bin"
New-Item -ItemType Directory -Path $resBin -Force | Out-Null
Get-ChildItem -Path (Join-Path $serverDir "bin") -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notin @("cloudflared.exe", "ffmpeg.exe") } |
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
