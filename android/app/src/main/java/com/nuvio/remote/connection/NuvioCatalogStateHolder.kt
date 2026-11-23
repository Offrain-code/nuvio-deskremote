package com.nuvio.remote.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Catalog state and commands (Watch, Library, Continue Watching, Episodes,
 * Addon Search, Music Albums/Artists, and Context Action Menus).
 */
class NuvioCatalogStateHolder(
    private val sendJson: (type: String, body: JSONObject.() -> Unit) -> Boolean,
) {
    private val _videoLibrary = MutableStateFlow(NuvioVideoLibrary())
    val videoLibrary: StateFlow<NuvioVideoLibrary> = _videoLibrary

    private val _musicLibrary = MutableStateFlow(NuvioMusicLibrary())
    val musicLibrary: StateFlow<NuvioMusicLibrary> = _musicLibrary

    private val _continueWatching = MutableStateFlow<List<NuvioContinueItem>>(emptyList())
    val continueWatching: StateFlow<List<NuvioContinueItem>> = _continueWatching

    private val _episodes = MutableStateFlow(NuvioEpisodesResult())
    val episodes: StateFlow<NuvioEpisodesResult> = _episodes

    private val _searchResults = MutableStateFlow(NuvioSearchResults())
    val searchResults: StateFlow<NuvioSearchResults> = _searchResults

    private val _albumTracks = MutableStateFlow(NuvioAlbumTracksResult())
    val albumTracks: StateFlow<NuvioAlbumTracksResult> = _albumTracks

    private val _menuSheet = MutableStateFlow<NuvioMenuSheet?>(null)
    val menuSheet: StateFlow<NuvioMenuSheet?> = _menuSheet

    private val _playlistsSheet = MutableStateFlow<NuvioPlaylistsSheet?>(null)
    val playlistsSheet: StateFlow<NuvioPlaylistsSheet?> = _playlistsSheet

    private val _recapSheet = MutableStateFlow<NuvioRecapSheet?>(null)
    val recapSheet: StateFlow<NuvioRecapSheet?> = _recapSheet

    private val _actionDone = MutableStateFlow<NuvioActionDone?>(null)
    val actionDone: StateFlow<NuvioActionDone?> = _actionDone

    fun handleServerMessage(type: String, obj: JSONObject): Boolean = when (type) {
        "nuvio_library_video" -> {
            _videoLibrary.value = NuvioVideoLibrary(
                ready = obj.optBoolean("ready", false),
                items = NuvioCatalogParser.parseVideoTitles(obj.optJSONArray("items")),
            )
            true
        }
        "nuvio_library_music" -> {
            _musicLibrary.value = NuvioCatalogParser.parseMusicLibrary(obj)
            true
        }
        "nuvio_continue" -> {
            _continueWatching.value = NuvioCatalogParser.parseContinueList(obj.optJSONArray("items"))
            true
        }
        "nuvio_episodes" -> {
            _episodes.value = NuvioCatalogParser.parseEpisodes(obj)
            true
        }
        "nuvio_search_results" -> {
            _searchResults.value = NuvioSearchResults(
                loading = obj.optBoolean("loading", false),
                query = obj.optString("query", ""),
                items = NuvioCatalogParser.parseVideoTitles(obj.optJSONArray("items")),
            )
            true
        }
        "nuvio_album_tracks" -> {
            _albumTracks.value = NuvioCatalogParser.parseAlbumTracks(obj)
            true
        }
        "nuvio_menu" -> {
            _menuSheet.value = NuvioCatalogParser.parseMenu(obj.optJSONObject("menu"))
            true
        }
        "nuvio_playlists" -> {
            _playlistsSheet.value = NuvioCatalogParser.parsePlaylists(obj.optJSONObject("playlists"))
            true
        }
        "nuvio_recap" -> {
            _recapSheet.value = NuvioCatalogParser.parseRecap(obj.optJSONObject("recap"))
            true
        }
        "nuvio_action_done" -> {
            val done = NuvioCatalogParser.parseActionDone(obj.optJSONObject("done"))
            _actionDone.value = done
            if (done?.refresh == "library") requestLibrary("video")
            if (done?.refresh == "continue") requestContinue()
            true
        }
        else -> false
    }

    fun requestLibrary(kind: String): Boolean = sendJson("nuvio_library") { put("kind", kind) }

    fun requestContinue(): Boolean = sendJson("nuvio_continue") {}

    fun resume(parentId: String, videoId: String): Boolean = sendJson("nuvio_resume") {
        put("parentId", parentId)
        put("videoId", videoId)
    }

    fun openTitle(contentType: String, id: String): Boolean = sendJson("nuvio_open_title") {
        put("contentType", contentType)
        put("id", id)
    }

    fun requestEpisodes(contentType: String, id: String): Boolean = sendJson("nuvio_episodes") {
        put("contentType", contentType)
        put("id", id)
    }

    fun playEpisode(contentType: String, id: String, videoId: String): Boolean = sendJson("nuvio_play_episode") {
        put("contentType", contentType)
        put("id", id)
        put("videoId", videoId)
    }

    fun search(query: String): Boolean = sendJson("nuvio_search") { put("query", query) }

    fun requestAlbumTracks(browseId: String): Boolean = sendJson("nuvio_album_tracks") {
        put("browseId", browseId)
    }

    fun playTrack(browseId: String, index: Int): Boolean = sendJson("nuvio_play_track") {
        put("browseId", browseId)
        put("index", index)
    }

    fun playAlbum(browseId: String, action: String): Boolean = sendJson("nuvio_play_album") {
        put("browseId", browseId)
        put("action", action)
    }

    fun artistAction(artistId: String, action: String): Boolean = sendJson("nuvio_artist_action") {
        put("artistId", artistId)
        put("action", action)
    }

    fun requestMenu(kind: String, contentType: String, id: String, ref: String? = null): Boolean =
        sendJson("nuvio_menu") {
            put("kind", kind)
            put("contentType", contentType)
            put("id", id)
            if (!ref.isNullOrBlank()) put("ref", ref)
        }

    fun doMenuAction(
        kind: String,
        contentType: String,
        id: String,
        code: String,
        ref: String? = null,
        args: List<String> = emptyList(),
    ): Boolean = sendJson("nuvio_do") {
        put("kind", kind)
        put("contentType", contentType)
        put("id", id)
        put("code", code)
        if (!ref.isNullOrBlank()) put("ref", ref)
        if (args.isNotEmpty()) put("args", JSONArray(args))
    }

    fun dismissOverlays() {
        _menuSheet.value = null
        _playlistsSheet.value = null
        _recapSheet.value = null
    }
}
