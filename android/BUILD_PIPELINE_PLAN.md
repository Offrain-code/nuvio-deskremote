# Portable Android CLI Build Pipeline — Nuvio DeskRemote

This guide explains how to provision and build the Android application from the Windows command line without installing Android Studio.

## Prerequisites
- Windows 10/11 (x64)
- PowerShell 5.1+
- JDK 17 or JDK 21 LTS (avoid JDK 25+ due to AGP 8.7.2 compatibility)

## Scripts in `android/`
- [`setup-android-toolchain.ps1`](setup-android-toolchain.ps1): downloads and extracts Eclipse Temurin JDK 21 LTS, Android Command-line Tools, Android SDK 35 (`platform-tools`, `platforms;android-35`, `build-tools;35.0.0`), and Gradle into a portable directory (default `%LOCALAPPDATA%\NuvioDeskRemote\AndroidBuild`, configurable via `-ToolchainRoot` or `NUVIO_ANDROID_TOOLCHAIN`), and writes `android/local.properties` (git-ignored).
- [`build-debug-apk.bat`](build-debug-apk.bat): configures `JAVA_HOME`, `ANDROID_HOME`, and `GRADLE_USER_HOME`, runs `assembleRelease`, and copies the output APK into `android/`.

## Commands

```cmd
:: Optional: choose a custom directory/drive for the toolchain and Gradle cache
set NUVIO_ANDROID_TOOLCHAIN=C:\AndroidBuild

:: Provision (first run only) and build
android\build-debug-apk.bat
```

## Mandatory Quality Gate

```cmd
cd android
gradlew.bat ktlintCheck detekt lintDebug testDebugUnitTest
```
