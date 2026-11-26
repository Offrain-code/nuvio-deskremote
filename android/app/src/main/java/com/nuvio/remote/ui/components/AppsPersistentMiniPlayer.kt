package com.nuvio.remote.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.remote.connection.NuvioNowPlaying
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography

/**
 * 100% opaque persistent Mini-Player bar docked above the bottom bar on the Apps tab
 * whenever Nuvio HTPC is actively playing media (DESIGN-SPEC §5.13 Fase 2B — zero glass).
 */
@Composable
fun AppsPersistentMiniPlayer(
    nowPlaying: NuvioNowPlaying,
    onPlayPause: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val posMs = nowPlaying.positionAt(System.currentTimeMillis())
    val progress = if (nowPlaying.durationMs > 0L) {
        (posMs.toFloat() / nowPlaying.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.contentCardSurface)
            .border(1.5.dp, scheme.ctxPlayer.copy(alpha = 0.78f), shape)
            .semantics { role = Role.Button }
            .clickable {
                if (hapticsEnabled) Haptics.tab(context)
                onOpenPlayer()
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 60.dp)
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = rememberRemoteBitmap(nowPlaying.imageUrl, maxDimensionPx = 128)
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = nowPlaying.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(38.dp),
                    )
                } else {
                    NuvioIcon(
                        imageVector = if (nowPlaying.isMusic) Icons.Rounded.MusicNote else Icons.Rounded.PlayCircle,
                        contentDescription = null,
                        tint = scheme.ctxPlayer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                NuvioText(
                    text = if (nowPlaying.isPlaying) {
                        l10n("NOW PLAYING", "TOCANDO AGORA")
                    } else {
                        l10n("PAUSED", "PAUSADO")
                    },
                    style = NuvioTypography.SectionMicroTitle.copy(fontSize = 9.sp),
                    color = scheme.ctxPlayer,
                    maxLines = 1,
                )
                NuvioText(
                    text = nowPlaying.title.ifBlank { l10n("Nuvio Player", "Player Nuvio") },
                    style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val timeLabel = formatMiniPlayerTime(posMs, nowPlaying.durationMs)
                val sub = listOfNotNull(
                    timeLabel.takeIf { it.isNotEmpty() },
                    l10n("Open Player", "Abrir Player"),
                ).joinToString(" • ")
                NuvioText(
                    text = sub,
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val playPauseDesc = if (nowPlaying.isPlaying) l10n("Pause", "Pausar") else l10n("Play", "Reproduzir")
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = playPauseDesc
                    }
                    .clickable {
                        if (hapticsEnabled) Haptics.playPause(context)
                        onPlayPause()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    NuvioIcon(
                        imageVector = if (nowPlaying.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = playPauseDesc,
                        tint = scheme.pillActiveFg,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            NuvioIcon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = l10n("Open Player", "Abrir Player"),
                tint = scheme.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(scheme.contentCellSurface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(scheme.ctxPlayer),
            )
        }
    }
}

private fun formatMiniPlayerTime(posMs: Long, durationMs: Long): String {
    if (durationMs <= 0L) return ""
    return "${formatClockMs(posMs)} / ${formatClockMs(durationMs)}"
}

private fun formatClockMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
