package com.nuvio.remote.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass
import kotlin.math.abs

/**
 * Pure directional step classifier for the Remote Swipe Pad gesture surface.
 */
internal fun resolveSwipeStepDirection(accX: Float, accY: Float, stepPx: Float): String? {
    if (abs(accX) < stepPx && abs(accY) < stepPx) return null
    return if (abs(accX) >= abs(accY)) {
        if (accX > 0f) "right" else "left"
    } else {
        if (accY > 0f) "down" else "up"
    }
}

/**
 * Concentric segmented switcher [ D-Pad | Gestos ] centered between the top Remote satellites
 * (DESIGN-SPEC §5.13 Fase 2A, width = 156.dp, height = 48.dp, 2 targets of 78.dp x 48.dp).
 */
@Composable
fun RemotePadModeToggle(swipeMode: Boolean, onSelectMode: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val options = listOf(
        false to "D-Pad",
        true to l10n("Swipe", "Gestos"),
    )

    Box(
        modifier = modifier
            .width(156.dp)
            .height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .nuvioGlass(shape = RoundedCornerShape(999.dp), variant = GlassVariant.REGULAR),
        )
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (isSwipe, label) ->
                val isSelected = isSwipe == swipeMode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            role = Role.Tab
                            selected = isSelected
                            contentDescription = label
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (hapticsEnabled) Haptics.tab(context)
                            onSelectMode(isSwipe)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 3.dp)
                                .height(30.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom),
                                    ),
                                ),
                        )
                    }
                    NuvioText(
                        text = label,
                        style = NuvioTypography.KeyLabelCompact.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (isSelected) scheme.pillActiveFg else scheme.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Liquid Glass gesture squircle for zero-look Nuvio HTPC navigation (DESIGN-SPEC §5.13 Fase 2A):
 * Swipe = D-pad arrows, Single tap = OK, Long press = Back.
 */
@Composable
fun PrecisionSwipePad(
    diameter: Dp,
    accentColor: Color,
    onDirection: (String) -> Unit,
    onOk: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val stepPx = with(LocalDensity.current) { 30.dp.toPx() }
    val shape = RoundedCornerShape(46.dp)
    val swipeDesc = l10n(
        "Swipe pad: swipe to navigate, tap for OK, hold for Back",
        "Área gestual: deslize para navegar, toque para OK, segure para Voltar",
    )

    Box(
        modifier = modifier
            .size(diameter)
            .nuvioGlass(shape = shape, variant = GlassVariant.REGULAR, elevated = true)
            .semantics { contentDescription = swipeDesc }
            .pointerInput(onOk, onBack, hapticsEnabled) {
                detectTapGestures(
                    onTap = {
                        if (hapticsEnabled) Haptics.ok(context)
                        onOk()
                    },
                    onLongPress = {
                        if (hapticsEnabled) Haptics.toggle(context)
                        onBack()
                    },
                )
            }
            .pointerInput(stepPx, onDirection, hapticsEnabled) {
                var accX = 0f
                var accY = 0f
                detectDragGestures(
                    onDragStart = {
                        accX = 0f
                        accY = 0f
                    },
                    onDragEnd = {
                        accX = 0f
                        accY = 0f
                    },
                    onDragCancel = {
                        accX = 0f
                        accY = 0f
                    },
                ) { change, dragAmount ->
                    change.consume()
                    accX += dragAmount.x
                    accY += dragAmount.y
                    val dir = resolveSwipeStepDirection(accX, accY, stepPx)
                    if (dir != null) {
                        if (hapticsEnabled) Haptics.dpad(context)
                        onDirection(dir)
                        accX = 0f
                        accY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().clip(shape)) {
            val step = 16.dp.toPx()
            val dotRadius = 1.dp.toPx()
            var y = step
            while (y < size.height - step * 0.5f) {
                var x = step
                while (x < size.width - step * 0.5f) {
                    drawCircle(
                        color = scheme.trackpadDots,
                        radius = dotRadius,
                        center = Offset(x, y),
                    )
                    x += step
                }
                y += step
            }
        }

        NuvioIcon(
            imageVector = Icons.Rounded.KeyboardArrowUp,
            contentDescription = null,
            tint = scheme.textMuted,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(16.dp),
        )
        NuvioIcon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = scheme.textMuted,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(16.dp),
        )
        NuvioIcon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = null,
            tint = scheme.textMuted,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(16.dp),
        )
        NuvioIcon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = scheme.textMuted,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(16.dp),
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(24.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NuvioIcon(
                imageVector = Icons.Rounded.TouchApp,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp),
            )
            NuvioText(
                text = "SWIPE PAD",
                style = NuvioTypography.SectionMicroTitle.copy(fontSize = 10.sp),
                color = scheme.textPrimary,
                maxLines = 1,
            )
            NuvioText(
                text = l10n(
                    "Swipe: Arrows\nTap: OK • Hold: Back",
                    "Deslizar: Setas\nToque: OK • Segurar: Voltar",
                ),
                style = NuvioTypography.KeyShortcutBadge.copy(fontSize = 10.sp),
                color = scheme.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}
