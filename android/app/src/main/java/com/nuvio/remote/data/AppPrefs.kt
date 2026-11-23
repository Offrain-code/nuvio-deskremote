package com.nuvio.remote.data

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.remote.connection.NuvioWakeInfo

/**
 * Persistent application preferences stored in SharedPreferences.
 */
class AppPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("nuvio_remote_settings", Context.MODE_PRIVATE)

    /** Display language wire value ("system", "en", or "pt-BR"). */
    var appLanguage: String
        get() = sp.getString(K_LANGUAGE, "system") ?: "system"
        set(v) = sp.edit().putString(K_LANGUAGE, v).apply()

    var mouseSensitivity: Float
        get() = sp.getFloat(K_SENS, 1.6f)
        set(v) = sp.edit().putFloat(K_SENS, v).apply()

    var hapticsEnabled: Boolean
        get() = sp.getBoolean(K_HAPTICS, true)
        set(v) = sp.edit().putBoolean(K_HAPTICS, v).apply()

    var hapticStrength: Float
        get() = sp.getFloat(K_HAPTIC_STRENGTH, 1.0f)
        set(v) = sp.edit().putFloat(K_HAPTIC_STRENGTH, v).apply()

    var screenProfile: String
        get() = sp.getString(K_PROFILE, "720p30") ?: "720p30"
        set(v) = sp.edit().putString(K_PROFILE, v).apply()

    var screenMonitor: Int
        get() = sp.getInt(K_MONITOR, 1)
        set(v) = sp.edit().putInt(K_MONITOR, v).apply()

    /** true = direct touch (clicks where tapped), false = relative touchpad cursor. */
    var screenDirectTouch: Boolean
        get() = sp.getBoolean(K_DIRECT, true)
        set(v) = sp.edit().putBoolean(K_DIRECT, v).apply()

    /** true = live PC audio enabled during screen share, false = muted. */
    var screenAudioEnabled: Boolean
        get() = sp.getBoolean(K_AUDIO, true)
        set(v) = sp.edit().putBoolean(K_AUDIO, v).apply()

    /** Last known local LAN IP of the PC Companion, prioritized when on home Wi-Fi. */
    var savedLanHost: String
        get() = sp.getString(K_LAN_HOST, "")
            ?.trim()
            .orEmpty()
        set(v) = sp.edit().putString(K_LAN_HOST, v.trim()).apply()

    /** Last Cloudflare Quick Tunnel URL received from the server for remote access. */
    var savedTunnelUrl: String
        get() = sp.getString(K_TUNNEL, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TUNNEL
        set(v) = sp.edit().putString(K_TUNNEL, v.trim()).apply()

    fun savedWakeInfo(): NuvioWakeInfo? {
        val macs = sp.getString(K_WAKE_MACS, "")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (macs.isEmpty()) return null
        val broadcasts = sp.getString(K_WAKE_BROADCASTS, "")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return NuvioWakeInfo(macs = macs, broadcasts = broadcasts)
    }

    fun saveWakeInfo(info: NuvioWakeInfo) {
        if (info.macs.isEmpty()) return
        sp.edit()
            .putString(K_WAKE_MACS, info.macs.joinToString(","))
            .putString(K_WAKE_BROADCASTS, info.broadcasts.joinToString(","))
            .apply()
    }

    companion object {
        const val DEFAULT_TUNNEL = ""
        const val K_LANGUAGE = "app_language"
        const val K_SENS = "mouse_sensitivity"
        const val K_HAPTICS = "haptics_enabled"
        const val K_HAPTIC_STRENGTH = "haptic_strength"
        const val K_PROFILE = "screen_profile"
        const val K_MONITOR = "screen_monitor"
        const val K_DIRECT = "screen_direct_touch"
        const val K_AUDIO = "screen_audio_enabled"
        const val K_TUNNEL = "saved_tunnel_url"
        const val K_LAN_HOST = "saved_lan_host"
        const val K_WAKE_MACS = "wake_macs"
        const val K_WAKE_BROADCASTS = "wake_broadcasts"
    }
}
