package com.nuvio.remote.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.NuvioNowPlaying
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.connection.computeFilmEndsAtEpochMs
import com.nuvio.remote.connection.formatMediaTime
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioGlassSlider
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.LocalAppLanguage
import com.nuvio.remote.ui.i18n.ResolvedLanguage
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.accentForScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.Locale

private val posterBitmapCache = LruCache<String, Bitmap>(12)

enum class PlayerSheetType {
    NONE,
    SUBTITLES,
    AUDIO,
    SOURCES,
    QUEUE,
    SPEED_TIMER,
    LYRICS,
}

/**
 * Player state body inside DynamicContextDock (DESIGN-SPEC.md section 5.2 B.1 & B.2).
 * Adapts automatically between Movies/Shows and Music (`kind == "music"`),
 * with a clean fallback when the native Nuvio bridge is not yet linked.
 */
@Composable
fun PlayerNowPlayingDock(
    nowPlaying: NuvioNowPlaying?,
    bridgeConnected: Boolean,
    expanded: Boolean,
    onKey: (String) -> Unit,
    onSeekTo: (Long) -> Unit,
    onSetVolume: (Int) -> Unit,
    onControl: (action: String, value: Int?) -> Unit,
    onOpenSheet: (PlayerSheetType) -> Unit,
) {
    val isMusic = nowPlaying?.isMusic == true
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current

    var clockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nowPlaying?.isPlaying, nowPlaying?.receivedAtMs) {
        while (nowPlaying != null) {
            clockTick = System.currentTimeMillis()
            delay(if (nowPlaying.isPlaying) 1_000L else 15_000L)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (nowPlaying != null) {
            NowPlayingMiniCard(
                nowPlaying = nowPlaying,
                nowMs = clockTick,
                onSeekTo = onSeekTo,
            )
        }

        PlayerTransportRow(
            isMusic = isMusic,
            isPlaying = nowPlaying?.isPlaying == true,
            bridgeConnected = bridgeConnected,
            currentPositionMs = nowPlaying?.positionAt(clockTick) ?: 0L,
            durationMs = nowPlaying?.durationMs ?: 0L,
            onKey = onKey,
            onSeekTo = onSeekTo,
            onPlayPauseHaptic = { if (hapticsEnabled) Haptics.playPause(context) },
        )

        if (expanded) {
            PlayerVolumeRow(
                volume = nowPlaying?.volume,
                volumeMax = nowPlaying?.volumeMax ?: 100,
                onMute = { onKey("M") },
                onSetVolume = onSetVolume,
                onVolStep = { delta -> onKey(if (delta > 0) "Up" else "Down") },
            )

            if (isMusic) {
                MusicBottomActionsRow(
                    nowPlaying = nowPlaying,
                    onControl = onControl,
                    onOpenQueue = { onOpenSheet(PlayerSheetType.QUEUE) },
                )
            } else {
                FilmBottomActionsRow(
                    bridgeConnected = bridgeConnected,
                    speedPercent = nowPlaying?.speedPercent ?: 100,
                    sleepMinutes = nowPlaying?.sleepMinutes ?: 0,
                    onKey = onKey,
                    onOpenSheet = onOpenSheet,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingMiniCard(nowPlaying: NuvioNowPlaying, nowMs: Long, onSeekTo: (Long) -> Unit) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val lang = LocalAppLanguage.current
    val art = rememberRemoteBitmap(nowPlaying.imageUrl)
    val livePos = nowPlaying.positionAt(nowMs)
    val duration = nowPlaying.durationMs
    var scrubbingFrac by remember(nowPlaying.title, nowPlaying.receivedAtMs) { mutableStateOf<Float?>(null) }

    val displayPos = if (scrubbingFrac != null && duration > 0L) {
        (scrubbingFrac!! * duration).toLong()
    } else {
        livePos
    }
    val sliderFrac = if (duration > 0L) {
        (scrubbingFrac ?: (livePos.toFloat() / duration.toFloat())).coerceIn(0f, 1f)
    } else {
        0f
    }

    val endsAtLabel = remember(nowPlaying.isMusic, livePos, duration, nowPlaying.speedPercent, nowMs, lang) {
        if (nowPlaying.isMusic || duration <= 0L) {
            null
        } else {
            computeFilmEndsAtEpochMs(livePos, duration, nowPlaying.speedPercent, nowMs)?.let { epoch ->
                val formatted = DateFormat.getTimeFormat(context).format(Date(epoch))
                l10n(lang, "Ends at $formatted", "Termina às $formatted")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PosterThumbnail(bitmap = art, isMusic = nowPlaying.isMusic)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioText(
                        text = nowPlaying.title,
                        style = NuvioTypography.KeyLabelPrimary,
                        color = scheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val badgeText = if (nowPlaying.isMusic) "YT Music" else endsAtLabel
                    if (!badgeText.isNullOrBlank()) {
                        val playerAccent = accentForScreen(NuvioScreenState.PLAYER)
                        Box(
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .clip(CircleShape)
                                .background(playerAccent.surfaceTint)
                                .border(1.dp, playerAccent.primary.copy(alpha = 0.35f), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            NuvioText(
                                text = badgeText,
                                style = NuvioTypography.KeyShortcutBadge,
                                color = playerAccent.primary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                if (!nowPlaying.subtitle.isNullOrBlank()) {
                    NuvioText(
                        text = nowPlaying.subtitle,
                        style = NuvioTypography.KeyShortcutBadge,
                        color = scheme.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (duration > 0L) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NuvioText(
                    text = formatMediaTime(displayPos),
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.textSecondary,
                    maxLines = 1,
                )
                NuvioGlassSlider(
                    value = sliderFrac,
                    onValueChange = { frac ->
                        scrubbingFrac = frac
                        onSeekTo((frac * duration).toLong())
                    },
                    modifier = Modifier.weight(1f),
                )
                NuvioText(
                    text = formatMediaTime(duration),
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PosterThumbnail(bitmap: Bitmap?, isMusic: Boolean) {
    val scheme = LocalNuvioColors.current
    val shape = RoundedCornerShape(if (isMusic) 10.dp else 8.dp)
    val sizeMod = if (isMusic) Modifier.size(42.dp) else Modifier.width(34.dp).height(46.dp)

    Box(
        modifier = sizeMod
            .clip(shape)
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            val playerAccent = accentForScreen(NuvioScreenState.PLAYER)
            NuvioIcon(
                imageVector = if (isMusic) Icons.Rounded.MusicNote else Icons.Rounded.Movie,
                contentDescription = null,
                tint = playerAccent.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PlayerTransportRow(
    isMusic: Boolean,
    isPlaying: Boolean,
    bridgeConnected: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onKey: (String) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPauseHaptic: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerActionPill(
            label = if (isMusic) l10n("Previous", "Anterior") else "−10s",
            icon = if (isMusic) Icons.Rounded.SkipPrevious else Icons.Rounded.Replay10,
            primary = false,
            onClick = {
                if (isMusic) {
                    onKey("PageUp")
                } else if (bridgeConnected && durationMs > 0L) {
                    onSeekTo((currentPositionMs - 10_000L).coerceAtLeast(0L))
                } else {
                    onKey("Left")
                }
            },
            modifier = Modifier.weight(1f),
        )
        PlayerActionPill(
            label = if (isPlaying) l10n("Pause", "Pausar") else "Play / Pause",
            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            primary = true,
            onClick = {
                onPlayPauseHaptic()
                onKey("Space")
            },
            modifier = Modifier.weight(1.45f),
        )
        PlayerActionPill(
            label = if (isMusic) l10n("Next", "Próxima") else "+10s",
            icon = if (isMusic) Icons.Rounded.SkipNext else Icons.Rounded.Forward10,
            primary = false,
            onClick = {
                if (isMusic) {
                    onKey("PageDown")
                } else if (bridgeConnected && durationMs > 0L) {
                    onSeekTo((currentPositionMs + 10_000L).coerceAtMost(durationMs))
                } else {
                    onKey("Right")
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlayerVolumeRow(
    volume: Int?,
    volumeMax: Int,
    onMute: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onVolStep: (Int) -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val maxVal = volumeMax.coerceAtLeast(1)
    var localVol by remember(volume) { mutableFloatStateOf((volume ?: 70).toFloat() / maxVal.toFloat()) }
    val pct = (localVol * maxVal).toInt().coerceIn(0, maxVal)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (hapticsEnabled) Haptics.toggle(context)
                    onMute()
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
                    imageVector = if (pct == 0) {
                        Icons.AutoMirrored.Rounded.VolumeOff
                    } else {
                        Icons.AutoMirrored.Rounded.VolumeUp
                    },
                    contentDescription = l10n("Mute audio", "Silenciar áudio"),
                    tint = scheme.textPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        NuvioGlassSlider(
            value = localVol,
            onValueChange = { next ->
                val prevPct = (localVol * maxVal).toInt()
                localVol = next
                val nextPct = (next * maxVal).toInt().coerceIn(0, maxVal)
                if (volume != null) {
                    onSetVolume(nextPct)
                } else if (nextPct != prevPct) {
                    onVolStep(if (nextPct > prevPct) 1 else -1)
                }
            },
            modifier = Modifier.weight(1f),
        )
        NuvioText(
            text = "$pct%",
            style = NuvioTypography.KeyShortcutBadge,
            color = scheme.textSecondary,
            maxLines = 1,
            modifier = Modifier.width(34.dp),
        )
    }
}

@Composable
private fun FilmBottomActionsRow(
    bridgeConnected: Boolean,
    speedPercent: Int,
    sleepMinutes: Int,
    onKey: (String) -> Unit,
    onOpenSheet: (PlayerSheetType) -> Unit,
) {
    val lang = LocalAppLanguage.current
    val numLocale = if (lang == ResolvedLanguage.PT_BR) Locale("pt", "BR") else Locale.US
    val speedText = if (sleepMinutes > 0) {
        "${sleepMinutes}m"
    } else if (speedPercent == 100) {
        "1x • Timer"
    } else {
        String.format(numLocale, "%.2gx", speedPercent / 100.0)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PlayerGridCell(
            label = l10n("Subtitles", "Legenda"),
            icon = Icons.Rounded.Subtitles,
            active = false,
            onClick = {
                if (bridgeConnected) onOpenSheet(PlayerSheetType.SUBTITLES) else onKey("S")
            },
            onLongClick = { onOpenSheet(PlayerSheetType.SUBTITLES) },
            modifier = Modifier.weight(1f),
        )
        PlayerGridCell(
            label = l10n("Audio", "Áudio"),
            icon = Icons.Rounded.Audiotrack,
            active = false,
            onClick = {
                if (bridgeConnected) onOpenSheet(PlayerSheetType.AUDIO) else onKey("A")
            },
            onLongClick = { onOpenSheet(PlayerSheetType.AUDIO) },
            modifier = Modifier.weight(1f),
        )
        PlayerGridCell(
            label = l10n("Sources", "Fontes"),
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            active = false,
            onClick = {
                if (bridgeConnected) onOpenSheet(PlayerSheetType.SOURCES) else onKey("O")
            },
            modifier = Modifier.weight(1f),
        )
        PlayerGridCell(
            label = speedText,
            icon = Icons.Rounded.Timer,
            active = speedPercent != 100 || sleepMinutes > 0,
            onClick = { onOpenSheet(PlayerSheetType.SPEED_TIMER) },
            modifier = Modifier.weight(1f),
        )
        PlayerGridCell(
            label = l10n("Aspect", "Aspecto"),
            icon = Icons.Rounded.AspectRatio,
            active = false,
            onClick = { onKey("C") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MusicBottomActionsRow(
    nowPlaying: NuvioNowPlaying,
    onControl: (action: String, value: Int?) -> Unit,
    onOpenQueue: () -> Unit,
) {
    val shuffleOn = nowPlaying.shuffle == true
    val repeatMode = nowPlaying.repeat ?: 0
    val liked = nowPlaying.liked == true

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PlayerGridCell(
            label = l10n("Shuffle", "Aleatório"),
            icon = Icons.Rounded.Shuffle,
            active = shuffleOn,
            onClick = { onControl("SHUFFLE", if (shuffleOn) 0 else 1) },
            modifier = Modifier.weight(1f),
        )
        PlayerGridCell(
            label = when (repeatMode) {
                1 -> l10n("All", "Todos")
                2 -> "1x"
                else -> l10n("Repeat", "Repetir")
            },
            icon = if (repeatMode == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            active = repeatMode > 0,
            onClick = { onControl("REPEAT", (repeatMode + 1) % 3) },
            modifier = Modifier.weight(1f),
        )
        PlayerGridCell(
            label = l10n("Like", "Curtir"),
            icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            active = liked,
            onClick = { onControl("LIKE", null) },
            modifier = Modifier.weight(1f),
        )
        PlayerGridCell(
            label = l10n("Radio", "Rádio"),
            icon = Icons.Rounded.Radio,
            active = false,
            onClick = { onControl("RADIO", null) },
            modifier = Modifier.weight(1f),
        )
        PlayerGridCell(
            label = l10n("Queue", "Fila"),
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            active = false,
            onClick = onOpenQueue,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlayerActionPill(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerGridCell(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val playerAccent = accentForScreen(NuvioScreenState.PLAYER)
    val borderColor = if (active) playerAccent.primary.copy(alpha = 0.7f) else scheme.contentBorder
    val bgColor = if (active) playerAccent.surfaceTint else scheme.contentCellSurface
    val fgColor = if (active) playerAccent.primary else scheme.textPrimary

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (hapticsEnabled) Haptics.action(context)
                    onClick()
                },
                onLongClick = onLongClick?.let { longAction ->
                    {
                        if (hapticsEnabled) Haptics.toggle(context)
                        longAction()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(13.dp))
                .padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            NuvioIcon(imageVector = icon, contentDescription = label, tint = fgColor, modifier = Modifier.size(16.dp))
            NuvioText(
                text = label,
                style = NuvioTypography.KeyShortcutBadge,
                color = if (active) fgColor else scheme.textSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberRemoteBitmap(url: String?): Bitmap? {
    var bitmap by remember(url) { mutableStateOf(url?.let { posterBitmapCache.get(it) }) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        posterBitmapCache.get(url)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                }
                try {
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
        if (fetched != null) {
            posterBitmapCache.put(url, fetched)
            bitmap = fetched
        }
    }
    return bitmap
}
