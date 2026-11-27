package com.nuvio.remote.pairing

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Saved Windows Companion profile for Multi-PC quick switching (Phase 3C).
 * Tokens stay inside EncryptedSharedPreferences via PairingManager; never logged or exported.
 */
data class SavedPcProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 8787,
    val serverId: String = "",
    val token: String = "",
    val tunnelUrl: String = "",
    val wakeMacs: List<String> = emptyList(),
    val wakeBroadcasts: List<String> = emptyList(),
    val lastConnectedAtMs: Long = 0L,
) {
    fun isPaired(): Boolean = token.startsWith("nuvio_sec_") && token.length >= 32
}

internal object SavedPcProfileCodec {
    fun encode(profiles: List<SavedPcProfile>): String {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("host", p.host)
                    .put("port", p.port)
                    .put("serverId", p.serverId)
                    .put("token", p.token)
                    .put("tunnelUrl", p.tunnelUrl)
                    .put("wakeMacs", JSONArray(p.wakeMacs))
                    .put("wakeBroadcasts", JSONArray(p.wakeBroadcasts))
                    .put("lastConnectedAtMs", p.lastConnectedAtMs),
            )
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<SavedPcProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { idx -> decodeOne(arr.optJSONObject(idx)) }
    }

    private fun decodeOne(obj: JSONObject?): SavedPcProfile? {
        if (obj == null) return null
        val host = obj.optString("host", "").trim()
        if (host.isEmpty()) return null
        val id = obj.optString("id", "").trim().ifEmpty { UUID.randomUUID().toString() }
        val name = obj.optString("name", "").trim().ifEmpty { host }
        val port = obj.optInt("port", 8787).coerceIn(1, 65535)
        return SavedPcProfile(
            id = id,
            name = name,
            host = host,
            port = port,
            serverId = obj.optString("serverId", "").trim(),
            token = obj.optString("token", "").trim(),
            tunnelUrl = obj.optString("tunnelUrl", "").trim(),
            wakeMacs = jsonStringList(obj.optJSONArray("wakeMacs")),
            wakeBroadcasts = jsonStringList(obj.optJSONArray("wakeBroadcasts")),
            lastConnectedAtMs = obj.optLong("lastConnectedAtMs", 0L),
        )
    }

    fun upsert(existing: List<SavedPcProfile>, incoming: SavedPcProfile): List<SavedPcProfile> {
        val matchIdx = existing.indexOfFirst { cur ->
            (incoming.id.isNotBlank() && cur.id == incoming.id) ||
                (incoming.serverId.isNotBlank() && cur.serverId.equals(incoming.serverId, ignoreCase = true)) ||
                cur.host.equals(incoming.host, ignoreCase = true)
        }
        if (matchIdx < 0) return existing + incoming
        val current = existing[matchIdx]
        val merged = current.copy(
            name = incoming.name.ifBlank { current.name },
            host = incoming.host.ifBlank { current.host },
            port = incoming.port,
            serverId = incoming.serverId.ifBlank { current.serverId },
            token = incoming.token.ifBlank { current.token },
            tunnelUrl = incoming.tunnelUrl.ifBlank { current.tunnelUrl },
            wakeMacs = incoming.wakeMacs.ifEmpty { current.wakeMacs },
            wakeBroadcasts = incoming.wakeBroadcasts.ifEmpty { current.wakeBroadcasts },
            lastConnectedAtMs = maxOf(current.lastConnectedAtMs, incoming.lastConnectedAtMs),
        )
        return existing.toMutableList().apply { set(matchIdx, merged) }
    }

    fun rename(existing: List<SavedPcProfile>, id: String, newName: String): List<SavedPcProfile> {
        val clean = newName.trim().take(32)
        if (clean.isEmpty()) return existing
        return existing.map { if (it.id == id) it.copy(name = clean) else it }
    }

    fun remove(existing: List<SavedPcProfile>, id: String): List<SavedPcProfile> = existing.filterNot { it.id == id }

    private fun jsonStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim()
                if (item.isNotEmpty()) add(item)
            }
        }
    }
}
