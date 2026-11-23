package com.nuvio.remote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.AppsSubTab
import com.nuvio.remote.connection.NuvioBridgeStateHolder
import com.nuvio.remote.connection.NuvioBridgeStatus
import com.nuvio.remote.connection.NuvioMusicAlbum
import com.nuvio.remote.connection.NuvioVideoTitle
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.LocalAppLanguage
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import kotlinx.coroutines.delay

private data class AppModuleItem(
    val titleEn: String,
    val titlePt: String,
    val keyName: String,
    val icon: ImageVector,
)

private val NUVIO_MODULES = listOf(
    AppModuleItem("Home", "Início", "H", Icons.Rounded.Home),
    AppModuleItem("Search", "Busca", "S", Icons.Rounded.Search),
    AppModuleItem("Library", "Biblioteca", "L", Icons.Rounded.VideoLibrary),
    AppModuleItem("Discover", "Descobrir", "D", Icons.Rounded.Explore),
    AppModuleItem("YouTube", "YouTube", "Y", Icons.Rounded.Slideshow),
    AppModuleItem("Music", "Música", "U", Icons.AutoMirrored.Rounded.QueueMusic),
    AppModuleItem("Live TV", "Live TV", "V", Icons.Rounded.LiveTv),
    AppModuleItem("Game Mode", "Modo Jogo", "G", Icons.Rounded.SportsEsports),
    AppModuleItem("Calendar", "Calendário", "C", Icons.Rounded.CalendarMonth),
    AppModuleItem("Fullscreen", "Tela Cheia", "F11", Icons.Rounded.Fullscreen),
)

/**
 * Apps tab exclusive to Nuvio Mode (Watch, Music, and Modules).
 * Lists and poster grids are 100% opaque; glass is only used on floating overlay sheets.
 */
@Composable
fun AppsScreen(
    subTab: AppsSubTab,
    bridge: NuvioBridgeStateHolder,
    onKey: (String) -> Unit,
    onWindowAction: (String) -> Unit,
    onTogglePlayerMode: () -> Unit,
    onOpenRemoteTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bridgeStatus by bridge.status.collectAsState()
    val bridgeConnected = bridgeStatus == NuvioBridgeStatus.CONNECTED
    val liveSources by bridge.sources.collectAsState()
    val catalog = bridge.catalog
    val videoLibrary by catalog.videoLibrary.collectAsState()
    val musicLibrary by catalog.musicLibrary.collectAsState()
    val continueItems by catalog.continueWatching.collectAsState()
    val episodesResult by catalog.episodes.collectAsState()
    val searchResults by catalog.searchResults.collectAsState()
    val albumTracks by catalog.albumTracks.collectAsState()
    val menuSheet by catalog.menuSheet.collectAsState()
    val playlistsSheet by catalog.playlistsSheet.collectAsState()
    val recapSheet by catalog.recapSheet.collectAsState()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf("all") }
    var selectedSeries by remember { mutableStateOf<NuvioVideoTitle?>(null) }
    var selectedAlbum by remember { mutableStateOf<NuvioMusicAlbum?>(null) }
    var sourcesDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(liveSources == null) {
        if (liveSources == null) sourcesDismissed = false
    }

    val hasOverlay = listOf(selectedSeries, selectedAlbum, menuSheet, playlistsSheet, recapSheet).any { it != null }

    fun dismissOverlays() {
        selectedSeries = null
        selectedAlbum = null
        catalog.dismissOverlays()
    }

    BackHandler(enabled = hasOverlay) { dismissOverlays() }

    LaunchedEffect(bridgeConnected, subTab) {
        if (bridgeConnected) requestSubTabCatalogData(catalog, subTab)
    }

    LaunchedEffect(bridgeConnected, searchQuery) {
        val trimmed = searchQuery.trim()
        if (bridgeConnected && trimmed.length >= 2) {
            delay(380L)
            catalog.search(trimmed)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (subTab) {
            AppsSubTab.WATCH -> AppsWatchTab(
                bridgeConnected = bridgeConnected,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onSubmitSearch = { q -> if (q.isNotBlank()) catalog.search(q) },
                selectedFilter = selectedFilter,
                onSelectFilter = { selectedFilter = it },
                continueItems = continueItems,
                videoLibrary = videoLibrary,
                searchResults = searchResults,
                onRefresh = {
                    catalog.requestContinue()
                    catalog.requestLibrary("video")
                },
                onResume = { item -> catalog.resume(item.parentId, item.videoId) },
                onOpenTitle = { title ->
                    if (title.contentType.equals("series", ignoreCase = true)) {
                        selectedSeries = title
                        catalog.requestEpisodes(title.contentType, title.id)
                    } else {
                        catalog.openTitle(title.contentType, title.id)
                    }
                },
                onOpenMenuForTitle = { title ->
                    catalog.requestMenu("title", title.contentType, title.id)
                },
                onOpenMenuForContinue = { item ->
                    catalog.requestMenu("continue", item.contentType, item.parentId, item.videoId)
                },
            )
            AppsSubTab.MUSIC -> AppsMusicTab(
                bridgeConnected = bridgeConnected,
                musicLibrary = musicLibrary,
                onOpenAlbum = { album ->
                    selectedAlbum = album
                    catalog.requestAlbumTracks(album.browseId)
                },
                onArtistAction = { artistId, action -> catalog.artistAction(artistId, action) },
            )
            AppsSubTab.MODULES -> AppsModulesTab(
                onKey = onKey,
                onWindowAction = onWindowAction,
                onTogglePlayerMode = onTogglePlayerMode,
                onOpenRemoteTab = onOpenRemoteTab,
            )
        }

        AppsCatalogOverlayHost(
            selectedSeries = selectedSeries,
            episodesResult = episodesResult,
            selectedAlbum = selectedAlbum,
            albumTracks = albumTracks,
            menuSheet = menuSheet,
            playlistsSheet = playlistsSheet,
            recapSheet = recapSheet,
            onDismissAll = ::dismissOverlays,
            onPlayEpisode = { ep ->
                selectedSeries?.let { series ->
                    catalog.playEpisode(series.contentType, series.id, ep.videoId)
                }
                dismissOverlays()
            },
            onOpenEpisodeMenu = { ep ->
                selectedSeries?.let { series ->
                    catalog.requestMenu("episode", series.contentType, series.id, ep.videoId)
                }
            },
            onPlayTrack = { browseId, index ->
                catalog.playTrack(browseId, index)
                dismissOverlays()
            },
            onPlayAlbumAction = { browseId, action ->
                catalog.playAlbum(browseId, action)
                dismissOverlays()
            },
            onExecuteMenuAction = { menu, code ->
                catalog.doMenuAction(menu.kind, menu.contentType, menu.id, code, menu.ref)
                if (code != "playlists" && code != "recap") {
                    catalog.dismissOverlays()
                }
            },
            onSelectPlaylist = { playlists, plId ->
                catalog.doMenuAction(
                    kind = "title",
                    contentType = playlists.scope,
                    id = playlists.target,
                    code = "playlist_toggle",
                    args = listOf(plId),
                )
                catalog.dismissOverlays()
            },
        )

        if (liveSources != null && !sourcesDismissed) {
            PlayerOverlaySheetHost(
                sheet = PlayerSheetType.SOURCES,
                nowPlaying = null,
                subtitles = com.nuvio.remote.connection.NuvioTrackList(),
                audioTracks = com.nuvio.remote.connection.NuvioTrackList(),
                sources = liveSources,
                musicQueue = com.nuvio.remote.connection.NuvioQueueSnapshot(),
                onDismiss = { sourcesDismissed = true },
                onPickSubtitle = {},
                onPickAudio = {},
                onRequestSources = { bridge.requestSources(force = true) },
                onPickSource = { n ->
                    bridge.pickSource(n)
                    sourcesDismissed = true
                },
                onQueueAction = { _, _ -> },
                onControl = { _, _ -> },
                onKey = onKey,
            )
        }
    }
}

private fun requestSubTabCatalogData(
    catalog: com.nuvio.remote.connection.NuvioCatalogStateHolder,
    subTab: AppsSubTab,
) {
    when (subTab) {
        AppsSubTab.WATCH -> {
            catalog.requestContinue()
            catalog.requestLibrary("video")
        }
        AppsSubTab.MUSIC -> catalog.requestLibrary("music")
        AppsSubTab.MODULES -> Unit
    }
}

@Composable
private fun AppsModulesTab(
    onKey: (String) -> Unit,
    onWindowAction: (String) -> Unit,
    onTogglePlayerMode: () -> Unit,
    onOpenRemoteTab: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(title = l10n("NUVIO PLAYBACK", "REPRODUÇÃO NO NUVIO")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppActionTile(
                        label = l10n("Player Panel", "Painel Player"),
                        icon = Icons.Rounded.Tune,
                        highlighted = true,
                        onClick = {
                            onTogglePlayerMode()
                            onOpenRemoteTab()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    AppActionTile(
                        label = "Play / Pause",
                        icon = Icons.Rounded.PlayArrow,
                        highlighted = false,
                        onClick = { onKey("Space") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            SectionCard(title = l10n("NUVIO MODULES", "MÓDULOS DO NUVIO")) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NUVIO_MODULES.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            pair.forEach { module ->
                                AppActionTile(
                                    label = l10n(lang, module.titleEn, module.titlePt),
                                    icon = module.icon,
                                    highlighted = false,
                                    onClick = { onKey(module.keyName) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = l10n("NUVIO WINDOW ON PC", "JANELA DO NUVIO NO COMPUTADOR")) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppActionTile(
                            label = l10n("Bring to Front", "Trazer à Frente"),
                            icon = Icons.Rounded.OpenInBrowser,
                            highlighted = false,
                            onClick = { onWindowAction("focus") },
                            modifier = Modifier.weight(1f),
                        )
                        AppActionTile(
                            label = l10n("Restart Nuvio", "Reiniciar Nuvio"),
                            icon = Icons.Rounded.Refresh,
                            highlighted = false,
                            onClick = { onWindowAction("restart_nuvio") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    NuvioText(
                        text = l10n(
                            "Focuses or restarts the Nuvio window on your PC. General PC actions live in " +
                                "Trackpad and Screen.",
                            "Traz a janela do Nuvio para o primeiro plano no computador ou reinicia o " +
                                "aplicativo. Ações gerais do PC ficam em Trackpad e Tela.",
                        ),
                        style = NuvioTypography.KeyShortcutBadge,
                        color = scheme.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val scheme = LocalNuvioColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(22.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NuvioText(
            text = title,
            style = NuvioTypography.SectionMicroTitle,
            color = scheme.textMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        content()
    }
}

@Composable
private fun AppActionTile(
    label: String,
    icon: ImageVector,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.96f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "appTileScale",
    )

    val fgColor = if (highlighted) scheme.pillActiveFg else scheme.textPrimary
    val bgModifier = if (highlighted) {
        Modifier.background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
    } else {
        Modifier
            .background(if (pressed) scheme.contentCellPressed else scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(16.dp))
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(14.dp))
                .then(bgModifier)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (highlighted) scheme.pillActiveFg.copy(alpha = 0.14f) else scheme.contentCardSurface,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                NuvioIcon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = fgColor,
                    modifier = Modifier.size(15.dp),
                )
            }
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = fgColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            NuvioIcon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = if (highlighted) scheme.pillActiveFg.copy(alpha = 0.65f) else scheme.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
