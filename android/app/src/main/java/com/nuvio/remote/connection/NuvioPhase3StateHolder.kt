package com.nuvio.remote.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages state and commands for synced Lyrics (`LYRICS`), Live TV Mirror (`LIVETV`),
 * and "What to Watch Tonight" (`TONIGHT`) via the Nuvio bridge.
 */
class NuvioPhase3StateHolder(
    private val sendJson: (type: String, body: JSONObject.() -> Unit) -> Boolean,
) {
    private val _lyrics = MutableStateFlow<NuvioLyricsSheet?>(null)
    val lyrics: StateFlow<NuvioLyricsSheet?> = _lyrics

    private val _liveTv = MutableStateFlow<NuvioLiveTvScreen?>(null)
    val liveTv: StateFlow<NuvioLiveTvScreen?> = _liveTv

    private val _tonight = MutableStateFlow<NuvioTonightScreen?>(null)
    val tonight: StateFlow<NuvioTonightScreen?> = _tonight

    fun applyBridgeSnapshot(obj: JSONObject) {
        if (obj.has("liveTv")) {
            _liveTv.value = NuvioPhase3Parser.parseLiveTv(obj.optJSONObject("liveTv"))
        }
        if (obj.has("tonight")) {
            _tonight.value = NuvioPhase3Parser.parseTonight(obj.optJSONObject("tonight"))
        }
    }

    fun handleServerMessage(type: String, obj: JSONObject): Boolean = when (type) {
        "nuvio_lyrics" -> {
            _lyrics.value = NuvioPhase3Parser.parseLyrics(obj.optJSONObject("lyrics"))
            true
        }
        "nuvio_livetv" -> {
            _liveTv.value = NuvioPhase3Parser.parseLiveTv(obj.optJSONObject("screen"))
            true
        }
        "nuvio_tonight" -> {
            _tonight.value = NuvioPhase3Parser.parseTonight(obj.optJSONObject("screen"))
            true
        }
        else -> false
    }

    fun resetOnDisconnect() {
        _lyrics.value = null
        _liveTv.value = null
        _tonight.value = null
    }

    fun requestLyrics(): Boolean = sendJson("nuvio_lyrics") {}

    fun sendLiveTvDo(args: List<String>): Boolean = sendJson("nuvio_livetv_do") {
        put("args", JSONArray(args))
    }

    fun sendTonightDo(action: String, number: Int? = null): Boolean = sendJson("nuvio_tonight_do") {
        put("action", action)
        if (number != null) put("number", number)
    }
}
