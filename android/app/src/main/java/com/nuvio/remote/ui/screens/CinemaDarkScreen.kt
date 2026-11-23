package com.nuvio.remote.ui.screens

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.NuvioNowPlaying
import com.nuvio.remote.connection.computeFilmEndsAtEpochMs
import com.nuvio.remote.connection.formatMediaTime
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.LocalAppLanguage
import com.nuvio.remote.ui.i18n.ResolvedLanguage
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.NuvioTypography
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.abs

private const val GESTURE_THRESHOLD_PX = 90f

/**
 * Pure OLED Cinema Dark Mode (#000000, zero glass) for dark rooms (DESIGN-SPEC.md section 5.2.2).
 * Tap = Play/Pause; horizontal drag = seek/track; vertical drag = volume; long-press = exit.
 */
@Composable
fun CinemaDarkScreen(
    nowPlaying: NuvioNowPlaying?,
    onKey: (String) -> Unit,
    onSeekTo: (Long) -> Unit,
    onSetVolume: (Int) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val lang = LocalAppLanguage.current
    var brightnessLevel by remember { mutableIntStateOf(1) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var clockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    BackHandler(onBack = onExit)

    LaunchedEffect(nowPlaying?.isPlaying, nowPlaying?.receivedAtMs) {
        while (nowPlaying != null) {
            clockTick = System.currentTimeMillis()
            delay(if (nowPlaying.isPlaying) 1_000L else 15_000L)
        }
    }

    LaunchedEffect(gestureHint) {
        if (gestureHint != null) {
            delay(1_400L)
            gestureHint = null
        }
    }

    val fgAlpha = cinemaBrightnessAlpha(brightnessLevel)
    val fg = Color.White.copy(alpha = fgAlpha)
    val fgMuted = Color.White.copy(alpha = (fgAlpha * 0.65f).coerceIn(0.16f, 0.7f))
    val border = Color.White.copy(alpha = (fgAlpha * 0.25f).coerceIn(0.08f, 0.28f))

    val livePos = nowPlaying?.positionAt(clockTick) ?: 0L
    val duration = nowPlaying?.durationMs ?: 0L
    val progressFrac = if (duration > 0L) (livePos.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val endsAt = remember(nowPlaying, livePos, duration, clockTick, lang) {
        formatCinemaEndsAt(context, nowPlaying, livePos, duration, clockTick, lang)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CinemaTopControls(
            brightnessLevel = brightnessLevel,
            fg = fg,
            border = border,
            onSelectBrightness = { brightnessLevel = it },
            onExit = onExit,
        )

        CinemaPlaybackHeader(
            nowPlaying = nowPlaying,
            livePos = livePos,
            duration = duration,
            progressFrac = progressFrac,
            endsAt = endsAt,
            fg = fg,
            fgMuted = fgMuted,
            border = border,
        )

        CinemaGesturePad(
            nowPlaying = nowPlaying,
            livePos = livePos,
            duration = duration,
            fg = fg,
            fgMuted = fgMuted,
            border = border,
            gestureHint = gestureHint,
            onTapPlayPause = {
                if (hapticsEnabled) Haptics.playPause(context)
                onKey("Space")
                gestureHint = "Play / Pause"
            },
            onLongPressExit = {
                if (hapticsEnabled) Haptics.toggle(context)
                onExit()
            },
            onHorizontalStep = { dir ->
                if (hapticsEnabled) Haptics.action(context)
                gestureHint = applyCinemaHorizontalGesture(nowPlaying, livePos, duration, dir, lang, onKey, onSeekTo)
            },
            onVerticalStep = { dir ->
                if (hapticsEnabled) Haptics.volume(context)
                gestureHint = applyCinemaVerticalGesture(nowPlaying, dir, onKey, onSetVolume)
            },
            modifier = Modifier.weight(1f),
        )

        NuvioText(
            text = l10n(
                "Pure OLED #000000 • Zero background glow for dark rooms",
                "Tela OLED #000000 • Zero emissão de fundo para sala escura",
            ),
            style = NuvioTypography.KeyShortcutBadge,
            color = fgMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun cinemaBrightnessAlpha(level: Int): Float = when (level) {
    0 -> 0.30f
    2 -> 0.85f
    else -> 0.56f
}

private fun formatCinemaEndsAt(
    context: android.content.Context,
    nowPlaying: NuvioNowPlaying?,
    livePos: Long,
    duration: Long,
    clockTick: Long,
    lang: ResolvedLanguage,
): String? {
    if (nowPlaying == null || nowPlaying.isMusic || duration <= 0L) return null
    val epoch = computeFilmEndsAtEpochMs(livePos, duration, nowPlaying.speedPercent, clockTick) ?: return null
    val formatted = DateFormat.getTimeFormat(context).format(Date(epoch))
    return l10n(lang, "Ends at $formatted", "Termina às $formatted")
}

private fun applyCinemaHorizontalGesture(
    nowPlaying: NuvioNowPlaying?,
    livePos: Long,
    duration: Long,
    dir: Int,
    lang: ResolvedLanguage,
    onKey: (String) -> Unit,
    onSeekTo: (Long) -> Unit,
): String = when {
    nowPlaying?.isMusic == true -> {
        onKey(if (dir > 0) "PageDown" else "PageUp")
        if (dir > 0) {
            l10n(lang, "Next track", "Próxima faixa")
        } else {
            l10n(lang, "Previous track", "Faixa anterior")
        }
    }
    duration > 0L -> {
        onSeekTo((livePos + dir * 10_000L).coerceIn(0L, duration))
        if (dir > 0) "+10s" else "−10s"
    }
    else -> {
        onKey(if (dir > 0) "Right" else "Left")
        if (dir > 0) l10n(lang, "Forward", "Avançar") else l10n(lang, "Back", "Voltar")
    }
}

private fun applyCinemaVerticalGesture(
    nowPlaying: NuvioNowPlaying?,
    dir: Int,
    onKey: (String) -> Unit,
    onSetVolume: (Int) -> Unit,
): String {
    val vol = nowPlaying?.volume
    if (vol == null) {
        onKey(if (dir > 0) "Up" else "Down")
        return if (dir > 0) "Volume +" else "Volume −"
    }
    val next = (vol + dir * 5).coerceIn(0, nowPlaying.volumeMax)
    onSetVolume(next)
    return "Volume $next%"
}

@Composable
private fun CinemaTopControls(
    brightnessLevel: Int,
    fg: Color,
    border: Color,
    onSelectBrightness: (Int) -> Unit,
    onExit: () -> Unit,
) {
    val levels = listOf(
        l10n("Low", "Baixo"),
        l10n("Medium", "Médio"),
        l10n("High", "Alto"),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            levels.forEachIndexed { idx, label ->
                val selected = idx == brightnessLevel
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelectBrightness(idx) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(CircleShape)
                            .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Black)
                            .border(1.dp, if (selected) fg else border, CircleShape)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioText(text = label, style = NuvioTypography.KeyShortcutBadge, color = fg)
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onExit,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(CircleShape)
                    .border(1.dp, border, CircleShape)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = l10n("Exit Cinema Mode", "Sair do Modo Cinema"),
                    tint = fg,
                    modifier = Modifier.size(14.dp),
                )
                NuvioText(
                    text = l10n("Exit (1s)", "Sair (1s)"),
                    style = NuvioTypography.KeyShortcutBadge,
                    color = fg,
                )
            }
        }
    }
}

@Composable
private fun CinemaPlaybackHeader(
    nowPlaying: NuvioNowPlaying?,
    livePos: Long,
    duration: Long,
    progressFrac: Float,
    endsAt: String?,
    fg: Color,
    fgMuted: Color,
    border: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val heading = listOfNotNull(nowPlaying?.title, nowPlaying?.subtitle)
                .joinToString(" • ")
                .ifBlank { l10n("Nuvio Player", "Player Nuvio") }
            NuvioText(
                text = heading,
                style = NuvioTypography.KeyLabelPrimary,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!endsAt.isNullOrBlank()) {
                NuvioText(
                    text = endsAt,
                    style = NuvioTypography.KeyShortcutBadge,
                    color = fgMuted,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NuvioText(
                text = "${l10n("OLED CINEMA MODE", "MODO CINEMA OLED")} • ${formatMediaTime(livePos)}",
                style = NuvioTypography.KeyShortcutBadge,
                color = fgMuted,
            )
            val volSuffix = nowPlaying?.volume?.let { " • Vol $it%" } ?: ""
            NuvioText(
                text = "${formatMediaTime(duration)}$volSuffix",
                style = NuvioTypography.KeyShortcutBadge,
                color = fgMuted,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(border),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressFrac)
                    .fillMaxHeight()
                    .background(fg),
            )
        }
    }
}

@Composable
private fun CinemaGesturePad(
    nowPlaying: NuvioNowPlaying?,
    livePos: Long,
    duration: Long,
    fg: Color,
    fgMuted: Color,
    border: Color,
    gestureHint: String?,
    onTapPlayPause: () -> Unit,
    onLongPressExit: () -> Unit,
    onHorizontalStep: (Int) -> Unit,
    onVerticalStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, border, RoundedCornerShape(22.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTapPlayPause() },
                    onLongPress = { onLongPressExit() },
                )
            }
            .pointerInput(nowPlaying?.isMusic, livePos, duration, nowPlaying?.volume) {
                var accumX = 0f
                var accumY = 0f
                detectDragGestures(
                    onDragStart = {
                        accumX = 0f
                        accumY = 0f
                    },
                    onDragEnd = {
                        accumX = 0f
                        accumY = 0f
                    },
                ) { change, dragAmount ->
                    change.consume()
                    accumX += dragAmount.x
                    accumY += dragAmount.y
                    if (abs(accumX) >= GESTURE_THRESHOLD_PX && abs(accumX) > abs(accumY)) {
                        onHorizontalStep(if (accumX > 0f) 1 else -1)
                        accumX = 0f
                        accumY = 0f
                    } else if (abs(accumY) >= GESTURE_THRESHOLD_PX && abs(accumY) > abs(accumX)) {
                        onVerticalStep(if (accumY < 0f) 1 else -1)
                        accumX = 0f
                        accumY = 0f
                    }
                }
            }
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .border(1.dp, border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.DarkMode,
                    contentDescription = l10n("Cinema Mode", "Modo Cinema"),
                    tint = fg,
                    modifier = Modifier.size(24.dp),
                )
            }
            NuvioText(
                text = gestureHint ?: l10n("Dark-Room Gesture Control", "Controle por Gestos no Escuro"),
                style = NuvioTypography.KeyLabelPrimary,
                color = fg,
                textAlign = TextAlign.Center,
            )
            NuvioText(
                text = l10n(
                    "Single tap: Play / Pause\n" +
                        "Horizontal drag: −10s / +10s or track\n" +
                        "Vertical drag: Player volume\n" +
                        "Hold 1 second: Exit Cinema Mode",
                    "Toque simples: Play / Pause\n" +
                        "Arrastar horizontal: −10s / +10s ou faixa\n" +
                        "Arrastar vertical: Volume do player\n" +
                        "Segurar por 1 segundo: Sair do Modo Cinema",
                ),
                style = NuvioTypography.KeyShortcutBadge,
                color = fgMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
