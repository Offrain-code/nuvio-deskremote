package com.nuvio.remote.connection

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

@Immutable
data class NuvioLyricWord(
    val word: String,
    val startMs: Long,
    val endMs: Long,
)

@Immutable
data class NuvioLyricLine(
    val timeMs: Long?,
    val text: String,
    val words: List<NuvioLyricWord> = emptyList(),
)

@Immutable
data class NuvioLyricsSheet(
    val kind: String = "none",
    val title: String? = null,
    val lines: List<NuvioLyricLine> = emptyList(),
) {
    val isSynced: Boolean get() = kind == "synced" && lines.any { it.timeMs != null }
    val isPlain: Boolean get() = kind == "plain" && lines.isNotEmpty()
    val hasContent: Boolean get() = kind != "none" && lines.isNotEmpty()

    /** Index of the active lyric line for `positionMs` (with 150ms tolerance) or -1 if none. */
    fun activeLineIndex(positionMs: Long): Int {
        if (!isSynced || lines.isEmpty()) return -1
        val target = positionMs + 150L
        if (lines.all { it.timeMs != null }) {
            var lo = 0
            var hi = lines.lastIndex
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val t = lines[mid].timeMs ?: Long.MAX_VALUE
                if (t <= target) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return ans
        }
        return lines.indices.lastOrNull { i ->
            val t = lines[i].timeMs
            t != null && t <= target
        } ?: -1
    }
}

@Immutable
data class NuvioLiveTvStage(
    val name: String,
    val label: String,
)

@Immutable
data class NuvioLiveTvItem(
    val group: String,
    val index: Int,
    val slot: String,
    val title: String,
    val sub: String?,
    val picture: String?,
    val live: Boolean,
    val progress: Int,
    val action: String,
)

@Immutable
data class NuvioLiveTvButton(
    val group: String,
    val index: Int,
    val label: String,
    val accent: Boolean,
)

@Immutable
data class NuvioLiveTvScreen(
    val stage: String,
    val heading: String,
    val sub: String?,
    val stages: List<NuvioLiveTvStage>,
    val items: List<NuvioLiveTvItem>,
    val buttons: List<NuvioLiveTvButton>,
    val empty: String?,
)

@Immutable
data class NuvioTonightCard(
    val slot: String,
    val name: String,
    val why: String?,
    val meta: String?,
    val poster: String?,
    val flag: Boolean,
)

@Immutable
data class NuvioTonightScreen(
    val view: String,
    val mode: String,
    val loading: Boolean,
    val number: Int,
    val total: Int,
    val countFrom: Int,
    val countLeft: Int,
    val reshuffleLeft: Boolean,
    val prompt: String?,
    val leftLabel: String?,
    val leftDetail: String?,
    val rightLabel: String?,
    val rightDetail: String?,
    val answers: List<String>,
    val cards: List<NuvioTonightCard>,
    val extra: String?,
    val seconds: Int,
    val secondsTotal: Int,
    val paused: Boolean,
    val selected: Int,
)

@Immutable
data class QrPairPayload(
    val host: String?,
    val port: Int?,
    val pin: String?,
)

object NuvioPhase3Parser {
    fun parseLyrics(obj: JSONObject?): NuvioLyricsSheet? {
        if (obj == null) return null
        val kind = obj.optString("kind", "none").trim().ifEmpty { "none" }
        val title = optCleanString(obj, "title")
        val lines = parseLyricLines(obj.optJSONArray("lines"))
        return NuvioLyricsSheet(kind = kind, title = title, lines = lines)
    }

    private fun parseLyricLines(arr: JSONArray?): List<NuvioLyricLine> {
        if (arr == null) return emptyList()
        val out = ArrayList<NuvioLyricLine>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val timeMs = if (item.isNull("timeMs")) null else item.optLong("timeMs", -1L).takeIf { it >= 0L }
            val text = item.optString("text", "")
            val words = parseLyricWords(item.optJSONArray("words"))
            out.add(NuvioLyricLine(timeMs = timeMs, text = text, words = words))
        }
        return out
    }

    private fun parseLyricWords(arr: JSONArray?): List<NuvioLyricWord> {
        if (arr == null) return emptyList()
        val out = ArrayList<NuvioLyricWord>(arr.length())
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i)
            val word = w?.optString("word", "").orEmpty()
            if (w != null && word.isNotEmpty()) {
                out.add(
                    NuvioLyricWord(
                        word = word,
                        startMs = w.optLong("startMs", 0L).coerceAtLeast(0L),
                        endMs = w.optLong("endMs", 0L).coerceAtLeast(0L),
                    ),
                )
            }
        }
        return out
    }

    fun parseLiveTv(obj: JSONObject?): NuvioLiveTvScreen? {
        if (obj == null) return null
        val stage = obj.optString("stage", "").trim()
        val heading = obj.optString("heading", "").trim()
        if (stage.isEmpty() && heading.isEmpty()) return null
        return NuvioLiveTvScreen(
            stage = stage,
            heading = heading.ifEmpty { "Live TV" },
            sub = optCleanString(obj, "sub"),
            stages = parseLiveTvStages(obj.optJSONArray("stages")),
            items = parseLiveTvItems(obj.optJSONArray("items")),
            buttons = parseLiveTvButtons(obj.optJSONArray("buttons")),
            empty = optCleanString(obj, "empty"),
        )
    }

    private fun parseLiveTvStages(arr: JSONArray?): List<NuvioLiveTvStage> {
        if (arr == null) return emptyList()
        val out = ArrayList<NuvioLiveTvStage>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i)
            val name = s?.optString("name", "")?.trim().orEmpty()
            if (s != null && name.isNotEmpty()) {
                val label = s.optString("label", name).trim().ifEmpty { name }
                out.add(NuvioLiveTvStage(name = name, label = label))
            }
        }
        return out
    }

    private fun parseLiveTvItems(arr: JSONArray?): List<NuvioLiveTvItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<NuvioLiveTvItem>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)
            val title = item?.optString("title", "")?.trim().orEmpty()
            if (item != null && title.isNotEmpty()) {
                out.add(
                    NuvioLiveTvItem(
                        group = item.optString("group", ""),
                        index = item.optInt("index", i),
                        slot = item.optString("slot", ""),
                        title = title,
                        sub = optCleanString(item, "sub"),
                        picture = optCleanString(item, "picture"),
                        live = item.optBoolean("live", false),
                        progress = normalizeLiveTvProgress(item.optInt("progress", 0)),
                        action = item.optString("action", ""),
                    ),
                )
            }
        }
        return out
    }

    /** Normalizes bridge Live TV progress (0..1000 permille or legacy 0..100 percent) into 0..100. */
    internal fun normalizeLiveTvProgress(raw: Int): Int =
        if (raw > 100) (raw / 10).coerceIn(0, 100) else raw.coerceIn(0, 100)

    private fun parseLiveTvButtons(arr: JSONArray?): List<NuvioLiveTvButton> {
        if (arr == null) return emptyList()
        val out = ArrayList<NuvioLiveTvButton>(arr.length())
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i)
            val label = b?.optString("label", "")?.trim().orEmpty()
            if (b != null && label.isNotEmpty()) {
                out.add(
                    NuvioLiveTvButton(
                        group = b.optString("group", ""),
                        index = b.optInt("index", i),
                        label = label,
                        accent = b.optBoolean("accent", false),
                    ),
                )
            }
        }
        return out
    }

    fun parseTonight(obj: JSONObject?): NuvioTonightScreen? {
        if (obj == null) return null
        val view = obj.optString("view", "").trim()
        if (view.isEmpty()) return null
        return NuvioTonightScreen(
            view = view,
            mode = obj.optString("mode", "").trim(),
            loading = obj.optBoolean("loading", false),
            number = obj.optInt("number", 0),
            total = obj.optInt("total", 0),
            countFrom = obj.optInt("countFrom", 0),
            countLeft = obj.optInt("countLeft", 0),
            reshuffleLeft = obj.optBoolean("reshuffleLeft", false),
            prompt = optCleanString(obj, "prompt"),
            leftLabel = optCleanString(obj, "leftLabel"),
            leftDetail = optCleanString(obj, "leftDetail"),
            rightLabel = optCleanString(obj, "rightLabel"),
            rightDetail = optCleanString(obj, "rightDetail"),
            answers = parseStringArray(obj.optJSONArray("answers")),
            cards = parseTonightCards(obj.optJSONArray("cards")),
            extra = optCleanString(obj, "extra"),
            seconds = obj.optInt("seconds", 0),
            secondsTotal = obj.optInt("secondsTotal", 0),
            paused = obj.optBoolean("paused", false),
            selected = obj.optInt("selected", -1),
        )
    }

    private fun parseTonightCards(arr: JSONArray?): List<NuvioTonightCard> {
        if (arr == null) return emptyList()
        val out = ArrayList<NuvioTonightCard>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i)
            val name = c?.optString("name", "")?.trim().orEmpty()
            if (c != null && name.isNotEmpty()) {
                out.add(
                    NuvioTonightCard(
                        slot = c.optString("slot", ""),
                        name = name,
                        why = optCleanString(c, "why"),
                        meta = optCleanString(c, "meta"),
                        poster = optCleanString(c, "poster"),
                        flag = c.optBoolean("flag", false),
                    ),
                )
            }
        }
        return out
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun optCleanString(obj: JSONObject, key: String): String? {
        if (obj.isNull(key)) return null
        val s = obj.optString(key, "").trim()
        return if (s.isEmpty() || s == "null") null else s
    }
}

/**
 * Parses Nuvio DeskRemote / Nuvio HTPC pairing QR code payloads:
 * - JSON: `{"host":"192.0.2.10","port":8787,"pin":"319804"}`
 * - URL: `http://192.0.2.10:8765/?pin=319804`, `nuvio://pair?host=192.0.2.10&pin=319804`
 * - Short: `192.0.2.10|319804` or bare 6-digit PIN `319804`
 */
object QrPairParser {
    private val SIX_DIGITS = Regex("^\\d{6}$")
    private val IPV4_REGEX = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

    fun parse(raw: String): QrPairPayload? {
        val text = raw.trim()
        val digitsOnly = text.filter(Char::isDigit)
        return when {
            text.isEmpty() -> null
            text.startsWith("{") -> parseJson(text)
            text.contains("://") -> parseUri(text)
            text.contains("|") -> parsePipe(text)
            SIX_DIGITS.matches(digitsOnly) && text.length <= 8 ->
                QrPairPayload(host = null, port = null, pin = digitsOnly)
            IPV4_REGEX.matches(text) -> QrPairPayload(host = text, port = null, pin = null)
            else -> null
        }
    }

    private fun parseJson(text: String): QrPairPayload? = runCatching {
        val obj = JSONObject(text)
        val host = listOf("host", "ip", "address")
            .firstNotNullOfOrNull { k -> obj.optString(k, "").trim().takeIf { it.isNotEmpty() } }
        val port = obj.optInt("port", -1).takeIf { it in 1..65535 }
        val pinRaw = listOf("pin", "code")
            .firstNotNullOfOrNull { k -> obj.optString(k, "").filter(Char::isDigit).takeIf { it.length == 6 } }
        if (host == null && pinRaw == null) null else QrPairPayload(host = host, port = port, pin = pinRaw)
    }.getOrNull()

    private fun parseUri(text: String): QrPairPayload? = runCatching {
        val uri = URI(text)
        val params = parseQueryParams(uri.rawQuery)
        val host = params["host"]?.takeIf { it.isNotBlank() }
            ?: params["ip"]?.takeIf { it.isNotBlank() }
            ?: uri.host?.takeIf { it.isNotBlank() && it != "pair" }
        val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: uri.port.takeIf { it in 1..65535 }
        val pinSource = params["pin"] ?: params["code"] ?: uri.fragment
        val pin = pinSource?.filter(Char::isDigit)?.takeIf { it.length == 6 }
        if (host == null && pin == null) null else QrPairPayload(host = host, port = port, pin = pin)
    }.getOrNull()

    private fun parsePipe(text: String): QrPairPayload? {
        val parts = text.split("|").map { it.trim() }
        val hostPart = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val pinPart = parts.getOrNull(1)?.filter(Char::isDigit)?.takeIf { it.length == 6 }
        val hostPieces = hostPart?.split(":")
        val host = hostPieces?.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val port = hostPieces?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
        return if (host == null && pinPart == null) null else QrPairPayload(host = host, port = port, pin = pinPart)
    }

    private fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        return query.split("&")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
            }
            .toMap()
    }
}
