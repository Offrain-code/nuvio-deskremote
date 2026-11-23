package com.nuvio.remote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BlurCircular
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.ClearContentShadow
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class RadialPcSlice(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

internal data class RadialDockCallbacks(
    val onRightClick: () -> Unit,
    val onToggleKeyboard: () -> Unit,
    val onAltTab: () -> Unit,
    val onCopyFromPc: () -> Unit,
    val onPasteToPc: () -> Unit,
    val onSendEscape: () -> Unit,
    val onToggleAudio: () -> Unit,
    val onToggleWindowFullscreen: () -> Unit,
)

/**
 * Draggable radial orb exclusive to PC Mode with 8 >= 48dp slices in Liquid Glass Clear.
 */
@Composable
internal fun ScreenShareRadialDock(
    isBackdropLight: Boolean,
    screenAudioEnabled: Boolean,
    callbacks: RadialDockCallbacks,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val reducedMotion = LocalReducedMotion.current
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val touchBoxPx = with(density) { 48.dp.toPx() }
        val edgeMarginPx = with(density) { 10.dp.toPx() }
        val maxX = (with(density) { maxWidth.toPx() } - touchBoxPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        val maxY = (with(density) { maxHeight.toPx() } - touchBoxPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)

        var orbX by remember(maxX) { mutableFloatStateOf(maxX) }
        var orbY by remember(maxY) {
            val centeredY = (with(density) { maxHeight.toPx() } - touchBoxPx) * 0.50f
            mutableFloatStateOf(centeredY.coerceIn(edgeMarginPx, maxY))
        }

        val clampedX = orbX.coerceIn(edgeMarginPx, maxX)
        val clampedY = orbY.coerceIn(edgeMarginPx, maxY)

        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.24f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = false },
                contentAlignment = Alignment.Center,
            ) {
                RadialWheelOverlay(
                    isBackdropLight = isBackdropLight,
                    screenAudioEnabled = screenAudioEnabled,
                    callbacks = callbacks,
                    onDismiss = { expanded = false },
                )
            }
        }

        AnimatedVisibility(
            visible = !expanded,
            enter = NuvioMotion.morphEnter(reducedMotion),
            exit = NuvioMotion.morphExit(reducedMotion),
            modifier = Modifier.offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) },
        ) {
            OrbAnchorButton(
                isBackdropLight = isBackdropLight,
                onClick = {
                    if (hapticsEnabled) Haptics.tab(context)
                    expanded = true
                },
                onDrag = { dx, dy ->
                    orbX = (orbX + dx).coerceIn(edgeMarginPx, maxX)
                    orbY = (orbY + dy).coerceIn(edgeMarginPx, maxY)
                },
            )
        }
    }
}

@Composable
private fun OrbAnchorButton(isBackdropLight: Boolean, onClick: () -> Unit, onDrag: (Float, Float) -> Unit) {
    val scheme = LocalNuvioColors.current
    val openMenuDesc = l10n("Open PC quick menu", "Abrir menu rápido do PC")

    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                role = Role.Button
                contentDescription = openMenuDesc
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .nuvioGlass(
                    shape = CircleShape,
                    variant = GlassVariant.CLEAR,
                    isBackdropLight = isBackdropLight,
                    captureStageBackdrop = false,
                    resting = true,
                ),
            contentAlignment = Alignment.Center,
        ) {
            NuvioIcon(
                imageVector = Icons.Rounded.BlurCircular,
                contentDescription = l10n("PC shortcuts", "Atalhos do PC"),
                tint = scheme.clearOverlayText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RadialWheelOverlay(
    isBackdropLight: Boolean,
    screenAudioEnabled: Boolean,
    callbacks: RadialDockCallbacks,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = NuvioMotion.smoothSpring(reducedMotion),
        label = "radialScale",
    )

    val slices = listOf(
        RadialPcSlice(l10n("Right", "Dir."), Icons.Rounded.Mouse) { callbacks.onRightClick() },
        RadialPcSlice(l10n("Paste", "Colar"), Icons.Rounded.ContentPaste) { callbacks.onPasteToPc() },
        RadialPcSlice(l10n("Copy", "Copiar"), Icons.Rounded.ContentCopy) { callbacks.onCopyFromPc() },
        RadialPcSlice(l10n("Keys", "Teclado"), Icons.Rounded.Keyboard) { callbacks.onToggleKeyboard() },
        RadialPcSlice("Alt+Tab", Icons.Rounded.SwapHoriz) { callbacks.onAltTab() },
        RadialPcSlice("ESC", Icons.Rounded.Close) { callbacks.onSendEscape() },
        RadialPcSlice(
            if (screenAudioEnabled) l10n("Audio", "Som PC") else l10n("Muted", "Mudo"),
            if (screenAudioEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
        ) { callbacks.onToggleAudio() },
        RadialPcSlice("F11", Icons.Rounded.Fullscreen) { callbacks.onToggleWindowFullscreen() },
    )

    val density = LocalDensity.current
    val radiusPx = with(density) { 86.dp.toPx() }

    Box(
        modifier = Modifier
            .size(252.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .nuvioGlass(
                shape = CircleShape,
                variant = GlassVariant.CLEAR,
                isBackdropLight = isBackdropLight,
                captureStageBackdrop = false,
                resting = false,
            ),
        contentAlignment = Alignment.Center,
    ) {
        slices.forEachIndexed { index, slice ->
            val angleRad = (index * (2.0 * PI / slices.size)) - (PI / 2.0)
            val dx = (cos(angleRad) * radiusPx).roundToInt()
            val dy = (sin(angleRad) * radiusPx).roundToInt()

            Box(
                modifier = Modifier
                    .offset { IntOffset(dx, dy) }
                    .width(58.dp)
                    .height(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (hapticsEnabled) Haptics.action(context)
                        slice.onClick()
                        onDismiss()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color.Black.copy(alpha = 0.30f))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(13.dp))
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                ) {
                    NuvioIcon(
                        imageVector = slice.icon,
                        contentDescription = slice.label,
                        tint = scheme.clearOverlayText,
                        modifier = Modifier.size(15.dp),
                    )
                    NuvioText(
                        text = slice.label,
                        style = NuvioTypography.DockTabLabel,
                        color = scheme.clearOverlayText,
                        shadow = ClearContentShadow,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(scheme.clearPillActiveBgTop, scheme.clearPillActiveBgBottom),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = l10n("Close quick menu", "Fechar menu rápido"),
                    tint = scheme.clearPillActiveFg,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
