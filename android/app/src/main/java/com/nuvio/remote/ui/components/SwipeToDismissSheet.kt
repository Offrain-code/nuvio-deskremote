package com.nuvio.remote.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch

/**
 * Interruptible 1:1 swipe-to-dismiss sheet wrapper with standardized 36x4dp drag handle
 * (DESIGN-SPEC §5.15.4). Animates strictly on GPU translationY at 60fps and respects reduced motion.
 */
@Composable
fun SwipeToDismissSheetContainer(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showHandle: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val dismissThresholdPx = with(LocalDensity.current) { 92.dp.toPx() }
    val offsetY = remember { Animatable(0f) }

    Column(
        modifier = modifier
            .graphicsLayer {
                translationY = offsetY.value
            }
            .pointerInput(dismissThresholdPx, reducedMotion) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        val next = (offsetY.value + dragAmount).coerceAtLeast(-24f)
                        val resisted = if (next < 0f) next * 0.35f else next
                        scope.launch { offsetY.snapTo(resisted) }
                    },
                    onDragEnd = {
                        if (offsetY.value >= dismissThresholdPx) {
                            onDismiss()
                        } else {
                            scope.launch {
                                if (reducedMotion) {
                                    offsetY.snapTo(0f)
                                } else {
                                    offsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offsetY.snapTo(0f) }
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showHandle) {
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(scheme.textMuted.copy(alpha = 0.45f)),
            )
        }
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}
