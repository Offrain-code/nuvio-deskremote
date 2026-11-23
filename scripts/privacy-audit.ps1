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
    @{ Name = "PersonalUserPath"; Regex = "C:[\\/]{1,2}Users[\\/]{1,2}[A-Za-z0-9._-]+" },
    @{ Name = "LocalDriveToolchain"; Regex = "E:[\\/]{1,2}Ferramentas|G:[\\/]{1,2}Meu Drive" },
    @{ Name = "PersonalUsername"; Regex = "\bLeuu\b|Curicica|PC-Leo|Galaxy S23 do Leo|\bdo Leo\b|\bo Leo\b" },
    @{ Name = "PersonalLanIp"; Regex = "\b192\.168\.1\.64\b|\b192\.168\.1\.105\b" },
    @{ Name = "LegacyPersonalTunnel"; Regex = "was-automatic-inquiries-register\.trycloudflare\.com" },
    @{ Name = "PersonalHardwareSpecs"; Regex = "RX 6600M|E5-2650|NuvioCodeineXO|Poco C65" },
    @{ Name = "InternalAgentNames"; Regex = "\b(Geraldo Sites|Kleber Artes|Silvinha Segurança|Alberto do TI|Agostinho|OpenBot|Luma Design|Nuno Remote)\b" },
    @{ Name = "LeakedSessionToken"; Regex = "nuvio_sec_[0-9a-fA-F]{32,}" }
)

$documentedExampleToken = "nuvio_sec_7f9c3a1b8e4d2091c5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8"

$excludedDirs = '[\\/](node_modules|\.git|\.gradle|\.kotlin|\.idea|build|dist|target)[\\/]'
$excludedExts = @('.apk', '.zip', '.exe', '.dll', '.jar', '.png', '.jpg', '.jpeg', '.webp', '.ico', '.log', '.old', '.jks', '.keystore')
$excludedFiles = @('local.properties', 'package-lock.json', 'privacy-audit.ps1')
$excludedFilePatterns = @('build-log.*\.txt$')

$findings = @()

# 1. Fail if any sensitive/log/keystore/secret file is tracked in Git
if (Get-Command git -ErrorAction SilentlyContinue) {
    $trackedFiles = @(git -C $RootDir ls-files 2>$null)
    $forbiddenTrackedRegex = '(?i)(\.log(\.old|\.\d+)?$|\.jks$|\.keystore$|\.pem$|\.key$|(^|[\\/])local\.properties$|(^|[\\/])paired-devices\.json$|(^|[\\/])nuvio-bridge\.json$|(^|[\\/])\.env(\.|$)|^server/config\.json$)'
    foreach ($tf in $trackedFiles) {
        if ($tf -match $forbiddenTrackedRegex) {
            $findings += [PSCustomObject]@{
                Rule = "ForbiddenTrackedFile"
                File = $tf
                Line = 0
                Snippet = "Sensitive or local runtime file tracked in git index"
            }
        }
    }
}

$files = Get-ChildItem -Path $RootDir -Recurse -File | Where-Object {
    if ($_.FullName -match $excludedDirs) { return $false }
    if ($excludedExts -contains $_.Extension.ToLowerInvariant()) { return $false }
    if ($excludedFiles -contains $_.Name) { return $false }
    foreach ($fp in $excludedFilePatterns) {
        if ($_.Name -match $fp) { return $false }
    }
    return $true
}

foreach ($file in $files) {
    $rel = $file.FullName.Substring($RootDir.Length).TrimStart('\', '/')
    $relNormalized = $rel.Replace('\', '/')
    $lines = @(Get-Content -Path $file.FullName -Encoding UTF8 -ErrorAction SilentlyContinue)
    if ($lines.Count -eq 0) { continue }
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = [string]$lines[$i]
        foreach ($p in $patterns) {
            if ($line -match $p.Regex) {
                # Allow only the exact documented example token in SECURITY.md
                if ($p.Name -eq "LeakedSessionToken" -and $relNormalized -eq "SECURITY.md") {
                    $matchesInLine = [regex]::Matches($line, $p.Regex)
                    $allExample = ($matchesInLine.Count -gt 0)
                    foreach ($m in $matchesInLine) {
                        if ($m.Value -ne $documentedExampleToken) { $allExample = $false }
                    }
                    if ($allExample) { continue }
                }
                $findings += [PSCustomObject]@{
                    Rule = $p.Name
                    File = $relNormalized
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
