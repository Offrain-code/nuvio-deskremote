package com.nuvio.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.NuvioLyricLine
import com.nuvio.remote.connection.NuvioLyricsSheet
import com.nuvio.remote.connection.NuvioNowPlaying
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.connection.formatMediaTime
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.accentForScreen
import kotlinx.coroutines.delay

/**
 * Synced Lyrics floating sheet body (DESIGN-SPEC.md section 5.2.1 item 6).
 * Opaque inner stage with live highlight of the active lyric line and automatic scrolling.
 */
@Composable
fun PlayerLyricsSheetBody(
    lyrics: NuvioLyricsSheet?,
    nowPlaying: NuvioNowPlaying?,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onKey: (String) -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val playerAccent = accentForScreen(NuvioScreenState.PLAYER)
    var syncedMode by remember(lyrics?.kind) { mutableStateOf(lyrics?.isSynced != false) }
    var clockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(nowPlaying?.isPlaying, nowPlaying?.receivedAtMs) {
        while (nowPlaying != null) {
            clockTick = System.currentTimeMillis()
            delay(if (nowPlaying.isPlaying) 350L else 2_000L)
        }
    }

    val livePosMs = nowPlaying?.positionAt(clockTick) ?: 0L
    val activeIdx = remember(lyrics, livePosMs, syncedMode) {
        if (syncedMode && lyrics != null) lyrics.activeLineIndex(livePosMs) else -1
    }

    LyricsSheetHeader(onClose = onClose)

    LyricsModeSwitcher(
        syncedMode = syncedMode,
        canSync = lyrics?.isSynced == true,
        onSelectSynced = { syncedMode = it },
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioText(
                text = lyrics?.title?.takeIf { it.isNotBlank() }
                    ?: nowPlaying?.title?.takeIf { it.isNotBlank() }
                    ?: l10n("Now playing", "Música em reprodução"),
                style = NuvioTypography.KeyLabelCompact.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(CircleShape)
                    .background(playerAccent.surfaceTint)
                    .border(1.dp, playerAccent.primary.copy(alpha = 0.4f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                NuvioText(
                    text = if (syncedMode && lyrics?.isSynced == true) {
                        l10n("Synced", "Sincronizada")
                    } else {
                        l10n("Plain text", "Texto simples")
                    },
                    style = NuvioTypography.KeyShortcutBadge,
                    color = playerAccent.primary,
                    maxLines = 1,
                )
            }
        }

        LyricsLinesList(
            lyrics = lyrics,
            syncedMode = syncedMode,
            activeIdx = activeIdx,
            onSeekTo = onSeekTo,
            onRefresh = onRefresh,
        )
    }

    LyricsTransportFooter(
        isPlaying = nowPlaying?.isPlaying == true,
        onPrev = { onKey("PageUp") },
        onPlayPause = { onKey("Space") },
        onNext = { onKey("PageDown") },
    )
}

@Composable
private fun LyricsSheetHeader(onClose: () -> Unit) {
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
                text = l10n("SONG LYRICS", "LETRAS DA MÚSICA (LYRICS)"),
                style = NuvioTypography.SectionMicroTitle,
                color = accentForScreen(NuvioScreenState.PLAYER).primary,
                maxLines = 1,
            )
            NuvioText(
                text = l10n("Opaque reading stage • Auto-scroll", "Palco interno opaco • Rolagem automática"),
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
                    contentDescription = l10n("Close lyrics", "Fechar letras"),
                    tint = scheme.textPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun LyricsModeSwitcher(syncedMode: Boolean, canSync: Boolean, onSelectSynced: (Boolean) -> Unit) {
    val scheme = LocalNuvioColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LyricsModePill(
            label = l10n("Synced • Live", "Sincronizada • Tempo real"),
            selected = syncedMode && canSync,
            enabled = canSync,
            onClick = { onSelectSynced(true) },
            modifier = Modifier.weight(1.25f),
        )
        LyricsModePill(
            label = l10n("Plain text", "Texto simples"),
            selected = !syncedMode || !canSync,
            enabled = true,
            onClick = { onSelectSynced(false) },
            modifier = Modifier.weight(1f),
        )
    }
    if (!canSync) {
        NuvioText(
            text = l10n(
                "This track has plain-text lyrics (no timestamps).",
                "Esta faixa possui letra em texto simples (sem marcação de tempo).",
            ),
            style = NuvioTypography.KeyShortcutBadge,
            color = scheme.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LyricsModePill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val bg = if (selected) {
        Modifier.background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
    } else {
        Modifier
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, CircleShape)
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(CircleShape)
                .then(bg)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            NuvioText(
                text = label,
                style = NuvioTypography.KeyShortcutBadge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = when {
                    selected -> scheme.pillActiveFg
                    enabled -> scheme.textPrimary
                    else -> scheme.textMuted
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LyricsLinesList(
    lyrics: NuvioLyricsSheet?,
    syncedMode: Boolean,
    activeIdx: Int,
    onSeekTo: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val listState = rememberLazyListState()

    LaunchedEffect(activeIdx, syncedMode) {
        if (syncedMode && activeIdx >= 0) {
            val target = (activeIdx - 1).coerceAtLeast(0)
            runCatching { listState.animateScrollToItem(target) }
        }
    }

    if (lyrics == null || !lyrics.hasContent) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NuvioText(
                text = if (lyrics == null) {
                    l10n("Loading lyrics from Nuvio…", "Carregando letra do Nuvio…")
                } else {
                    l10n(
                        "Lyrics unavailable for this track in Nuvio.",
                        "Letra indisponível para esta faixa no Nuvio.",
                    )
                },
                style = NuvioTypography.BodyRegular,
                color = scheme.textSecondary,
            )
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(scheme.contentCellSurface)
                        .border(1.dp, scheme.contentBorder, CircleShape)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NuvioText(
                        text = l10n("Reload lyrics", "Recarregar letra"),
                        style = NuvioTypography.KeyLabelCompact,
                        color = scheme.textPrimary,
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(lyrics.lines, key = { idx, line -> "${idx}_${line.timeMs ?: -1L}" }) { idx, line ->
            LyricLineRow(
                line = line,
                isActive = syncedMode && idx == activeIdx,
                showTime = syncedMode && line.timeMs != null,
                onClick = { line.timeMs?.let(onSeekTo) },
            )
        }
    }
}

@Composable
private fun LyricLineRow(line: NuvioLyricLine, isActive: Boolean, showTime: Boolean, onClick: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val playerAccent = accentForScreen(NuvioScreenState.PLAYER)
    val bg = if (isActive) playerAccent.surfaceTint else scheme.contentCellSurface
    val border = if (isActive) playerAccent.primary else scheme.contentBorder

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(if (isActive) 1.5.dp else 1.dp, border, RoundedCornerShape(12.dp))
            .clickable(enabled = line.timeMs != null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isActive) {
                NuvioIcon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = l10n("Current line", "Verso atual"),
                    tint = playerAccent.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            NuvioText(
                text = line.text.ifBlank { "♪" },
                style = NuvioTypography.BodyRegular.copy(
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                ),
                color = if (isActive) scheme.textPrimary else scheme.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showTime && line.timeMs != null) {
            NuvioText(
                text = formatMediaTime(line.timeMs),
                style = NuvioTypography.KeyShortcutBadge,
                color = if (isActive) playerAccent.primary else scheme.textMuted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun LyricsTransportFooter(
    isPlaying: Boolean,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LyricsFooterPill(
            label = l10n("Previous", "Anterior"),
            icon = Icons.Rounded.SkipPrevious,
            primary = false,
            onClick = onPrev,
            modifier = Modifier.weight(1f),
        )
        LyricsFooterPill(
            label = if (isPlaying) l10n("Pause", "Pausar") else "Play / Pause",
            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            primary = true,
            onClick = onPlayPause,
            modifier = Modifier.weight(1.35f),
        )
        LyricsFooterPill(
            label = l10n("Next", "Próxima"),
            icon = Icons.Rounded.SkipNext,
            primary = false,
            onClick = onNext,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LyricsFooterPill(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val fg = if (primary) scheme.pillActiveFg else scheme.textPrimary
    val bg = if (primary) {
        Modifier.background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
    } else {
        Modifier
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
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
                .height(38.dp)
                .clip(RoundedCornerShape(14.dp))
                .then(bg)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        ) {
            NuvioIcon(imageVector = icon, contentDescription = label, tint = fg, modifier = Modifier.size(16.dp))
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = fg,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
