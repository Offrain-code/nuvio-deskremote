# stream-webrtc (io.getstream:stream-webrtc-android)
-dontwarn org.webrtc.**
-keep class org.webrtc.** { *; }

# Tink / security-crypto annotations (compileOnly errorprone)
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
