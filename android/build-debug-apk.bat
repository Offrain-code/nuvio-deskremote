@echo off
setlocal EnableDelayedExpansion

set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

if not defined TOOLCHAIN_ROOT (
    if defined NUVIO_ANDROID_TOOLCHAIN (
        set "TOOLCHAIN_ROOT=%NUVIO_ANDROID_TOOLCHAIN%"
    ) else (
        set "TOOLCHAIN_ROOT=%LOCALAPPDATA%\NuvioDeskRemote\AndroidBuild"
    )
)
if not defined ANDROID_SDK_ROOT (
    set "SDK_ROOT=%TOOLCHAIN_ROOT%\sdk"
) else (
    set "SDK_ROOT=%ANDROID_SDK_ROOT%"
)
if not defined GRADLE_USER_HOME (
    set "GRADLE_USER_HOME=%TOOLCHAIN_ROOT%\gradle-home"
)

:: 1. Ensure the portable Android toolchain is provisioned
if not exist "%SDK_ROOT%\platforms\android-35" (
    echo [INFO] Provisioning portable Android toolchain in %TOOLCHAIN_ROOT%...
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%\setup-android-toolchain.ps1" -ToolchainRoot "%TOOLCHAIN_ROOT%" -ProjectDir "%PROJECT_DIR%"
    if errorlevel 1 (
        echo [ERROR] Failed to provision the Android SDK.
        exit /b 1
    )
)

:: 2. Locate JDK 21 (uses JAVA_HOME if set, otherwise searches TOOLCHAIN_ROOT\jdk-21)
if not defined JAVA_HOME (
    for /f "delims=" %%J in ('dir /b /s "%TOOLCHAIN_ROOT%\jdk-21\javac.exe" 2^>nul') do (
        for %%P in ("%%~dpJ..") do set "JAVA_HOME=%%~fP"
    )
)

if not defined JAVA_HOME (
    echo [ERROR] JDK 21 not found. Set JAVA_HOME or run setup-android-toolchain.ps1.
    exit /b 1
)

set "ANDROID_HOME=%SDK_ROOT%"
set "ANDROID_SDK_ROOT=%SDK_ROOT%"
set "PATH=%JAVA_HOME%\bin;%SDK_ROOT%\platform-tools;%PATH%"

:: 3. Ensure local.properties points to SDK_ROOT
set "SDK_ESCAPED=%SDK_ROOT:\=\\%"
echo sdk.dir=%SDK_ESCAPED%> "%PROJECT_DIR%\local.properties"

:: 4. Run optimized assembleRelease (R8 + Compose AOT, signed with release or fallback debug keystore)
pushd "%PROJECT_DIR%"
if exist "%PROJECT_DIR%\gradlew.bat" (
    call "%PROJECT_DIR%\gradlew.bat" assembleRelease --no-daemon --stacktrace
) else (
    for /f "delims=" %%G in ('dir /b /s "%TOOLCHAIN_ROOT%\gradle\gradle.bat" 2^>nul') do set "GRADLE_BIN=%%G"
    if not defined GRADLE_BIN (
        echo [ERROR] Neither gradlew.bat nor portable Gradle was found.
        popd
        exit /b 1
    )
    call "!GRADLE_BIN!" assembleRelease --no-daemon --stacktrace
)
set "BUILD_EXIT=%ERRORLEVEL%"
popd

if not "%BUILD_EXIT%"=="0" (
    echo [ERROR] assembleRelease build failed with exit code %BUILD_EXIT%.
    exit /b %BUILD_EXIT%
)

:: 5. Copy the generated release APK to the android/ root (nuvio-remote-release.apk and /apk compatibility alias)
if exist "%PROJECT_DIR%\app\build\outputs\apk\release\app-release.apk" (
    copy /Y "%PROJECT_DIR%\app\build\outputs\apk\release\app-release.apk" "%PROJECT_DIR%\nuvio-remote-release.apk" >nul
    copy /Y "%PROJECT_DIR%\app\build\outputs\apk\release\app-release.apk" "%PROJECT_DIR%\nuvio-remote-debug.apk" >nul
    echo [OK] Optimized release APK ready at: %PROJECT_DIR%\nuvio-remote-release.apk
)
exit /b 0
