package com.nuvio.remote.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 6-digit PIN pairing flow per SECURITY.md section 3:
 * - persistent UUIDv4 device_id;
 * - POST http://PC:8765/api/v1/pair (with fallback to 8787);
 * - session token stored in EncryptedSharedPreferences (with safe fallback on Keystore errors).
 */
sealed interface PairResult {
    data class Paired(val token: String, val serverId: String) : PairResult
    data class WrongPin(val attemptsRemaining: Int) : PairResult
    data class RateLimited(val retryAfterSeconds: Int) : PairResult
    data object LanOnly : PairResult
    data object NetworkError : PairResult
}

sealed interface RequestPinResult {
    data class Success(val expiresInSeconds: Int) : RequestPinResult
    data object LanOnly : RequestPinResult
    data class RateLimited(val retryAfterSeconds: Int) : RequestPinResult
    data object NetworkError : RequestPinResult
}

class PairingManager(context: Context) {
    private val json = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val prefs: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "nuvio_secure_prefs",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Fallback if device Keystore throws AEADBadTagException after reinstalling a debug APK
        runCatching { context.deleteSharedPreferences("nuvio_secure_prefs") }
        context.getSharedPreferences("nuvio_fallback_prefs", Context.MODE_PRIVATE)
    }

    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }
    val deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null) ?: cleanName(android.os.Build.MODEL).also {
            prefs.edit().putString(KEY_DEVICE_NAME, it).apply()
        }

    /** Persisted session token (SECURITY section 4.2 — EncryptedSharedPreferences). No default token. */
    var savedToken: String
        get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: ""
        set(value) {
            if (value.isBlank()) {
                prefs.edit().remove(KEY_TOKEN).apply()
            } else {
                prefs.edit().putString(KEY_TOKEN, value).apply()
            }
        }

    var savedHost: String?
        get() = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_HOST, value).apply()
        }

    /** Returns true when a valid cryptographic session token is saved. */
    fun isPaired(): Boolean = savedToken.startsWith("nuvio_sec_") && savedToken.length >= 32

    /** Requests a temporary 6-digit PIN displayed on the PC (POST /api/v1/pair/request). */
    fun requestPin(host: String, port: Int = 8765): RequestPinResult {
        if (isRemoteTunnelHost(host)) return RequestPinResult.LanOnly
        val clean = host.trim()
            .removePrefix("wss://")
            .removePrefix("ws://")
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        val url = "http://$clean:$port/api/v1/pair/request"
        val req = Request.Builder()
            .url(url)
            .post("{}".toRequestBody(json))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val obj = JSONObject(resp.body?.string() ?: "{}")
                when (resp.code) {
                    200 -> RequestPinResult.Success(obj.optInt("expires_in_seconds", 120))
                    403 -> RequestPinResult.LanOnly
                    429 -> RequestPinResult.RateLimited(obj.optInt("retry_after_seconds", 300))
                    else -> {
                        if (port == 8765) {
                            requestPin(host, 8787)
                        } else {
                            RequestPinResult.NetworkError
                        }
                    }
                }
            }
        } catch (_: Exception) {
            if (port == 8765) {
                requestPin(host, 8787)
            } else {
                RequestPinResult.NetworkError
            }
        }
    }

    /** Pairs with the PC using the 6-digit PIN shown on screen (tries port 8765, falls back to 8787). */
    fun pair(host: String, port: Int = 8765, pin: String): PairResult {
        if (!pin.matches(Regex("^\\d{6}$"))) return PairResult.WrongPin(attemptsRemaining = 0)
        if (isRemoteTunnelHost(host)) {
            return PairResult.LanOnly
        }
        val first = tryPairPort(host, port, pin)
        if (first !is PairResult.NetworkError || port == 8787) return first
        return tryPairPort(host, 8787, pin)
    }

    private fun isRemoteTunnelHost(host: String): Boolean {
        val h = host.trim().lowercase()
        return h.startsWith("https://") ||
            h.startsWith("wss://") ||
            h.contains(".trycloudflare.com") ||
            h.contains(".ngrok")
    }

    private fun buildPairUrl(host: String, port: Int): String {
        val clean = host.trim()
            .removePrefix("wss://")
            .removePrefix("ws://")
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        return if (isRemoteTunnelHost(host)) {
            "https://$clean/api/v1/pair"
        } else {
            "http://$clean:$port/api/v1/pair"
        }
    }

    private fun tryPairPort(host: String, port: Int, pin: String): PairResult {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("device_name", deviceName)
            .put("pin", pin)
            .toString()
            .toRequestBody(json)
        val req = Request.Builder()
            .url(buildPairUrl(host, port))
            .post(body)
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val obj = JSONObject(resp.body?.string() ?: "{}")
                when {
                    resp.code == 200 -> {
                        val token = obj.optString("token")
                        savedToken = token
                        savedHost = host.trim()
                        PairResult.Paired(token, obj.optString("server_id"))
                    }
                    resp.code == 401 -> PairResult.WrongPin(obj.optInt("attempts_remaining", 0))
                    resp.code == 403 -> PairResult.LanOnly
                    resp.code == 429 -> PairResult.RateLimited(obj.optInt("retry_after_seconds", 300))
                    else -> PairResult.NetworkError
                }
            }
        } catch (_: Exception) {
            PairResult.NetworkError
        }
    }

    fun unpair() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private fun cleanName(raw: String): String = raw.replace(Regex("[^\\w \\-()]"), "").take(40)

    companion object {
        const val DEFAULT_HOST = ""
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_TOKEN = "session_token"
        const val KEY_HOST = "paired_host"
    }
}
