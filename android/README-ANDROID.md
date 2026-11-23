# Nuvio DeskRemote — Android App (Jetpack Compose)

Native Android application built with Kotlin 2.0.20 + Jetpack Compose using the **Liquid Glass** design system (zero Material 3), strict separation between **Nuvio Mode** (`Remote`, `Apps`) and **PC Mode** (`Trackpad`, `Screen`), plus `Settings` with a live **System / English / Português (BR)** language switcher.

## Build Requirements
- JDK 17 or JDK 21 LTS (`JAVA_HOME`)
- Android SDK 35 (`ANDROID_HOME` / `ANDROID_SDK_ROOT`)
- Gradle Wrapper (`./gradlew`)

## Build & Verify

```bash
cd android
./gradlew ktlintCheck detekt lintDebug testDebugUnitTest
./gradlew assembleDebug
```

On Windows without Android Studio installed, you can use the portable CLI build script:

```cmd
set NUVIO_ANDROID_TOOLCHAIN=<toolchain-directory>
android\build-debug-apk.bat
```

## Main Modules (`com.nuvio.remote`)
- `connection/`: WebSocket + low-latency binary fast-path, native `PhoneRemote` bridge (catalog, player, music, lyrics, Live TV, Tonight), QR pairing parser, and Wake-on-LAN.
- `discovery/`: Automatic local network discovery via NSD (`_nuvio-remote._tcp`) and UDP broadcast (port `8766`).
- `pairing/`: 6-digit PIN pairing (`LAN_ONLY`) with `EncryptedSharedPreferences` token persistence.
- `webrtc/`: Low-latency H.264 WebRTC video decoding (`KEY_LOW_LATENCY`).
- `media/`, `share/`, `tile/`: Lock-screen media controls (`MediaSession`), Android Share target ("Send to Nuvio"), and Quick Settings Tile.
- `ui/`: Unified Liquid Glass theme (`NuvioGlassTheme.kt`), bilingual `ui/i18n/AppI18n.kt` (`EN` default + `PT-BR`), all 5 tab screens, Cinema OLED Mode, and offline QR scanner (CameraX + ZXing).
