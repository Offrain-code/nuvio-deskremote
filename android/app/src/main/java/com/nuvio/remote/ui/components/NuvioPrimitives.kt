package com.nuvio.remote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import kotlin.math.roundToInt

/** Native Compose text primitive without Material 3 dependency. */
@Composable
fun NuvioText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = NuvioTypography.BodyRegular,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    fontWeight: FontWeight? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    shadow: Shadow? = null,
) {
    val resolvedColor = if (color != Color.Unspecified) color else LocalNuvioColors.current.textPrimary
    val mergedStyle = style.merge(
        color = resolvedColor,
        fontWeight = fontWeight,
        fontSize = fontSize,
        textAlign = textAlign ?: style.textAlign,
        shadow = shadow,
    )
    BasicText(
        text = text,
        modifier = modifier,
        style = mergedStyle,
        softWrap = softWrap,
        overflow = overflow,
        maxLines = maxLines,
    )
}

/** Native Compose vector icon without Material 3, preserving accessibility description. */
@Composable
fun NuvioIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val resolvedTint = if (tint != Color.Unspecified) tint else LocalNuvioColors.current.textPrimary
    val painter = rememberVectorPainter(imageVector)
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(resolvedTint),
    )
}

/** High-contrast native toggle switch with >= 48dp touch target. */
@Composable
fun NuvioGlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }

    val trackColor by animateColorAsState(
        targetValue = if (checked) scheme.statusOnline else scheme.contentCellPressed,
        animationSpec = NuvioMotion.smoothSpring(reducedMotion),
        label = "switchTrack",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) Color.White else scheme.textSecondary,
        animationSpec = NuvioMotion.smoothSpring(reducedMotion),
        label = "switchThumb",
    )
    val thumbProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "switchOffset",
    )
    val slidePx = with(LocalDensity.current) { 20.dp.toPx() }

    Box(
        modifier = modifier
            .sizeIn(minWidth = 52.dp, minHeight = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(50.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(trackColor)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(999.dp))
                .padding(3.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer { translationX = thumbProgress * slidePx }
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(thumbColor),
            )
        }
    }
}

/** Native tactile slider without Material 3, with >= 48dp vertical hitbox and theme-aware contrast. */
@Composable
fun NuvioGlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val density = LocalDensity.current
    val currentOnValueChange by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by androidx.compose.runtime.rememberUpdatedState(onValueChangeFinished)
    val currentHaptics by androidx.compose.runtime.rememberUpdatedState(hapticsEnabled)

    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    fun mapOffsetToValue(xPx: Float, widthPx: Float): Float {
        val rawFrac = (xPx / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val snappedFrac = if (steps > 0) {
            val segments = steps + 1
            (rawFrac * segments).roundToInt().toFloat() / segments
        } else {
            rawFrac
        }
        return (valueRange.start + snappedFrac * span).coerceIn(valueRange.start, valueRange.endInclusive)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                setProgress { targetValue ->
                    val clamped = targetValue.coerceIn(valueRange.start, valueRange.endInclusive)
                    currentOnValueChange(clamped)
                    currentOnValueChangeFinished?.invoke()
                    true
                }
            }
            .pointerInput(valueRange, steps) {
                detectTapGestures { offset ->
                    val next = mapOffsetToValue(offset.x, size.width.toFloat())
                    if (currentHaptics) Haptics.action(context)
                    currentOnValueChange(next)
                    currentOnValueChangeFinished?.invoke()
                }
            }
            .pointerInput(valueRange, steps) {
                detectHorizontalDragGestures(
                    onDragEnd = { currentOnValueChangeFinished?.invoke() },
                    onDragCancel = { currentOnValueChangeFinished?.invoke() },
                ) { change, _ ->
                    change.consume()
                    val next = mapOffsetToValue(change.position.x, size.width.toFloat())
                    currentOnValueChange(next)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val thumbSize = 20.dp
        val maxOffsetPx = with(density) { (maxWidth - thumbSize).coerceAtLeast(0.dp).toPx() }
        val thumbOffsetDp = with(density) { (maxOffsetPx * fraction).toDp() }
        val activeColor = scheme.textPrimary

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(scheme.contentCellPressed)
                .border(0.75.dp, scheme.contentBorder, RoundedCornerShape(999.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(activeColor),
            )
        }
        Box(
            modifier = Modifier
                .offset(x = thumbOffsetDp)
                .size(thumbSize)
                .clip(CircleShape)
                .background(activeColor)
                .border(1.5.dp, scheme.contentBorder, CircleShape),
        )
    }
}
