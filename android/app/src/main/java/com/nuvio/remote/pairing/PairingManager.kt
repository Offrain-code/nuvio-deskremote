package com.nuvio.remote.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nuvio.remote.connection.EndpointUrlResolver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 6-digit PIN pairing flow per SECURITY.md section 3:
 * - persistent UUIDv4 device_id;
 * - POST http://PC:8765/api/v1/pair (with fallback to 8787);
 * - session token stored in EncryptedSharedPreferences (with Keystore recovery and no plaintext disk fallback).
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

    private val prefs: SharedPreferences = openSecurePreferences(context.applicationContext)

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

    /** Server identifier learned at pairing time to authenticate LAN UDP discovery replies. */
    var savedServerId: String?
        get() = prefs.getString(KEY_SERVER_ID, null)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) {
                prefs.edit().remove(KEY_SERVER_ID).apply()
            } else {
                prefs.edit().putString(KEY_SERVER_ID, value.trim()).apply()
            }
        }

    /** Returns true when a valid cryptographic session token is saved. */
    fun isPaired(): Boolean = savedToken.startsWith("nuvio_sec_") && savedToken.length >= 32

    /** Requests a temporary 6-digit PIN displayed on the PC (POST /api/v1/pair/request). */
    fun requestPin(host: String, port: Int = 8765): RequestPinResult {
        if (EndpointUrlResolver.isRemoteTunnelHost(host)) return RequestPinResult.LanOnly
        val url = EndpointUrlResolver.buildLanHttpUrl(host, port, "/api/v1/pair/request")
            ?: return RequestPinResult.NetworkError
        return try {
            val req = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(json))
                .build()
            http.newCall(req).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(rawBody) }.getOrNull() ?: JSONObject()
                when (resp.code) {
                    200 -> RequestPinResult.Success(obj.optInt("expires_in_seconds", 120))
                    403 -> RequestPinResult.LanOnly
                    429 -> RequestPinResult.RateLimited(obj.optInt("retry_after_seconds", 300))
                    else -> {
                        if (port == 8765) requestPin(host, 8787) else RequestPinResult.NetworkError
                    }
                }
            }
        } catch (_: Exception) {
            if (port == 8765) requestPin(host, 8787) else RequestPinResult.NetworkError
        }
    }

    /** Pairs with the PC using the 6-digit PIN shown on screen (tries port 8765, falls back to 8787). */
    fun pair(host: String, port: Int = 8765, pin: String): PairResult {
        if (!pin.matches(Regex("^\\d{6}$"))) return PairResult.WrongPin(attemptsRemaining = 0)
        if (EndpointUrlResolver.isRemoteTunnelHost(host)) {
            return PairResult.LanOnly
        }
        val first = tryPairPort(host, port, pin)
        if (first !is PairResult.NetworkError || port == 8787) return first
        return tryPairPort(host, 8787, pin)
    }

    private fun tryPairPort(host: String, port: Int, pin: String): PairResult {
        val url = EndpointUrlResolver.buildLanHttpUrl(host, port, "/api/v1/pair")
            ?: return PairResult.NetworkError
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("device_name", deviceName)
            .put("pin", pin)
            .toString()
            .toRequestBody(json)
        return try {
            val req = Request.Builder()
                .url(url)
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                parsePairResponse(host, resp.code, resp.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            PairResult.NetworkError
        }
    }

    private fun parsePairResponse(host: String, statusCode: Int, rawBody: String): PairResult {
        val obj = runCatching { JSONObject(rawBody) }.getOrNull() ?: JSONObject()
        return when (statusCode) {
            200 -> handlePairSuccess(host, obj)
            401 -> PairResult.WrongPin(obj.optInt("attempts_remaining", 0))
            403 -> PairResult.LanOnly
            429 -> PairResult.RateLimited(obj.optInt("retry_after_seconds", 300))
            else -> PairResult.NetworkError
        }
    }

    private fun handlePairSuccess(host: String, obj: JSONObject): PairResult {
        val token = obj.optString("token", "").trim()
        if (!token.startsWith("nuvio_sec_") || token.length < 32) {
            return PairResult.NetworkError
        }
        val serverId = obj.optString("server_id", "").trim()
        savedToken = token
        savedHost = EndpointUrlResolver.extractHostOnly(host)
        if (serverId.isNotEmpty()) savedServerId = serverId
        return PairResult.Paired(token, serverId)
    }

    /** Best-effort server-side revocation for this device before clearing local credentials. */
    fun revokeCurrentDevice(host: String, port: Int = 8787): Boolean {
        val token = savedToken
        if (token.isBlank()) return false
        val url = EndpointUrlResolver.buildLanHttpUrl(host, port, "/api/v1/devices/revoke") ?: return false
        return runCatching {
            val body = JSONObject().put("device_id", deviceId).toString().toRequestBody(json)
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun unpair() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_SERVER_ID)
            .apply()
    }

    private fun cleanName(raw: String): String = raw.replace(Regex("[^\\w \\-()]"), "").take(40)

    private fun openSecurePreferences(appContext: Context): SharedPreferences {
        createEncryptedPrefs(appContext)?.let { secure ->
            runCatching { appContext.deleteSharedPreferences(LEGACY_FALLBACK_PREFS) }
            return secure
        }
        // Recover from corrupted Keystore / debug-reinstall AEADBadTagException without falling back to plaintext disk
        runCatching { appContext.deleteSharedPreferences(SECURE_PREFS_NAME) }
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }
        createEncryptedPrefs(appContext)?.let { recovered ->
            runCatching { appContext.deleteSharedPreferences(LEGACY_FALLBACK_PREFS) }
            return recovered
        }
        runCatching { appContext.deleteSharedPreferences(LEGACY_FALLBACK_PREFS) }
        return InMemorySharedPreferences()
    }

    private fun createEncryptedPrefs(appContext: Context): SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS_NAME,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    companion object {
        const val DEFAULT_HOST = ""
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_TOKEN = "session_token"
        const val KEY_HOST = "paired_host"
        const val KEY_SERVER_ID = "paired_server_id"
        private const val SECURE_PREFS_NAME = "nuvio_secure_prefs"
        private const val LEGACY_FALLBACK_PREFS = "nuvio_fallback_prefs"
    }
}

/** Ephemeral in-memory fallback used only if Android Keystore fails completely (never writes plaintext tokens). */
private class InMemorySharedPreferences : SharedPreferences {
    private val map = ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? =
        if (key == null) defValue else map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = if (key == null) defValue else map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long =
        if (key == null) defValue else map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float =
        if (key == null) defValue else map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        if (key == null) defValue else map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = key != null && map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = null
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            pending.clear()
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearAll) map.clear()
            for ((k, v) in pending) {
                if (v == null) map.remove(k) else map[k] = v
            }
        }
    }
}
