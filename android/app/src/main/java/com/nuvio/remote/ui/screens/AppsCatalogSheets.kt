package com.nuvio.remote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.NuvioAlbumTracksResult
import com.nuvio.remote.connection.NuvioEpisodeItem
import com.nuvio.remote.connection.NuvioEpisodesResult
import com.nuvio.remote.connection.NuvioMenuSheet
import com.nuvio.remote.connection.NuvioMusicAlbum
import com.nuvio.remote.connection.NuvioPlaylistsSheet
import com.nuvio.remote.connection.NuvioRecapSheet
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.connection.NuvioVideoTitle
import com.nuvio.remote.connection.formatMediaTime
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.LocalAppLanguage
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.accentForScreen
import com.nuvio.remote.ui.theme.nuvioGlass

/**
 * Floating Liquid Glass overlay sheets (`GlassVariant.REGULAR` outer container + opaque inner cells):
 * Seasons & Episodes, Action Menu, Playlists/AI Recap, and Album Tracks.
 */
@Composable
fun AppsCatalogOverlayHost(
    selectedSeries: NuvioVideoTitle?,
    episodesResult: NuvioEpisodesResult,
    selectedAlbum: NuvioMusicAlbum?,
    albumTracks: NuvioAlbumTracksResult,
    menuSheet: NuvioMenuSheet?,
    playlistsSheet: NuvioPlaylistsSheet?,
    recapSheet: NuvioRecapSheet?,
    onDismissAll: () -> Unit,
    onPlayEpisode: (NuvioEpisodeItem) -> Unit,
    onOpenEpisodeMenu: (NuvioEpisodeItem) -> Unit,
    onPlayTrack: (browseId: String, index: Int) -> Unit,
    onPlayAlbumAction: (browseId: String, action: String) -> Unit,
    onExecuteMenuAction: (NuvioMenuSheet, String) -> Unit,
    onSelectPlaylist: (NuvioPlaylistsSheet, String) -> Unit,
) {
    val visible = selectedSeries != null ||
        selectedAlbum != null ||
        menuSheet != null ||
        playlistsSheet != null ||
        recapSheet != null
    val reducedMotion = LocalReducedMotion.current
    val playerAccent = accentForScreen(NuvioScreenState.PLAYER)

    AnimatedVisibility(
        visible = visible,
        enter = NuvioMotion.morphEnter(reducedMotion),
        exit = NuvioMotion.morphExit(reducedMotion),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.44f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissAll,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .nuvioGlass(
                        shape = RoundedCornerShape(24.dp),
                        variant = GlassVariant.REGULAR,
                        elevated = true,
                        accentTint = playerAccent.surfaceTint,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    recapSheet != null -> RecapSheetBody(recap = recapSheet, onClose = onDismissAll)
                    playlistsSheet != null -> PlaylistsSheetBody(
                        playlists = playlistsSheet,
                        onSelect = { plId -> onSelectPlaylist(playlistsSheet, plId) },
                        onClose = onDismissAll,
                    )
                    menuSheet != null -> MenuSheetBody(
                        menu = menuSheet,
                        onAction = { code -> onExecuteMenuAction(menuSheet, code) },
                        onClose = onDismissAll,
                    )
                    selectedAlbum != null -> AlbumTracksSheetBody(
                        album = selectedAlbum,
                        tracksResult = albumTracks,
                        onPlayTrack = { idx -> onPlayTrack(selectedAlbum.browseId, idx) },
                        onAlbumAction = { act -> onPlayAlbumAction(selectedAlbum.browseId, act) },
                        onClose = onDismissAll,
                    )
                    selectedSeries != null -> EpisodesSheetBody(
                        series = selectedSeries,
                        result = episodesResult,
                        onPlayEpisode = onPlayEpisode,
                        onOpenMenu = onOpenEpisodeMenu,
                        onClose = onDismissAll,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlaySheetHeader(title: String, subtitle: String, onClose: () -> Unit) {
    val scheme = LocalNuvioColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NuvioText(
                text = title,
                style = NuvioTypography.SectionMicroTitle,
                color = accentForScreen(NuvioScreenState.PLAYER).primary,
                maxLines = 1,
            )
            NuvioText(
                text = subtitle,
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier.size(48.dp).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = l10n("Close", "Fechar"),
                    tint = scheme.textPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodesSheetBody(
    series: NuvioVideoTitle,
    result: NuvioEpisodesResult,
    onPlayEpisode: (NuvioEpisodeItem) -> Unit,
    onOpenMenu: (NuvioEpisodeItem) -> Unit,
    onClose: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    val seasons = remember(result.episodes) { result.episodes.map { it.season }.distinct().sorted() }
    var selectedSeason by remember(series.id, seasons) {
        mutableIntStateOf(seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1)
    }
    val seasonEps = remember(result.episodes, selectedSeason) {
        result.episodes.filter { it.season == selectedSeason }
    }

    OverlaySheetHeader(
        title = l10n("SEASONS & EPISODES", "TEMPORADAS E EPISÓDIOS"),
        subtitle = "${series.name} • ${l10n("Tap plays on TV", "Toque reproduz na TV")}",
        onClose = onClose,
    )

    if (seasons.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            seasons.forEach { season ->
                val count = result.episodes.count { it.season == season }
                val label = if (season == 0) {
                    l10n(lang, "Extras ($count)", "Extras ($count)")
                } else {
                    l10n(lang, "S$season ($count)", "T$season ($count)")
                }
                CatalogFilterPill(
                    label = label,
                    selected = selectedSeason == season,
                    onClick = { selectedSeason = season },
                )
            }
        }
    }

    if (seasonEps.isEmpty()) {
        CatalogInfoBox(
            text = if (result.id != series.id) {
                l10n("Loading episodes of ${series.name}…", "Carregando episódios de ${series.name}…")
            } else {
                l10n(
                    "No episodes available for this season.",
                    "Nenhum episódio disponível para esta temporada.",
                )
            },
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(seasonEps, key = { it.videoId }) { ep ->
            val pct = (ep.progressPermille / 10).coerceIn(0, 100)
            val status = when {
                pct >= 90 -> l10n(lang, "Watched", "Assistido")
                pct > 0 -> l10n(lang, "$pct% watched", "$pct% assistido")
                else -> ep.released ?: l10n(lang, "Unwatched", "Não assistido")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                    .combinedClickable(
                        onClick = { onPlayEpisode(ep) },
                        onLongClick = { onOpenMenu(ep) },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val fallbackTitle = l10n(lang, "Episode ${ep.episode}", "Episódio ${ep.episode}")
                    val label = "E%02d • %s".format(ep.episode, ep.title.ifBlank { fallbackTitle })
                    NuvioText(
                        text = label,
                        style = NuvioTypography.KeyLabelPrimary,
                        color = scheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    NuvioText(
                        text = status,
                        style = NuvioTypography.KeyShortcutBadge,
                        color = scheme.textMuted,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier.size(48.dp).clickable { onOpenMenu(ep) },
                    contentAlignment = Alignment.Center,
                ) {
                    NuvioIcon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = l10n("Episode actions", "Ações do episódio"),
                        tint = scheme.textPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuSheetBody(menu: NuvioMenuSheet, onAction: (String) -> Unit, onClose: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current

    OverlaySheetHeader(
        title = l10n("TITLE ACTION MENU", "MENU DE AÇÕES DO TÍTULO"),
        subtitle = listOfNotNull(menu.heading.takeIf { it.isNotBlank() }, menu.detail).joinToString(" • "),
        onClose = onClose,
    )

    if (menu.actions.isEmpty()) {
        CatalogInfoBox(
            text = l10n(
                "No actions available for this item in Nuvio.",
                "Nenhuma ação disponível para este item no Nuvio.",
            ),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(menu.actions, key = { it.code }) { act ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                    .clickable {
                        if (hapticsEnabled) Haptics.action(context)
                        onAction(act.code)
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioText(
                    text = act.label,
                    style = NuvioTypography.KeyLabelPrimary,
                    color = scheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlaylistsSheetBody(playlists: NuvioPlaylistsSheet, onSelect: (String) -> Unit, onClose: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    OverlaySheetHeader(
        title = l10n("NUVIO PLAYLISTS", "PLAYLISTS DO NUVIO"),
        subtitle = playlists.label.ifBlank { l10n("Select a playlist", "Selecione a playlist") },
        onClose = onClose,
    )

    if (playlists.items.isEmpty()) {
        CatalogInfoBox(text = l10n("No playlists created in Nuvio.", "Nenhuma playlist cadastrada no Nuvio."))
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(playlists.items, key = { it.id }) { pl ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                    .clickable { onSelect(pl.id) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioText(
                    text = pl.name,
                    style = NuvioTypography.KeyLabelPrimary,
                    color = scheme.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                NuvioText(
                    text = "${pl.count} ${l10n(lang, "titles", "títulos")}",
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.textMuted,
                )
            }
        }
    }
}

@Composable
private fun RecapSheetBody(recap: NuvioRecapSheet, onClose: () -> Unit) {
    val scheme = LocalNuvioColors.current
    OverlaySheetHeader(
        title = l10n("AI RECAP", "RESUMO COM IA"),
        subtitle = recap.heading.ifBlank { l10n("Contextual summary from Nuvio", "Síntese contextual do Nuvio") },
        onClose = onClose,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp, max = 340.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        NuvioText(
            text = recap.text.ifBlank { l10n("Generating AI recap in Nuvio…", "Gerando resumo com IA no Nuvio…") },
            style = NuvioTypography.BodyRegular,
            color = scheme.textPrimary,
        )
    }
}

@Composable
private fun AlbumTracksSheetBody(
    album: NuvioMusicAlbum,
    tracksResult: NuvioAlbumTracksResult,
    onPlayTrack: (Int) -> Unit,
    onAlbumAction: (String) -> Unit,
    onClose: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    OverlaySheetHeader(
        title = l10n("ALBUM TRACKS", "FAIXAS DO ÁLBUM"),
        subtitle = album.title,
        onClose = onClose,
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AlbumActionButton(
            label = l10n("Play", "Tocar"),
            icon = Icons.Rounded.PlayArrow,
            primary = true,
            onClick = { onAlbumAction("play") },
            modifier = Modifier.weight(1f),
        )
        AlbumActionButton(
            label = l10n("Shuffle", "Aleatório"),
            icon = Icons.Rounded.Shuffle,
            primary = false,
            onClick = { onAlbumAction("shuffle") },
            modifier = Modifier.weight(1f),
        )
        AlbumActionButton(
            label = l10n("Queue", "Na fila"),
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            primary = false,
            onClick = { onAlbumAction("queue") },
            modifier = Modifier.weight(1f),
        )
    }

    val tracks = if (tracksResult.browseId == album.browseId) tracksResult.tracks else emptyList()
    if (tracks.isEmpty()) {
        CatalogInfoBox(text = l10n("Loading album tracks…", "Carregando faixas do álbum…"))
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(tracks, key = { i, t -> "${i}_${t.videoId}" }) { idx, track ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                    .clickable(enabled = track.playable) { onPlayTrack(idx) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NuvioText(
                        text = "%02d. %s".format(idx + 1, track.title),
                        style = NuvioTypography.KeyLabelPrimary,
                        color = if (track.playable) scheme.textPrimary else scheme.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (track.artist.isNotBlank()) {
                        NuvioText(
                            text = track.artist,
                            style = NuvioTypography.KeyShortcutBadge,
                            color = scheme.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (track.seconds > 0) {
                    NuvioText(
                        text = formatMediaTime(track.seconds * 1000L),
                        style = NuvioTypography.KeyShortcutBadge,
                        color = scheme.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumActionButton(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val fg = if (primary) scheme.pillActiveFg else scheme.textPrimary
    val bg = if (primary) {
        Modifier.background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
    } else {
        Modifier
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(12.dp))
    }
    Box(
        modifier = modifier.defaultMinSize(minHeight = 48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(bg)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            NuvioIcon(imageVector = icon, contentDescription = label, tint = fg, modifier = Modifier.size(15.dp))
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = fg,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
