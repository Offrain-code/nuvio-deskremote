package com.nuvio.remote.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.ConnState
import com.nuvio.remote.ui.i18n.LocalAppLanguage
import com.nuvio.remote.ui.i18n.ResolvedLanguage
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass
import kotlin.math.max
import kotlin.math.min

/**
 * Sliding active pill for equal-width segmented selectors (5-tab dock and profiles).
 */
@Composable
fun LiquidSlidingWhitePillLayer(
    selectedIndex: Int,
    itemCount: Int,
    itemSpacing: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
) {
    val reducedMotion = LocalReducedMotion.current
    val scheme = LocalNuvioColors.current
    val target = selectedIndex.coerceIn(0, (itemCount - 1).coerceAtLeast(0)).toFloat()
    val leadState = animateFloatAsState(
        targetValue = target,
        animationSpec = NuvioMotion.liquidLeadSpring(reducedMotion),
        label = "pill_lead",
    )
    val trailState = animateFloatAsState(
        targetValue = target,
        animationSpec = NuvioMotion.liquidTrailSpring(reducedMotion),
        label = "pill_trail",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (itemCount <= 0 || size.width <= 1f || size.height <= 1f) return@Canvas
        val gapPx = itemSpacing.toPx()
        val slotW = ((size.width - gapPx * (itemCount - 1)) / itemCount).coerceAtLeast(1f)
        val step = slotW + gapPx

        val minIdx = min(leadState.value, trailState.value)
        val maxIdx = max(leadState.value, trailState.value)
        val spread = (maxIdx - minIdx).coerceAtLeast(0f)

        val pillLeft = (minIdx * step).coerceIn(0f, size.width - slotW)
        val pillRight = (maxIdx * step + slotW).coerceIn(pillLeft + slotW, size.width)
        val pillWidth = (pillRight - pillLeft).coerceAtLeast(slotW)
        val squishY = 1f - (spread * 0.08f).coerceAtMost(0.08f)
        val pillHeight = size.height * squishY
        val pillTop = (size.height - pillHeight) * 0.5f
        val radiusPx = min(cornerRadius.toPx(), min(pillWidth, pillHeight) * 0.5f)
        val cr = CornerRadius(radiusPx, radiusPx)

        drawRoundRect(
            color = scheme.pillActiveBgTop.copy(alpha = 0.14f),
            topLeft = Offset(
                (pillLeft - 1.5.dp.toPx()).coerceAtLeast(0f),
                (pillTop - 1.dp.toPx()).coerceAtLeast(0f),
            ),
            size = Size(
                width = (pillWidth + 3.dp.toPx()).coerceAtMost(size.width),
                height = (pillHeight + 2.dp.toPx()).coerceAtMost(size.height),
            ),
            cornerRadius = cr,
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom),
                startY = pillTop,
                endY = pillTop + pillHeight,
            ),
            topLeft = Offset(pillLeft, pillTop),
            size = Size(pillWidth, pillHeight),
            cornerRadius = cr,
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(scheme.glassRim, scheme.glassRim.copy(alpha = 0.25f)),
                startY = pillTop,
                endY = pillTop + pillHeight,
            ),
            topLeft = Offset(pillLeft, pillTop),
            size = Size(pillWidth, pillHeight),
            cornerRadius = cr,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/**
 * Translucent sliding pill for variable-width segments (e.g. Browse | Player | Keyboard).
 */
@Composable
fun LiquidSlidingGlassPillLayer(
    selectedIndex: Int,
    slotLefts: List<Float>,
    slotWidths: List<Float>,
    accentTint: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val scheme = LocalNuvioColors.current
    val safeIndex = selectedIndex.coerceIn(0, (slotLefts.size - 1).coerceAtLeast(0))
    val targetLeft = slotLefts.getOrElse(safeIndex) { 0f }
    val targetWidth = slotWidths.getOrElse(safeIndex) { 0f }
    val targetRight = targetLeft + targetWidth

    val leadLeft by animateFloatAsState(
        targetValue = targetLeft,
        animationSpec = NuvioMotion.liquidLeadSpring(reducedMotion),
        label = "seg_lead_left",
    )
    val trailLeft by animateFloatAsState(
        targetValue = targetLeft,
        animationSpec = NuvioMotion.liquidTrailSpring(reducedMotion),
        label = "seg_trail_left",
    )
    val leadRight by animateFloatAsState(
        targetValue = targetRight,
        animationSpec = NuvioMotion.liquidLeadSpring(reducedMotion),
        label = "seg_lead_right",
    )
    val trailRight by animateFloatAsState(
        targetValue = targetRight,
        animationSpec = NuvioMotion.liquidTrailSpring(reducedMotion),
        label = "seg_trail_right",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (targetWidth <= 1f || size.height <= 1f) return@Canvas
        val left = min(leadLeft, trailLeft).coerceIn(0f, size.width)
        val right = max(leadRight, trailRight).coerceIn(left + 1f, size.width)
        val w = (right - left).coerceAtLeast(1f)
        val stretchRatio = ((w - targetWidth) / targetWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val h = size.height * (1f - stretchRatio * 0.06f)
        val top = (size.height - h) * 0.5f
        val cr = CornerRadius(h * 0.5f, h * 0.5f)
        val hasAccent = accentTint != Color.Unspecified

        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(scheme.glassRegularElevatedTop, scheme.glassRegularElevatedBottom),
                startY = top,
                endY = top + h,
            ),
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = cr,
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    scheme.glassSpecularTop,
                    if (hasAccent) accentTint.copy(alpha = 0.14f) else scheme.glassSpecularMid,
                ),
                start = Offset(left, top),
                end = Offset(left + w, top + h),
            ),
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = cr,
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    (if (hasAccent) accentTint else scheme.glassRim).copy(alpha = 0.52f),
                    scheme.glassBorder,
                    if (hasAccent) accentTint.copy(alpha = 0.28f) else scheme.glassBorder,
                ),
                start = Offset(left, top),
                end = Offset(left + w, top + h),
            ),
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = cr,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/**
 * GPU-only transition (scale 0.98 -> 1 + fade, 260-300ms) between icons.
 */
@Composable
fun LiquidAnimatedIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val reducedMotion = LocalReducedMotion.current
    val resolvedTint = if (tint != Color.Unspecified) tint else LocalNuvioColors.current.textPrimary
    AnimatedContent(
        targetState = icon,
        transitionSpec = {
            NuvioMotion.morphEnter(reducedMotion) togetherWith NuvioMotion.morphExit(reducedMotion)
        },
        label = "liquid_animated_icon",
    ) { currentIcon ->
        NuvioIcon(
            imageVector = currentIcon,
            contentDescription = contentDescription,
            tint = resolvedTint,
            modifier = modifier,
        )
    }
}

/**
 * Circular Liquid Glass button with guaranteed >= 48dp touch target.
 */
@Composable
fun LiquidCircleButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    showLabel: Boolean = true,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp,
    active: Boolean = false,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) 0.92f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "liquid_circle_scale",
    )
    val touchSize = size.coerceAtLeast(48.dp)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(touchSize)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) {
                    if (hapticsEnabled) Haptics.action(context)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .size(size)
                    .nuvioGlass(
                        shape = CircleShape,
                        elevated = active,
                        pressed = isPressed,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LiquidAnimatedIcon(
                    icon = icon,
                    contentDescription = label,
                    tint = scheme.textPrimary,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        if (showLabel && !label.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            NuvioText(
                text = label,
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.textSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Rectangular Liquid Glass key pill with >= 48dp touch target.
 */
@Composable
fun NuvioKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    elevated: Boolean = false,
    accent: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val resolvedFg = if (contentColor != Color.Unspecified) contentColor else scheme.textPrimary
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) 0.94f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "key_scale",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = 48.dp)
            .defaultMinSize(minWidth = 48.dp, minHeight = height.coerceAtLeast(48.dp))
            .nuvioGlass(
                shape = RoundedCornerShape(14.dp),
                elevated = elevated,
                pressed = isPressed,
                accentTint = accent,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        NuvioText(
            text = label,
            style = NuvioTypography.KeyLabelPrimary,
            color = resolvedFg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Minimalist connection status LED. */
@Composable
fun StatusLed(connState: ConnState, modifier: Modifier = Modifier) {
    val scheme = LocalNuvioColors.current
    val color = when (connState) {
        is ConnState.Connected -> scheme.statusOnline
        is ConnState.Connecting -> scheme.statusWarning
        is ConnState.Failed -> scheme.statusError
        is ConnState.Disconnected -> scheme.textMuted
    }
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** Boolean overload for status LED compatibility. */
@Composable
fun StatusLed(connected: Boolean, modifier: Modifier = Modifier) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (connected) scheme.statusOnline else scheme.textMuted),
    )
}

/**
 * Discreet 4-color TV bar in flat opaque cells (no inner nested glass) with 48dp hitboxes.
 */
@Composable
fun TvColorBar(onColor: (NuvioTvColor) -> Unit) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val items = listOf(
            NuvioTvColor.RED,
            NuvioTvColor.GREEN,
            NuvioTvColor.YELLOW,
            NuvioTvColor.BLUE,
        )
        for (item in items) {
            val reducedMotion = LocalReducedMotion.current
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (isPressed && !reducedMotion) 0.95f else 1f,
                animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
                label = "tvColorScale",
            )
            val tint = item.color()
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) {
                        if (hapticsEnabled) Haptics.toggle(context)
                        onColor(item)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isPressed) scheme.contentCellPressed else scheme.contentCellSurface)
                        .border(1.dp, tint.copy(alpha = 0.34f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(tint),
                        )
                        NuvioText(
                            text = item.label(lang),
                            style = NuvioTypography.KeyShortcutBadge,
                            color = scheme.textPrimary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

data class NuvioTvColor(
    val labelEn: String,
    val labelPt: String,
    val keyShortcut: String,
    val color: @Composable () -> Color,
) {
    val label: String
        get() = labelEn

    fun label(lang: ResolvedLanguage): String = l10n(lang, labelEn, labelPt)

    companion object {
        val RED = NuvioTvColor("Red", "Verm.", "S") { LocalNuvioColors.current.tvRed }
        val GREEN = NuvioTvColor("Green", "Verde", "L") { LocalNuvioColors.current.tvGreen }
        val YELLOW = NuvioTvColor("Yellow", "Amar.", "C") { LocalNuvioColors.current.tvYellow }
        val BLUE = NuvioTvColor("Blue", "Azul", "G") { LocalNuvioColors.current.tvBlue }
    }
}
