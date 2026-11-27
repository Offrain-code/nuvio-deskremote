package com.nuvio.remote.connection

import com.nuvio.remote.ui.i18n.l10nCurrent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Manages real-time state and commands for the native Nuvio bridge
 * carried over the Companion server WebSocket connection.
 */
class NuvioBridgeStateHolder(
    private val sendJson: (type: String, body: JSONObject.() -> Unit) -> Boolean,
) {
    private val _status = MutableStateFlow(NuvioBridgeStatus.DISCONNECTED)
    val status: StateFlow<NuvioBridgeStatus> = _status

    private val _pcName = MutableStateFlow<String?>(null)
    val pcName: StateFlow<String?> = _pcName

    private val _nowPlaying = MutableStateFlow<NuvioNowPlaying?>(null)
    val nowPlaying: StateFlow<NuvioNowPlaying?> = _nowPlaying

    private val _sources = MutableStateFlow<NuvioSourceList?>(null)
    val sources: StateFlow<NuvioSourceList?> = _sources

    private val _subtitles = MutableStateFlow(NuvioTrackList())
    val subtitles: StateFlow<NuvioTrackList> = _subtitles

    private val _audioTracks = MutableStateFlow(NuvioTrackList())
    val audioTracks: StateFlow<NuvioTrackList> = _audioTracks

    private val _musicQueue = MutableStateFlow(NuvioQueueSnapshot())
    val musicQueue: StateFlow<NuvioQueueSnapshot> = _musicQueue

    private val _pairFeedback = MutableStateFlow<NuvioBridgePairFeedback?>(null)
    val pairFeedback: StateFlow<NuvioBridgePairFeedback?> = _pairFeedback

    private val _openFeedback = MutableStateFlow<String?>(null)
    val openFeedback: StateFlow<String?> = _openFeedback

    private val _wakeInfo = MutableStateFlow<NuvioWakeInfo?>(null)
    val wakeInfo: StateFlow<NuvioWakeInfo?> = _wakeInfo

    val catalog = NuvioCatalogStateHolder(sendJson)
    val phase3 = NuvioPhase3StateHolder(sendJson)

    fun handleServerMessage(type: String, obj: JSONObject): Boolean {
        return when (type) {
            "nuvio_bridge" -> {
                _status.value = NuvioBridgeStatus.fromWire(obj.optString("status"))
                _pcName.value = obj.optString("pcName", "").takeIf { it.isNotBlank() && it != "null" }
                _nowPlaying.value = NuvioBridgeParser.parseNowPlaying(obj.optJSONObject("nowPlaying"))
                _sources.value = NuvioBridgeParser.parseSourceList(obj.optJSONObject("sources"))
                NuvioCatalogParser.parseWake(obj.optJSONObject("wake"))?.let { _wakeInfo.value = it }
                phase3.applyBridgeSnapshot(obj)
                true
            }
            "nuvio_state" -> {
                _nowPlaying.value = NuvioBridgeParser.parseNowPlaying(obj.optJSONObject("nowPlaying"))
                true
            }
            "nuvio_sources" -> {
                _sources.value = NuvioBridgeParser.parseSourceList(obj.optJSONObject("list"))
                true
            }
            "nuvio_subtitles" -> {
                _subtitles.value = NuvioBridgeParser.parseTrackList(obj)
                true
            }
            "nuvio_audio" -> {
                _audioTracks.value = NuvioBridgeParser.parseTrackList(obj)
                true
            }
            "nuvio_queue" -> {
                _musicQueue.value = NuvioBridgeParser.parseQueue(obj)
                true
            }
            "nuvio_bridge_pair_result" -> {
                val ok = obj.optBoolean("ok", false)
                val msg = if (ok) {
                    l10nCurrent("Nuvio linked successfully.", "Nuvio vinculado com sucesso.")
                } else {
                    mapPairError(obj.optString("error", ""))
                }
                _pairFeedback.value = NuvioBridgePairFeedback(ok = ok, message = msg)
                true
            }
            "nuvio_open_result" -> {
                val ok = obj.optBoolean("ok", false)
                val what = obj.optString("what", "").trim()
                _openFeedback.value = if (ok) {
                    if (what.isNotEmpty()) {
                        l10nCurrent("Opening $what in Nuvio", "Abrindo $what no Nuvio")
                    } else {
                        l10nCurrent("Opening in Nuvio", "Abrindo no Nuvio")
                    }
                } else {
                    l10nCurrent(
                        "Could not open this item in Nuvio.",
                        "Não foi possível abrir esse item no Nuvio.",
                    )
                }
                true
            }
            else -> catalog.handleServerMessage(type, obj) || phase3.handleServerMessage(type, obj)
        }
    }

    fun resetOnDisconnect() {
        _status.value = NuvioBridgeStatus.DISCONNECTED
        _nowPlaying.value = null
        _sources.value = null
        phase3.resetOnDisconnect()
    }

    fun pairWithPin(pin: String): Boolean = sendJson("nuvio_bridge_pair") {
        put("pin", pin.filter(Char::isDigit).take(6))
    }

    fun seekTo(positionMs: Long): Boolean = sendJson("nuvio_seek") {
        put("positionMs", positionMs.coerceAtLeast(0L))
    }

    fun setVolume(volume: Int): Boolean = sendJson("nuvio_volume") {
        put("volume", volume.coerceAtLeast(0))
    }

    fun sendControl(action: String, value: Int? = null): Boolean = sendJson("nuvio_control") {
        put("action", action)
        if (value != null) put("value", value)
    }

    fun requestSubtitles(): Boolean = sendJson("nuvio_subtitles") {}

    fun pickSubtitle(number: Int): Boolean = sendJson("nuvio_pick_subtitle") {
        put("number", number)
    }

    fun requestAudio(): Boolean = sendJson("nuvio_audio") {}

    fun pickAudio(number: Int): Boolean = sendJson("nuvio_pick_audio") {
        put("number", number)
    }

    fun requestSources(force: Boolean = false): Boolean {
        if (force && _sources.value == null) {
            _sources.value = NuvioSourceList(title = "", loading = true, sources = emptyList())
        }
        return sendJson("nuvio_sources_open") {
            put("force", force)
        }
    }

    fun pickSource(number: Int): Boolean = sendJson("nuvio_pick_source") {
        put("number", number)
    }

    fun requestQueue(): Boolean = sendJson("nuvio_queue") {}

    fun queueAction(action: String, videoId: String): Boolean = sendJson("nuvio_queue_action") {
        put("action", action)
        put("videoId", videoId)
    }

    fun openLink(url: String): Boolean = sendJson("nuvio_open") {
        put("url", url)
    }

    private fun mapPairError(code: String): String = when (code) {
        "BAD_PIN", "INVALID_PIN" -> l10nCurrent(
            "Incorrect code. Check the 6 digits in Nuvio.",
            "Código incorreto. Confira os 6 dígitos no Nuvio.",
        )
        "LOCKED" -> l10nCurrent(
            "Too many attempts. Wait 1 minute and try again.",
            "Muitas tentativas. Aguarde 1 minuto e tente novamente.",
        )
        "LAN_ONLY" -> l10nCurrent(
            "Linking is only allowed on home Wi-Fi.",
            "A vinculação só pode ser feita no Wi-Fi de casa.",
        )
        "UNAVAILABLE" -> l10nCurrent(
            "Open Nuvio on PC and enable Mobile Remote in Settings.",
            "Abra o Nuvio no PC e ative Controle pelo celular nos ajustes.",
        )
        else -> l10nCurrent(
            "Could not link right now. Please try again.",
            "Não foi possível vincular agora. Tente novamente.",
        )
    }
}
