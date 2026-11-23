package com.nuvio.remote.connection

import androidx.compose.runtime.Immutable
import com.nuvio.remote.ui.i18n.l10nCurrent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class NuvioBridgeStatus {
    DISCONNECTED,
    CONNECTING,
    PIN_REQUIRED,
    CONNECTED,
    ;

    companion object {
        fun fromWire(value: String?): NuvioBridgeStatus = when (value?.lowercase(Locale.ROOT)) {
            "connected" -> CONNECTED
            "pin_required" -> PIN_REQUIRED
            "connecting" -> CONNECTING
            else -> DISCONNECTED
        }
    }
}

@Immutable
data class NuvioNowPlaying(
    val kind: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Int?,
    val volumeMax: Int,
    val canSkip: Boolean,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val receivedAtMs: Long,
    val subtitleTrack: String? = null,
    val audioTrack: String? = null,
    val speedPercent: Int? = null,
    val skipLabel: String? = null,
    val canNextEpisode: Boolean = false,
    val sleepMinutes: Int = 0,
    val shuffle: Boolean? = null,
    val repeat: Int? = null,
    val liked: Boolean? = null,
) {
    val isMusic: Boolean get() = kind == "music"
    val hasTimeline: Boolean get() = durationMs > 0L

    fun positionAt(nowMs: Long): Long {
        val elapsed = if (isPlaying) (nowMs - receivedAtMs).coerceAtLeast(0L) else 0L
        val moved = positionMs + elapsed
        return if (hasTimeline) moved.coerceAtMost(durationMs) else moved
    }
}

@Immutable
data class NuvioSourceItem(
    val number: Int,
    val name: String,
    val detail: String?,
    val addon: String?,
    val sizeBytes: Long?,
    val instant: Boolean,
    val playable: Boolean,
)

@Immutable
data class NuvioSourceList(
    val title: String,
    val loading: Boolean,
    val sources: List<NuvioSourceItem>,
)

@Immutable
data class NuvioTrackChoice(
    val number: Int,
    val label: String,
    val detail: String?,
    val group: String,
    val selected: Boolean,
)

@Immutable
data class NuvioTrackList(
    val loading: Boolean = false,
    val choices: List<NuvioTrackChoice> = emptyList(),
)

@Immutable
data class NuvioQueueTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val seconds: Int,
    val cover: String?,
    val upcoming: Boolean,
)

@Immutable
data class NuvioQueueSnapshot(
    val playing: Int = -1,
    val tracks: List<NuvioQueueTrack> = emptyList(),
)

@Immutable
data class NuvioBridgePairFeedback(
    val ok: Boolean,
    val message: String,
    val tick: Long = System.currentTimeMillis(),
)

@Immutable
data class SourceBadge(val label: String, val key: String, val accent: Boolean = false)

@Immutable
data class SourceFacts(
    val resolution: SourceBadge?,
    val hdr: SourceBadge?,
    val codec: SourceBadge?,
    val extras: List<SourceBadge>,
) {
    val all: List<SourceBadge> get() = listOfNotNull(resolution, hdr, codec) + extras
}

object SourceBadgeParser {
    const val FILTER_ALL = "all"
    const val FILTER_4K = "4k"
    const val FILTER_HDR = "hdr"
    const val FILTER_DV = "dv"

    private val fourKRegex = Regex("""\b(2160p|4K|UHD)\b""", RegexOption.IGNORE_CASE)
    private val fullHdRegex = Regex("""\b1080p\b""", RegexOption.IGNORE_CASE)
    private val hdRegex = Regex("""\b720p\b""", RegexOption.IGNORE_CASE)
    private val dvRegex = Regex("""\b(DV|DoVi|Dolby[ .]?Vision)\b""", RegexOption.IGNORE_CASE)
    private val hdrPlusRegex = Regex("""\bHDR10\+""", RegexOption.IGNORE_CASE)
    private val hdr10Regex = Regex("""\bHDR10\b""", RegexOption.IGNORE_CASE)
    private val hdrRegex = Regex("""\bHDR\b""", RegexOption.IGNORE_CASE)
    private val av1Regex = Regex("""\bAV1\b""", RegexOption.IGNORE_CASE)
    private val hevcRegex = Regex("""\b(HEVC|x265|H[ .]?265)\b""", RegexOption.IGNORE_CASE)
    private val avcRegex = Regex("""\b(x264|H[ .]?264|AVC)\b""", RegexOption.IGNORE_CASE)
    private val atmosRegex = Regex("""\bAtmos\b""", RegexOption.IGNORE_CASE)
    private val remuxRegex = Regex("""\bREMUX\b""", RegexOption.IGNORE_CASE)

    fun parse(name: String, detail: String?): SourceFacts {
        val text = "$name\n${detail.orEmpty()}".replace('.', ' ').replace('_', ' ')
        val resolution = when {
            fourKRegex.containsMatchIn(text) -> SourceBadge("4K", FILTER_4K, accent = true)
            fullHdRegex.containsMatchIn(text) -> SourceBadge("1080p", "1080p")
            hdRegex.containsMatchIn(text) -> SourceBadge("720p", "720p")
            else -> null
        }
        val hdr = when {
            dvRegex.containsMatchIn(text) -> SourceBadge("DV", FILTER_DV, accent = true)
            hdrPlusRegex.containsMatchIn(text) -> SourceBadge("HDR10+", FILTER_HDR, accent = true)
            hdr10Regex.containsMatchIn(text) -> SourceBadge("HDR10", FILTER_HDR, accent = true)
            hdrRegex.containsMatchIn(text) -> SourceBadge("HDR", FILTER_HDR, accent = true)
            else -> null
        }
        val codec = when {
            av1Regex.containsMatchIn(text) -> SourceBadge("AV1", "av1")
            hevcRegex.containsMatchIn(text) -> SourceBadge("HEVC", "hevc")
            avcRegex.containsMatchIn(text) -> SourceBadge("H.264", "h264")
            else -> null
        }
        val extras = buildList {
            if (atmosRegex.containsMatchIn(text)) add(SourceBadge("Atmos", "atmos"))
            if (remuxRegex.containsMatchIn(text)) add(SourceBadge("Remux", "remux"))
        }
        return SourceFacts(resolution, hdr, codec, extras)
    }

    fun matches(facts: SourceFacts, filterKey: String): Boolean = when (filterKey) {
        FILTER_4K -> facts.resolution?.key == FILTER_4K
        FILTER_HDR -> facts.hdr != null
        FILTER_DV -> facts.hdr?.key == FILTER_DV
        else -> true
    }
}

fun formatMediaTime(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}

fun formatFileSizeBr(bytes: Long?): String? {
    if (bytes == null || bytes <= 0L) return null
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(Locale("pt", "BR"), "%.1f GB", gb)
    } else {
        val mb = bytes / (1024.0 * 1024.0)
        String.format(Locale("pt", "BR"), "%.0f MB", mb)
    }
}

fun computeFilmEndsAtEpochMs(positionMs: Long, durationMs: Long, speedPercent: Int?, nowEpochMs: Long): Long? {
    if (durationMs <= 0L) return null
    val remaining = (durationMs - positionMs).coerceAtLeast(0L)
    val speed = (speedPercent ?: 100).coerceIn(50, 400) / 100.0
    val realRemainingMs = (remaining / speed).toLong()
    return nowEpochMs + realRemainingMs
}

object NuvioBridgeParser {
    private fun JSONObject.optCleanString(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    fun parseNowPlaying(obj: JSONObject?, nowMs: Long = System.currentTimeMillis()): NuvioNowPlaying? {
        if (obj == null) return null
        val title = obj.optCleanString("title") ?: return null
        return NuvioNowPlaying(
            kind = obj.optString("kind", "film"),
            isPlaying = obj.optBoolean("isPlaying", false),
            positionMs = obj.optLong("positionMs", 0L).coerceAtLeast(0L),
            durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L),
            volume = if (obj.isNull("volume")) null else obj.optInt("volume"),
            volumeMax = obj.optInt("volumeMax", 100).coerceAtLeast(1),
            canSkip = obj.optBoolean("canSkip", false),
            title = title,
            subtitle = obj.optCleanString("subtitle"),
            imageUrl = obj.optCleanString("imageUrl"),
            receivedAtMs = nowMs,
            subtitleTrack = obj.optCleanString("subtitleTrack"),
            audioTrack = obj.optCleanString("audioTrack"),
            speedPercent = if (obj.isNull("speedPercent")) null else obj.optInt("speedPercent"),
            skipLabel = obj.optCleanString("skipLabel"),
            canNextEpisode = obj.optBoolean("canNextEpisode", false),
            sleepMinutes = obj.optInt("sleepMinutes", 0).coerceAtLeast(0),
            shuffle = if (obj.isNull("shuffle")) null else obj.optBoolean("shuffle"),
            repeat = if (obj.isNull("repeat")) null else obj.optInt("repeat"),
            liked = if (obj.isNull("liked")) null else obj.optBoolean("liked"),
        )
    }

    fun parseSourceList(obj: JSONObject?): NuvioSourceList? {
        if (obj == null) return null
        val arr = obj.optJSONArray("sources") ?: JSONArray()
        val list = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(
                    NuvioSourceItem(
                        number = item.optInt("number", i + 1),
                        name = item.optCleanString("name") ?: "${l10nCurrent("Source", "Fonte")} ${i + 1}",
                        detail = item.optCleanString("detail"),
                        addon = item.optCleanString("addon"),
                        sizeBytes = if (item.isNull("sizeBytes")) null else item.optLong("sizeBytes"),
                        instant = item.optBoolean("instant", false),
                        playable = item.optBoolean("playable", true),
                    ),
                )
            }
        }
        return NuvioSourceList(
            title = obj.optString("title", ""),
            loading = obj.optBoolean("loading", false),
            sources = list,
        )
    }

    fun parseTrackList(obj: JSONObject): NuvioTrackList {
        val arr = obj.optJSONArray("choices") ?: JSONArray()
        val list = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(
                    NuvioTrackChoice(
                        number = item.optInt("number", i + 1),
                        label = item.optCleanString("label") ?: "${l10nCurrent("Track", "Faixa")} ${i + 1}",
                        detail = item.optCleanString("detail"),
                        group = item.optString("group", ""),
                        selected = item.optBoolean("selected", false),
                    ),
                )
            }
        }
        return NuvioTrackList(
            loading = obj.optBoolean("loading", false),
            choices = list,
        )
    }

    fun parseQueue(obj: JSONObject): NuvioQueueSnapshot {
        val arr = obj.optJSONArray("tracks") ?: JSONArray()
        val list = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                val vid = item?.optCleanString("videoId")
                if (item != null && vid != null) {
                    add(
                        NuvioQueueTrack(
                            videoId = vid,
                            title = item.optCleanString("title") ?: l10nCurrent("Track", "Faixa"),
                            artist = item.optString("artist", ""),
                            seconds = item.optInt("seconds", 0).coerceAtLeast(0),
                            cover = item.optCleanString("cover"),
                            upcoming = item.optBoolean("upcoming", false),
                        ),
                    )
                }
            }
        }
        return NuvioQueueSnapshot(
            playing = obj.optInt("playing", -1),
            tracks = list,
        )
    }
}
