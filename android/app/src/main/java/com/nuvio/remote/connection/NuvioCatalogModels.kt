package com.nuvio.remote.connection

import androidx.compose.runtime.Immutable
import com.nuvio.remote.ui.i18n.ResolvedLanguage
import com.nuvio.remote.ui.i18n.l10n
import org.json.JSONArray
import org.json.JSONObject

enum class AppsSubTab(val labelEn: String, val labelPt: String) {
    WATCH("Watch", "Assistir"),
    MUSIC("Music", "Música"),
    MODULES("Modules", "Módulos"),
    ;

    val label: String
        get() = labelEn

    fun label(lang: ResolvedLanguage): String = l10n(lang, labelEn, labelPt)
}

@Immutable
data class NuvioWakeInfo(
    val macs: List<String> = emptyList(),
    val broadcasts: List<String> = emptyList(),
)

@Immutable
data class NuvioVideoTitle(
    val section: String,
    val contentType: String,
    val id: String,
    val name: String,
    val year: String?,
    val poster: String?,
    val posterFallback: String?,
    val watched: Boolean,
)

@Immutable
data class NuvioVideoLibrary(
    val ready: Boolean = false,
    val items: List<NuvioVideoTitle> = emptyList(),
)

@Immutable
data class NuvioContinueItem(
    val contentType: String,
    val parentId: String,
    val videoId: String,
    val title: String,
    val episode: String?,
    val poster: String?,
    val still: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastSource: String?,
    val progressPermille: Int,
) {
    val remainingMinutes: Int?
        get() {
            if (durationMs <= 0L) return null
            val remMs = (durationMs - positionMs).coerceAtLeast(0L)
            return ((remMs + 30_000L) / 60_000L).toInt()
        }
}

@Immutable
data class NuvioEpisodeItem(
    val season: Int,
    val episode: Int,
    val title: String,
    val videoId: String,
    val thumbnail: String?,
    val released: String?,
    val progressPermille: Int,
)

@Immutable
data class NuvioEpisodesResult(
    val contentType: String = "",
    val id: String = "",
    val found: Boolean = false,
    val episodes: List<NuvioEpisodeItem> = emptyList(),
)

@Immutable
data class NuvioSearchResults(
    val loading: Boolean = false,
    val query: String = "",
    val items: List<NuvioVideoTitle> = emptyList(),
)

@Immutable
data class NuvioMusicArtist(
    val id: String,
    val name: String,
    val photo: String?,
    val starred: Boolean,
)

@Immutable
data class NuvioMusicAlbum(
    val artistId: String,
    val browseId: String,
    val title: String,
    val kind: String?,
    val year: String?,
    val cover: String?,
    val starred: Boolean,
)

@Immutable
data class NuvioMusicLibrary(
    val ready: Boolean = false,
    val artists: List<NuvioMusicArtist> = emptyList(),
    val albums: List<NuvioMusicAlbum> = emptyList(),
)

@Immutable
data class NuvioAlbumTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val seconds: Int,
    val playable: Boolean,
)

@Immutable
data class NuvioAlbumTracksResult(
    val browseId: String = "",
    val tracks: List<NuvioAlbumTrack> = emptyList(),
)

@Immutable
data class NuvioMenuAction(val code: String, val label: String)

@Immutable
data class NuvioMenuSheet(
    val kind: String,
    val contentType: String,
    val id: String,
    val ref: String?,
    val heading: String,
    val detail: String?,
    val actions: List<NuvioMenuAction>,
)

@Immutable
data class NuvioPlaylistChoice(val id: String, val name: String, val count: Int)

@Immutable
data class NuvioPlaylistsSheet(
    val target: String,
    val scope: String,
    val label: String,
    val seasons: String?,
    val items: List<NuvioPlaylistChoice>,
)

@Immutable
data class NuvioRecapSheet(val state: Int, val heading: String, val text: String)

@Immutable
data class NuvioActionDone(val ok: Boolean, val message: String, val refresh: String?)

object NuvioCatalogParser {
    private fun JSONObject.optClean(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val value = optString(i, "").trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    fun parseWake(obj: JSONObject?): NuvioWakeInfo? {
        if (obj == null) return null
        val macs = obj.optJSONArray("macs").toStringList()
        val broadcasts = obj.optJSONArray("broadcasts").toStringList()
        return if (macs.isEmpty()) null else NuvioWakeInfo(macs = macs, broadcasts = broadcasts)
    }

    fun parseVideoTitles(arr: JSONArray?): List<NuvioVideoTitle> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                val id = item?.optClean("id")
                val name = item?.optClean("name")
                if (item != null && id != null && name != null) {
                    add(
                        NuvioVideoTitle(
                            section = item.optString("section", ""),
                            contentType = item.optString("contentType", "movie"),
                            id = id,
                            name = name,
                            year = item.optClean("year"),
                            poster = item.optClean("poster"),
                            posterFallback = item.optClean("posterFallback"),
                            watched = item.optBoolean("watched", false),
                        ),
                    )
                }
            }
        }
    }

    fun parseContinueList(arr: JSONArray?): List<NuvioContinueItem> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let(::parseContinueItem)?.let(::add)
            }
        }
    }

    private fun parseContinueItem(item: JSONObject): NuvioContinueItem? {
        val parentId = item.optClean("parentId")
        val videoId = item.optClean("videoId")
        val title = item.optClean("title")
        if (parentId == null || videoId == null || title == null) return null
        return NuvioContinueItem(
            contentType = item.optString("contentType", "movie"),
            parentId = parentId,
            videoId = videoId,
            title = title,
            episode = item.optClean("episode"),
            poster = item.optClean("poster"),
            still = item.optClean("still"),
            positionMs = item.optLong("positionMs", 0L).coerceAtLeast(0L),
            durationMs = item.optLong("durationMs", 0L).coerceAtLeast(0L),
            lastSource = item.optClean("lastSource"),
            progressPermille = item.optInt("progressPermille", 0).coerceIn(0, 1000),
        )
    }

    fun parseEpisodes(obj: JSONObject): NuvioEpisodesResult {
        val arr = obj.optJSONArray("episodes") ?: JSONArray()
        val list = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                val vid = item?.optClean("videoId")
                if (item != null && vid != null) {
                    add(
                        NuvioEpisodeItem(
                            season = item.optInt("season", 0).coerceAtLeast(0),
                            episode = item.optInt("episode", 0).coerceAtLeast(0),
                            title = item.optClean("title") ?: "",
                            videoId = vid,
                            thumbnail = item.optClean("thumbnail"),
                            released = item.optClean("released"),
                            progressPermille = item.optInt("progressPermille", 0).coerceIn(0, 1000),
                        ),
                    )
                }
            }
        }
        return NuvioEpisodesResult(
            contentType = obj.optString("contentType", "series"),
            id = obj.optString("id", ""),
            found = obj.optBoolean("found", false),
            episodes = list,
        )
    }

    fun parseMusicLibrary(obj: JSONObject): NuvioMusicLibrary {
        val artistsArr = obj.optJSONArray("artists") ?: JSONArray()
        val artists = buildList {
            for (i in 0 until artistsArr.length()) {
                val item = artistsArr.optJSONObject(i)
                val id = item?.optClean("id")
                val name = item?.optClean("name")
                if (item != null && id != null && name != null) {
                    add(
                        NuvioMusicArtist(
                            id = id,
                            name = name,
                            photo = item.optClean("photo"),
                            starred = item.optBoolean("starred", false),
                        ),
                    )
                }
            }
        }
        val albumsArr = obj.optJSONArray("albums") ?: JSONArray()
        val albums = buildList {
            for (i in 0 until albumsArr.length()) {
                val item = albumsArr.optJSONObject(i)
                val browseId = item?.optClean("browseId")
                val title = item?.optClean("title")
                if (item != null && browseId != null && title != null) {
                    add(
                        NuvioMusicAlbum(
                            artistId = item.optString("artistId", ""),
                            browseId = browseId,
                            title = title,
                            kind = item.optClean("kind"),
                            year = item.optClean("year"),
                            cover = item.optClean("cover"),
                            starred = item.optBoolean("starred", false),
                        ),
                    )
                }
            }
        }
        return NuvioMusicLibrary(ready = obj.optBoolean("ready", false), artists = artists, albums = albums)
    }

    fun parseAlbumTracks(obj: JSONObject): NuvioAlbumTracksResult {
        val arr = obj.optJSONArray("tracks") ?: JSONArray()
        val tracks = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                val vid = item?.optClean("videoId")
                val title = item?.optClean("title")
                if (item != null && vid != null && title != null) {
                    add(
                        NuvioAlbumTrack(
                            videoId = vid,
                            title = title,
                            artist = item.optString("artist", ""),
                            seconds = item.optInt("seconds", 0).coerceAtLeast(0),
                            playable = item.optBoolean("playable", true),
                        ),
                    )
                }
            }
        }
        return NuvioAlbumTracksResult(browseId = obj.optString("browseId", ""), tracks = tracks)
    }

    fun parseMenu(obj: JSONObject?): NuvioMenuSheet? {
        if (obj == null) return null
        val id = obj.optClean("id") ?: return null
        val arr = obj.optJSONArray("actions") ?: JSONArray()
        val actions = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                val code = item?.optClean("code")
                val label = item?.optClean("label")
                if (code != null && label != null) add(NuvioMenuAction(code, label))
            }
        }
        return NuvioMenuSheet(
            kind = obj.optString("kind", "title"),
            contentType = obj.optString("contentType", "movie"),
            id = id,
            ref = obj.optClean("ref"),
            heading = obj.optString("heading", ""),
            detail = obj.optClean("detail"),
            actions = actions,
        )
    }

    fun parsePlaylists(obj: JSONObject?): NuvioPlaylistsSheet? {
        if (obj == null) return null
        val arr = obj.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                val id = item?.optClean("id")
                val name = item?.optClean("name")
                if (item != null && id != null && name != null) {
                    add(NuvioPlaylistChoice(id = id, name = name, count = item.optInt("count", 0).coerceAtLeast(0)))
                }
            }
        }
        return NuvioPlaylistsSheet(
            target = obj.optString("target", ""),
            scope = obj.optString("scope", ""),
            label = obj.optString("label", ""),
            seasons = obj.optClean("seasons"),
            items = items,
        )
    }

    fun parseRecap(obj: JSONObject?): NuvioRecapSheet? {
        if (obj == null) return null
        return NuvioRecapSheet(
            state = obj.optInt("state", 0),
            heading = obj.optString("heading", ""),
            text = obj.optString("text", ""),
        )
    }

    fun parseActionDone(obj: JSONObject?): NuvioActionDone? {
        if (obj == null) return null
        return NuvioActionDone(
            ok = obj.optBoolean("ok", false),
            message = obj.optString("message", ""),
            refresh = obj.optClean("refresh"),
        )
    }
}
