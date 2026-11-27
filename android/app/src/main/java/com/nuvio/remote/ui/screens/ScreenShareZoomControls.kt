package com.nuvio.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.ClearContentShadow
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass
import java.util.Locale

/**
 * Synchronizes viewport pan offset to follow the remote cursor while zoomed in.
 */
@Composable
internal fun CursorFollowZoomEffect(
    remoteCursor: Triple<Float, Float, Boolean>,
    zoom: Float,
    followCursor: Boolean,
    videoSize: IntSize,
    currentPan: Offset,
    onPanChange: (Offset) -> Unit,
) {
    LaunchedEffect(remoteCursor, zoom, followCursor, videoSize) {
        if (!followCursor) return@LaunchedEffect
        val next = computeCursorFollowPan(
            currentPan = currentPan,
            cursorNormX = remoteCursor.first,
            cursorNormY = remoteCursor.second,
            size = videoSize,
            zoom = zoom,
        )
        if (zoom > 1.01f && next != currentPan) {
            onPanChange(next)
        }
    }
}

/**
 * Floating Clear-glass zoom overlay controls for Screen Share (DESIGN-SPEC §5.15.3):
 * displays current magnification + Reset and the Follow Cursor toggle while zoomed in.
 */
@Composable
internal fun ScreenShareZoomOverlayRow(
    zoom: Float,
    followCursor: Boolean,
    isBackdropLight: Boolean,
    onReset: () -> Unit,
    onToggleFollowCursor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (zoom <= 1.01f) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZoomResetPill(
            zoom = zoom,
            isBackdropLight = isBackdropLight,
            onReset = onReset,
        )
        FollowCursorZoomPill(
            enabled = followCursor,
            isBackdropLight = isBackdropLight,
            onToggle = onToggleFollowCursor,
        )
    }
}

@Composable
private fun ZoomResetPill(zoom: Float, isBackdropLight: Boolean, onReset: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val zoomLabel = "${String.format(Locale.US, "%.1fx", zoom)} • ${l10n("Reset", "Resetar")}"
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onReset),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .nuvioGlass(
                    shape = CircleShape,
                    variant = GlassVariant.CLEAR,
                    isBackdropLight = isBackdropLight,
                    captureStageBackdrop = false,
                    resting = false,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            NuvioText(
                text = zoomLabel,
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.clearOverlayText,
                shadow = ClearContentShadow,
            )
        }
    }
}

@Composable
private fun FollowCursorZoomPill(enabled: Boolean, isBackdropLight: Boolean, onToggle: () -> Unit) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .nuvioGlass(
                    shape = CircleShape,
                    variant = GlassVariant.CLEAR,
                    isBackdropLight = isBackdropLight,
                    captureStageBackdrop = false,
                    resting = false,
                )
                .then(
                    if (enabled) {
                        Modifier.border(1.dp, scheme.statusOnline.copy(alpha = 0.7f), CircleShape)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (enabled) scheme.statusOnline else scheme.textMuted),
            )
            NuvioText(
                text = l10n("Follow cursor", "Seguir cursor"),
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.clearOverlayText,
                shadow = ClearContentShadow,
            )
        }
    }
}

internal fun computeCursorFollowPan(
    currentPan: Offset,
    cursorNormX: Float,
    cursorNormY: Float,
    size: IntSize,
    zoom: Float,
): Offset {
    if (zoom <= 1.01f || size.width <= 1 || size.height <= 1) return Offset.Zero
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val cx = w * 0.5f
    val cy = h * 0.5f
    val screenX = cx + (cursorNormX.coerceIn(0f, 1f) * w - cx) * zoom + currentPan.x
    val screenY = cy + (cursorNormY.coerceIn(0f, 1f) * h - cy) * zoom + currentPan.y
    val marginX = w * 0.22f
    val marginY = h * 0.22f
    val dx = when {
        screenX < marginX -> marginX - screenX
        screenX > w - marginX -> (w - marginX) - screenX
        else -> 0f
    }
    val dy = when {
        screenY < marginY -> marginY - screenY
        screenY > h - marginY -> (h - marginY) - screenY
        else -> 0f
    }
    if (dx == 0f && dy == 0f) return currentPan
    return clampZoomPan(Offset(currentPan.x + dx, currentPan.y + dy), size, zoom)
}

private fun clampZoomPan(pan: Offset, size: IntSize, zoom: Float): Offset {
    if (zoom <= 1.01f) return Offset.Zero
    val maxX = ((size.width * zoom) - size.width).coerceAtLeast(0f) * 0.5f
    val maxY = ((size.height * zoom) - size.height).coerceAtLeast(0f) * 0.5f
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}
