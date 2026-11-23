<#
.SYNOPSIS
    Automated privacy and hygiene audit for the Nuvio DeskRemote repository.
.DESCRIPTION
    Scans all trackable files (excluding node_modules, dist, build, logs, binaries, and local.properties)
    for personal local paths, fixed LAN IPs, personal/internal names, hardware specs, and session tokens.
    Exits with code 1 if any match is found.
#>
param(
    [string]$RootDir = (Split-Path $PSScriptRoot -Parent)
)

$ErrorActionPreference = "Stop"

$patterns = @(
    @{ Name = "PersonalUserPath"; Regex = "C:\\\\?Users\\\\?[A-Za-z0-9._-]+" },
    @{ Name = "LocalDriveToolchain"; Regex = "E:\\\\?Ferramentas|G:\\\\?Meu Drive" },
    @{ Name = "PersonalUsername"; Regex = "\bLeuu\b|Curicica|PC-Leo|Galaxy S23 do Leo|\bdo Leo\b|\bo Leo\b" },
    @{ Name = "PersonalLanIp"; Regex = "\b192\.168\.1\.64\b|\b192\.168\.1\.105\b" },
    @{ Name = "LegacyPersonalTunnel"; Regex = "was-automatic-inquiries-register\.trycloudflare\.com" },
    @{ Name = "PersonalHardwareSpecs"; Regex = "RX 6600M|E5-2650|NuvioCodeineXO|Poco C65" },
    @{ Name = "InternalAgentNames"; Regex = "\b(Geraldo Sites|Kleber Artes|Silvinha Segurança|Alberto do TI|Agostinho)\b" },
    @{ Name = "LeakedSessionToken"; Regex = "nuvio_sec_[0-9a-fA-F]{32,}" }
)

$excludedDirs = '\\(node_modules|\.git|\.gradle|\.kotlin|\.idea|build|dist|target)\\'
$excludedExts = @('.apk', '.zip', '.exe', '.dll', '.jar', '.png', '.jpg', '.jpeg', '.webp', '.ico', '.log', '.old', '.jks', '.keystore')
$excludedFiles = @('local.properties', 'package-lock.json', 'privacy-audit.ps1')
$excludedFilePatterns = @('build-log.*\.txt$')

$files = Get-ChildItem -Path $RootDir -Recurse -File | Where-Object {
    $rel = $_.FullName.Substring($RootDir.Length).TrimStart('\')
    if ($_.FullName -match $excludedDirs) { return $false }
    if ($excludedExts -contains $_.Extension.ToLowerInvariant()) { return $false }
    if ($excludedFiles -contains $_.Name) { return $false }
    foreach ($fp in $excludedFilePatterns) {
        if ($_.Name -match $fp) { return $false }
    }
    return $true
}

$findings = @()
foreach ($file in $files) {
    $rel = $file.FullName.Substring($RootDir.Length).TrimStart('\')
    $lines = Get-Content -Path $file.FullName -Encoding UTF8 -ErrorAction SilentlyContinue
    if (-not $lines) { continue }
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        foreach ($p in $patterns) {
            if ($line -match $p.Regex) {
                # Allow documented example token in SECURITY.md (line with 7f9c3a1b...)
                if ($p.Name -eq "LeakedSessionToken" -and $rel -eq "SECURITY.md") { continue }
                $findings += [PSCustomObject]@{
                    Rule = $p.Name
                    File = $rel
                    Line = $i + 1
                    Snippet = $line.Trim().Substring(0, [Math]::Min(120, $line.Trim().Length))
                }
            }
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host "[FAIL] Found $($findings.Count) personal/sensitive occurrence(s):" -ForegroundColor Red
    $findings | Format-Table -AutoSize
    exit 1
}

Write-Host "[PASS] Privacy audit clean ($($files.Count) files scanned; 0 findings)." -ForegroundColor Green
exit 0
