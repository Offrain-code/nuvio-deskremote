<#
.SYNOPSIS
    Silent portable provisioning for the Android build environment (JDK 21 LTS + Command-line Tools + SDK 35 + Gradle 8.10.2).
.DESCRIPTION
    Installs all packages, Gradle caches, and the Android SDK into a portable directory (default: %LOCALAPPDATA%\NuvioDeskRemote\AndroidBuild or -ToolchainRoot).
    Does not modify global Windows environment variables and does not require Android Studio.
#>
param(
    [string]$ToolchainRoot = $(if ($env:NUVIO_ANDROID_TOOLCHAIN) { $env:NUVIO_ANDROID_TOOLCHAIN } else { Join-Path $env:LOCALAPPDATA "NuvioDeskRemote\AndroidBuild" }),
    [string]$ProjectDir = $PSScriptRoot
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$jdkRoot    = Join-Path $ToolchainRoot "jdk-21"
$sdkRoot    = Join-Path $ToolchainRoot "sdk"
$gradleRoot = Join-Path $ToolchainRoot "gradle"
$gradleHome = Join-Path $ToolchainRoot "gradle-home"
$tmpDir     = Join-Path $ToolchainRoot "tmp"

New-Item -ItemType Directory -Path $ToolchainRoot, $sdkRoot, $gradleRoot, $gradleHome, $tmpDir -Force | Out-Null

Write-Host "=== [1/4] CHECKING PORTABLE JDK 21 LTS (COMPATIBLE WITH AGP / KOTLIN) ===" -ForegroundColor Cyan
$javaExe = Get-ChildItem -Path $jdkRoot -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $javaExe) {
    $jdkZip = Join-Path $tmpDir "temurin21.zip"
    $jdkUrl = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
    Write-Host "Downloading Eclipse Temurin JDK 21 LTS (~195 MB) to $jdkZip..."
    Invoke-WebRequest -Uri $jdkUrl -OutFile $jdkZip -UseBasicParsing
    Expand-Archive -Path $jdkZip -DestinationPath $jdkRoot -Force
    Remove-Item $jdkZip -Force
    $javaExe = Get-ChildItem -Path $jdkRoot -Filter "java.exe" -Recurse | Select-Object -First 1
}
$javaHome = Split-Path (Split-Path $javaExe.FullName -Parent) -Parent
$env:JAVA_HOME = $javaHome
Write-Host "[OK] JAVA_HOME = $javaHome" -ForegroundColor Green

Write-Host "=== [2/4] CHECKING ANDROID COMMAND-LINE TOOLS & SDK 35 ===" -ForegroundColor Cyan
$sdkManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkManager)) {
    $cmdZip = Join-Path $tmpDir "commandlinetools.zip"
    $cmdUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    Write-Host "Downloading Android Command-line Tools (~147 MB)..."
    Invoke-WebRequest -Uri $cmdUrl -OutFile $cmdZip -UseBasicParsing
    $extractTmp = Join-Path $tmpDir "cmdline-extract"
    Expand-Archive -Path $cmdZip -DestinationPath $extractTmp -Force
    $latestDir = Join-Path $sdkRoot "cmdline-tools\latest"
    New-Item -ItemType Directory -Path (Split-Path $latestDir -Parent) -Force | Out-Null
    if (Test-Path $latestDir) { Remove-Item $latestDir -Recurse -Force }
    Move-Item -Path (Join-Path $extractTmp "cmdline-tools") -Destination $latestDir -Force
    Remove-Item $cmdZip, $extractTmp -Recurse -Force -ErrorAction SilentlyContinue
}

$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot

$platform35 = Join-Path $sdkRoot "platforms\android-35"
$buildTools35 = Join-Path $sdkRoot "build-tools\35.0.0"
if (-not ((Test-Path $platform35) -and (Test-Path $buildTools35))) {
    Write-Host "Accepting licenses and installing platform-tools, platforms;android-34/35 and build-tools;34.0.0/35.0.0..."
    $yesInput = ("y`r`n" * 40)
    $yesInput | & $sdkManager --sdk_root="$sdkRoot" --licenses | Out-Null
    $yesInput | & $sdkManager --sdk_root="$sdkRoot" "platform-tools" "platforms;android-34" "platforms;android-35" "build-tools;34.0.0" "build-tools;35.0.0"
}
Write-Host "[OK] ANDROID_HOME = $sdkRoot" -ForegroundColor Green

Write-Host "=== [3/4] CHECKING PORTABLE GRADLE 8.10.2 ===" -ForegroundColor Cyan
$gradleBat = Get-ChildItem -Path $gradleRoot -Filter "gradle.bat" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $gradleBat) {
    $gradleZip = Join-Path $tmpDir "gradle-8.10.2-bin.zip"
    $gradleUrl = "https://services.gradle.org/distributions/gradle-8.10.2-bin.zip"
    Write-Host "Downloading Gradle 8.10.2 (~132 MB)..."
    Invoke-WebRequest -Uri $gradleUrl -OutFile $gradleZip -UseBasicParsing
    Expand-Archive -Path $gradleZip -DestinationPath $gradleRoot -Force
    Remove-Item $gradleZip -Force
    $gradleBat = Get-ChildItem -Path $gradleRoot -Filter "gradle.bat" -Recurse | Select-Object -First 1
}
Write-Host "[OK] Gradle CLI = $($gradleBat.FullName)" -ForegroundColor Green

Write-Host "=== [4/4] WRITING local.properties IN $ProjectDir ===" -ForegroundColor Cyan
$escapedSdk = $sdkRoot.Replace("\", "\\")
Set-Content -Path (Join-Path $ProjectDir "local.properties") -Value "sdk.dir=$escapedSdk" -Encoding ASCII
Write-Host "[PASS] Portable Android build environment ready in $ToolchainRoot." -ForegroundColor Green
