package com.nuvio.remote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.NuvioLyricsSheet
import com.nuvio.remote.connection.NuvioNowPlaying
import com.nuvio.remote.connection.NuvioQueueSnapshot
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.connection.NuvioSourceList
import com.nuvio.remote.connection.NuvioTrackChoice
import com.nuvio.remote.connection.NuvioTrackList
import com.nuvio.remote.connection.SourceBadgeParser
import com.nuvio.remote.connection.formatFileSizeBr
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
 * Container for the floating Liquid Glass (`GlassVariant.REGULAR`) player sheets (DESIGN-SPEC.md section 5.2.1).
 * Only the outer container uses glass; inner chips, cells, and badges remain 100% opaque.
 */
@Composable
fun PlayerOverlaySheetHost(
    sheet: PlayerSheetType,
    nowPlaying: NuvioNowPlaying?,
    subtitles: NuvioTrackList,
    audioTracks: NuvioTrackList,
    sources: NuvioSourceList?,
    musicQueue: NuvioQueueSnapshot,
    lyrics: NuvioLyricsSheet? = null,
    onDismiss: () -> Unit,
    onPickSubtitle: (Int) -> Unit,
    onPickAudio: (Int) -> Unit,
    onRequestSources: () -> Unit,
    onPickSource: (Int) -> Unit,
    onQueueAction: (action: String, videoId: String) -> Unit,
    onRefreshLyrics: () -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    onControl: (action: String, value: Int?) -> Unit,
    onKey: (String) -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val playerAccent = accentForScreen(NuvioScreenState.PLAYER)

    AnimatedVisibility(
        visible = sheet != PlayerSheetType.NONE,
        enter = NuvioMotion.morphEnter(reducedMotion),
        exit = NuvioMotion.morphExit(reducedMotion),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
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
                when (sheet) {
                    PlayerSheetType.SUBTITLES -> PlayerTrackSheetBody(
                        title = l10n("PLAYER SUBTITLES", "LEGENDAS DO PLAYER"),
                        subtitle = l10n("Quick tap on Subtitles toggles", "Toque rápido no botão Legenda alterna"),
                        list = subtitles,
                        emptyText = l10n(
                            "No subtitles received yet. Start playing a video in Nuvio.",
                            "Nenhuma legenda recebida ainda. Inicie um vídeo no Nuvio.",
                        ),
                        onClose = onDismiss,
                        onPick = { n ->
                            onPickSubtitle(n)
                            onDismiss()
                        },
                    )
                    PlayerSheetType.AUDIO -> PlayerTrackSheetBody(
                        title = l10n("AUDIO TRACKS", "FAIXAS DE ÁUDIO"),
                        subtitle = l10n(
                            "Select the active audio track in Nuvio",
                            "Selecione a trilha de áudio ativa no Nuvio",
                        ),
                        list = audioTracks,
                        emptyText = l10n(
                            "No audio tracks received yet.",
                            "Nenhuma faixa de áudio recebida ainda.",
                        ),
                        onClose = onDismiss,
                        onPick = { n ->
                            onPickAudio(n)
                            onDismiss()
                        },
                    )
                    PlayerSheetType.SOURCES -> PlayerSourcesSheetBody(
                        sources = sources,
                        onClose = onDismiss,
                        onPickSource = { n ->
                            onPickSource(n)
                            onDismiss()
                        },
                        onOpenOnTv = onRequestSources,
                    )
                    PlayerSheetType.QUEUE -> PlayerMusicQueueSheetBody(
                        queue = musicQueue,
                        onClose = onDismiss,
                        onQueueAction = onQueueAction,
                    )
                    PlayerSheetType.SPEED_TIMER -> PlayerSpeedTimerSheetBody(
                        speedPercent = nowPlaying?.speedPercent ?: 100,
                        sleepMinutes = nowPlaying?.sleepMinutes ?: 0,
                        onClose = onDismiss,
                        onControl = onControl,
                        onKey = { key ->
                            onKey(key)
                            onDismiss()
                        },
                    )
                    PlayerSheetType.LYRICS -> PlayerLyricsSheetBody(
                        lyrics = lyrics,
                        nowPlaying = nowPlaying,
                        onClose = onDismiss,
                        onRefresh = onRefreshLyrics,
                        onSeekTo = onSeekTo,
                        onKey = onKey,
                    )
                    PlayerSheetType.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String, onClose: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current

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
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (hapticsEnabled) Haptics.tab(context)
                    onClose()
                },
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

@Composable
private fun PlayerTrackSheetBody(
    title: String,
    subtitle: String,
    list: NuvioTrackList,
    emptyText: String,
    onClose: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val scheme = LocalNuvioColors.current
    SheetHeader(title = title, subtitle = subtitle, onClose = onClose)

    if (list.loading || list.choices.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioText(
                text = if (list.loading) {
                    l10n("Loading options from Nuvio…", "Carregando opções do Nuvio…")
                } else {
                    l10n("Options synced with Nuvio", "Opções sincronizadas com o Nuvio")
                },
                style = NuvioTypography.KeyShortcutBadge,
                color = if (list.loading) scheme.statusWarning else scheme.statusOnline,
            )
            NuvioText(
                text = "${list.choices.size} ${l10n("options", "opções")}",
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.textMuted,
            )
        }
    }

    if (list.choices.isEmpty()) {
        EmptySheetBox(text = emptyText)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list.choices, key = { "${it.number}-${it.label}" }) { item ->
            TrackChoiceRow(choice = item, onClick = { onPick(item.number) })
        }
    }
}

@Composable
private fun TrackChoiceRow(choice: NuvioTrackChoice, onClick: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val playerAccent = accentForScreen(NuvioScreenState.PLAYER)
    val border = if (choice.selected) playerAccent.primary.copy(alpha = 0.8f) else scheme.contentBorder

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NuvioText(
                text = choice.label,
                style = NuvioTypography.KeyLabelPrimary,
                color = scheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                choice.group.takeIf { it.isNotBlank() && it != choice.label },
                choice.detail?.takeIf { it.isNotBlank() },
            ).joinToString(" • ")
            if (meta.isNotEmpty()) {
                NuvioText(
                    text = meta,
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (choice.selected) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(scheme.pillActiveBgTop)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                NuvioText(
                    text = l10n("Active", "Ativa"),
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.pillActiveFg,
                )
            }
        }
    }
}

@Composable
private fun PlayerSourcesSheetBody(
    sources: NuvioSourceList?,
    onClose: () -> Unit,
    onPickSource: (Int) -> Unit,
    onOpenOnTv: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    var filterKey by remember { mutableStateOf(SourceBadgeParser.FILTER_ALL) }
    val filters = remember(lang) {
        listOf(
            l10n(lang, "All", "Todos") to SourceBadgeParser.FILTER_ALL,
            "4K" to SourceBadgeParser.FILTER_4K,
            "HDR" to SourceBadgeParser.FILTER_HDR,
            "Dolby Vision" to SourceBadgeParser.FILTER_DV,
        )
    }

    SheetHeader(
        title = l10n("SOURCES & STREAMS", "FONTES E STREAMS"),
        subtitle = sources?.title?.takeIf { it.isNotBlank() }
            ?: l10n("Original Nuvio order", "Ordem original do Nuvio"),
        onClose = onClose,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEach { (label, key) ->
            FilterChipCell(label = label, selected = filterKey == key, onClick = { filterKey = key })
        }
    }

    val allItems = sources?.sources.orEmpty()
    val filtered = remember(allItems, filterKey) {
        allItems.filter { item ->
            val facts = SourceBadgeParser.parse(item.name, item.detail)
            SourceBadgeParser.matches(facts, filterKey)
        }
    }

    if (allItems.isEmpty()) {
        EmptySheetBox(
            text = if (sources?.loading == true) {
                l10n("Loading available sources in Nuvio…", "Carregando fontes disponíveis no Nuvio…")
            } else {
                l10n(
                    "Open the sources panel in Nuvio to list streams here.",
                    "Abra o painel de fontes no Nuvio para listar os streams aqui.",
                )
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable(onClick = onOpenOnTv),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                NuvioText(
                    text = l10n("Open source list on TV", "Abrir lista de fontes na TV"),
                    style = NuvioTypography.KeyLabelCompact,
                    color = scheme.textPrimary,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filtered, key = { it.number }) { item ->
            val facts = remember(item.name, item.detail) { SourceBadgeParser.parse(item.name, item.detail) }
            val sizeText = remember(item.sizeBytes) { formatFileSizeBr(item.sizeBytes) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                    .clickable(enabled = item.playable) { onPickSource(item.number) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioText(
                        text = item.name,
                        style = NuvioTypography.KeyLabelPrimary,
                        color = if (item.playable) scheme.textPrimary else scheme.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.instant) {
                        SmallTagBadge(text = l10n("Instant", "Instantâneo"), accent = true)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    facts.all.forEach { badge -> SmallTagBadge(text = badge.label, accent = badge.accent) }
                    sizeText?.let {
                        SmallTagBadge(
                            text = if (item.instant) "${l10n("Cached", "Cache")} • $it" else it,
                            accent = false,
                        )
                    }
                    item.addon?.takeIf { it.isNotBlank() }?.let { SmallTagBadge(text = it, accent = false) }
                }
            }
        }
    }
}

@Composable
private fun PlayerMusicQueueSheetBody(
    queue: NuvioQueueSnapshot,
    onClose: () -> Unit,
    onQueueAction: (action: String, videoId: String) -> Unit,
) {
    val scheme = LocalNuvioColors.current
    SheetHeader(
        title = l10n("PLAYBACK QUEUE", "FILA DE REPRODUÇÃO"),
        subtitle = l10n(
            "Tap to play • Actions for upcoming tracks",
            "Toque para tocar • Ações para próximas faixas",
        ),
        onClose = onClose,
    )

    if (queue.tracks.isEmpty()) {
        EmptySheetBox(text = l10n("No active music queue in Nuvio.", "Nenhuma fila de música ativa no Nuvio."))
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(queue.tracks, key = { i, t -> "${i}_${t.videoId}" }) { index, track ->
            val isCurrent = index == queue.playing
            val playerAccent = accentForScreen(NuvioScreenState.PLAYER)
            val border = if (isCurrent) playerAccent.primary.copy(alpha = 0.8f) else scheme.contentBorder
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, border, RoundedCornerShape(14.dp))
                    .clickable { onQueueAction("play", track.videoId) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NuvioText(
                        text = "${index + 1}. ${track.title}",
                        style = NuvioTypography.KeyLabelPrimary,
                        color = scheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        track.artist.takeIf { it.isNotBlank() },
                        track.seconds.takeIf { it > 0 }?.let { formatMediaTime(it * 1000L) },
                    ).joinToString(" • ")
                    if (meta.isNotEmpty()) {
                        NuvioText(
                            text = meta,
                            style = NuvioTypography.KeyShortcutBadge,
                            color = scheme.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(scheme.pillActiveBgTop)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        NuvioText(
                            text = l10n("Playing", "Tocando"),
                            style = NuvioTypography.KeyShortcutBadge,
                            color = scheme.pillActiveFg,
                        )
                    }
                } else if (track.upcoming) {
                    QueueIconAction(
                        icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                        description = l10n("Play next", "Tocar a seguir"),
                        onClick = { onQueueAction("next", track.videoId) },
                    )
                    QueueIconAction(
                        icon = Icons.Rounded.Close,
                        description = l10n("Remove from queue", "Remover da fila"),
                        onClick = { onQueueAction("remove", track.videoId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.contentCardSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = description,
                tint = scheme.textPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun PlayerSpeedTimerSheetBody(
    speedPercent: Int,
    sleepMinutes: Int,
    onClose: () -> Unit,
    onControl: (action: String, value: Int?) -> Unit,
    onKey: (String) -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val prefs = remember(context) { com.nuvio.remote.data.AppPrefs(context) }
    var fadeOutEnabled by remember { mutableStateOf(prefs.sleepFadeOutEnabled) }
    val speeds = listOf(
        l10n("0.75x", "0,75x") to 75,
        l10n("1.0x", "1,0x") to 100,
        l10n("1.25x", "1,25x") to 125,
        l10n("1.5x", "1,5x") to 150,
        l10n("2.0x", "2,0x") to 200,
    )
    val timers = listOf(
        l10n("Off", "Desligado") to 0,
        "15 min" to 15,
        "30 min" to 30,
        "60 min" to 60,
    )

    SheetHeader(
        title = l10n("SPEED & SLEEP TIMER", "VELOCIDADE E SLEEP TIMER"),
        subtitle = l10n("Nuvio player session controls", "Controles da sessão do player Nuvio"),
        onClose = onClose,
    )

    NuvioText(
        text = l10n("PLAYBACK SPEED", "VELOCIDADE DE REPRODUÇÃO"),
        style = NuvioTypography.SectionMicroTitle,
        color = scheme.textMuted,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        speeds.forEach { (label, value) ->
            FilterChipCell(
                label = label,
                selected = speedPercent == value,
                onClick = { onControl("SPEED", value) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    NuvioText(
        text = l10n("SLEEP TIMER (AUTO PAUSE)", "SLEEP TIMER (PAUSA AUTOMÁTICA)"),
        style = NuvioTypography.SectionMicroTitle,
        color = scheme.textMuted,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        timers.forEach { (label, mins) ->
            FilterChipCell(
                label = label,
                selected = (mins == 0 && sleepMinutes == 0) || (mins > 0 && sleepMinutes in (mins - 5)..mins),
                onClick = { onControl("SLEEP", mins) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (sleepMinutes > 0) {
        NuvioText(
            text = l10n(
                "Auto pause scheduled • $sleepMinutes min remaining",
                "Pausa automática programada • Restam $sleepMinutes min",
            ),
            style = NuvioTypography.KeyShortcutBadge,
            color = accentForScreen(NuvioScreenState.PLAYER).primary,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NuvioText(
                text = l10n("Gradual volume fade-out (60s)", "Reduzir volume gradualmente (60s)"),
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.textPrimary,
            )
            NuvioText(
                text = l10n("Smooth fade during final minute", "Fade-out suave no último minuto"),
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.textSecondary,
            )
        }
        com.nuvio.remote.ui.components.NuvioGlassSwitch(
            checked = fadeOutEnabled,
            onCheckedChange = { next ->
                fadeOutEnabled = next
                prefs.sleepFadeOutEnabled = next
            },
        )
    }

    NuvioText(
        text = l10n("SCREEN & SERIES SHORTCUTS", "ATALHOS DE TELA E SÉRIE"),
        style = NuvioTypography.SectionMicroTitle,
        color = scheme.textMuted,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetShortcutButton(
            label = l10n("Episodes", "Episódios"),
            icon = Icons.Rounded.LiveTv,
            onClick = { onKey("E") },
            modifier = Modifier.weight(1f),
        )
        SheetShortcutButton(
            label = l10n("Aspect Ratio", "Proporção"),
            icon = Icons.Rounded.AspectRatio,
            onClick = { onKey("C") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FilterChipCell(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = LocalNuvioColors.current
    val bg = if (selected) {
        Modifier.background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
    } else {
        Modifier
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(12.dp))
    }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(bg)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            NuvioText(
                text = label,
                style = NuvioTypography.KeyShortcutBadge,
                color = if (selected) scheme.pillActiveFg else scheme.textPrimary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SheetShortcutButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = label,
                tint = scheme.textPrimary,
                modifier = Modifier.size(16.dp),
            )
            NuvioText(text = label, style = NuvioTypography.KeyLabelCompact, color = scheme.textPrimary)
        }
    }
}

@Composable
private fun SmallTagBadge(text: String, accent: Boolean) {
    val scheme = LocalNuvioColors.current
    val playerAccent = accentForScreen(NuvioScreenState.PLAYER)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (accent) playerAccent.surfaceTint else scheme.contentCardSurface)
            .border(
                1.dp,
                if (accent) playerAccent.primary.copy(alpha = 0.45f) else scheme.contentBorder,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        NuvioText(
            text = text,
            style = NuvioTypography.KeyShortcutBadge,
            color = if (accent) playerAccent.primary else scheme.textSecondary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun EmptySheetBox(text: String) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        NuvioText(text = text, style = NuvioTypography.KeyShortcutBadge, color = scheme.textSecondary)
    }
}
