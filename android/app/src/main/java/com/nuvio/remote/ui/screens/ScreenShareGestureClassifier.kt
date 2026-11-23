package com.nuvio.remote.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.hypot

enum class MultiTouchMode {
    UNDECIDED,
    SCROLL,
    PINCH_ZOOM,
}

data class MultiTouchDecision(
    val mode: MultiTouchMode,
    val zoomChange: Float = 1f,
    val panChange: Offset = Offset.Zero,
    val scrollSteps: Int = 0,
)

/**
 * Classifies two-finger gestures in Screen Share with per-gesture intent locking,
 * preventing vertical scroll from conflicting with pinch-to-zoom due to micro finger jitter.
 */
class MultiTouchGestureClassifier(
    private val decisionDistancePx: Float = 44f,
    private val pinchRatioThreshold: Float = 0.14f,
    private val scrollGain: Float = 0.15f,
) {
    var mode: MultiTouchMode = MultiTouchMode.UNDECIDED
        private set

    private var accumPanX = 0f
    private var accumPanY = 0f
    private var cumulativeScale = 1f
    private var scrollAcc = 0f

    fun reset() {
        mode = MultiTouchMode.UNDECIDED
        accumPanX = 0f
        accumPanY = 0f
        cumulativeScale = 1f
        scrollAcc = 0f
    }

    fun process(zoomChange: Float, panChange: Offset, currentZoom: Float): MultiTouchDecision {
        if (mode == MultiTouchMode.UNDECIDED) {
            if (currentZoom > 1.01f) {
                mode = MultiTouchMode.PINCH_ZOOM
            } else {
                resolveInitialMode(zoomChange, panChange)
            }
        }
        return when (mode) {
            MultiTouchMode.UNDECIDED -> MultiTouchDecision(MultiTouchMode.UNDECIDED)
            MultiTouchMode.PINCH_ZOOM -> MultiTouchDecision(
                mode = MultiTouchMode.PINCH_ZOOM,
                zoomChange = zoomChange,
                panChange = panChange,
            )
            MultiTouchMode.SCROLL -> {
                val steps = consumeScrollDelta(panChange.y)
                MultiTouchDecision(mode = MultiTouchMode.SCROLL, scrollSteps = steps)
            }
        }
    }

    fun consumeSingleFingerScroll(deltaY: Float): Int = consumeScrollDelta(deltaY)

    private fun resolveInitialMode(zoomChange: Float, panChange: Offset) {
        cumulativeScale *= zoomChange
        accumPanX += panChange.x
        accumPanY += panChange.y
        val scaleDelta = abs(cumulativeScale - 1f)
        val absY = abs(accumPanY)
        val absX = abs(accumPanX)
        val panMag = hypot(accumPanX, accumPanY)

        mode = when {
            scaleDelta >= pinchRatioThreshold -> MultiTouchMode.PINCH_ZOOM
            absY >= decisionDistancePx && absY >= absX * 1.15f -> MultiTouchMode.SCROLL
            panMag >= decisionDistancePx && scaleDelta >= pinchRatioThreshold * 0.65f -> MultiTouchMode.PINCH_ZOOM
            else -> MultiTouchMode.UNDECIDED
        }
    }

    private fun consumeScrollDelta(deltaY: Float): Int {
        if (abs(deltaY) <= 0.4f) return 0
        scrollAcc += deltaY * scrollGain
        val step = scrollAcc.toInt()
        if (step != 0) scrollAcc -= step
        return step
    }
}

internal fun isPointInsideVideoRect(pos: Offset, origin: Offset, size: IntSize): Boolean {
    if (size.width <= 1 || size.height <= 1) return true
    return pos.x >= origin.x &&
        pos.x <= origin.x + size.width &&
        pos.y >= origin.y &&
        pos.y <= origin.y + size.height
}

internal fun isPointInRightScrollStrip(pos: Offset, containerWidth: Int, stripWidthPx: Float): Boolean {
    if (containerWidth <= 1 || stripWidthPx <= 0f) return false
    return pos.x >= containerWidth - stripWidthPx
}

internal enum class DoubleTapDragPhase {
    IDLE,
    ARMED,
    DRAGGING,
}

/**
 * Tracks the double-tap-and-hold drag gesture (tap 1 -> quick tap 2 kept pressed -> drag -> release).
 * Preserves 0ms single-tap dispatch and native double-click when the second tap releases quickly,
 * and engages left-button hold as soon as the second tap either moves past [engageMovePx] or stays
 * pressed for [holdEngageMs].
 */
internal class DoubleTapDragTracker(
    private val doubleTapWindowMs: Long = DEFAULT_DOUBLE_TAP_WINDOW_MS,
    private val holdEngageMs: Long = DEFAULT_HOLD_ENGAGE_MS,
    private val maxDoubleTapDistancePx: Float = 96f,
    private val engageMovePx: Float = 16f,
    private val maxTapDurationMs: Long = DEFAULT_MAX_TAP_DURATION_MS,
    private val maxTapMovePx: Float = 24f,
) {
    var phase: DoubleTapDragPhase = DoubleTapDragPhase.IDLE
        private set

    var anchorPosition: Offset = Offset.Zero
        private set

    private var lastTapUpMs: Long = 0L
    private var lastTapPos: Offset = Offset.Zero
    private var pressStartMs: Long = 0L
    private var pressStartPos: Offset = Offset.Zero
    private var pressMovedPx: Float = 0f

    val isArmed: Boolean
        get() = phase == DoubleTapDragPhase.ARMED

    val isDragging: Boolean
        get() = phase == DoubleTapDragPhase.DRAGGING

    fun onPointerDown(pos: Offset, nowMs: Long): Boolean {
        pressStartMs = nowMs
        pressStartPos = pos
        pressMovedPx = 0f
        val withinWindow = lastTapUpMs > 0L && (nowMs - lastTapUpMs) in 0L..doubleTapWindowMs
        val nearLastTap = (pos - lastTapPos).getDistance() <= maxDoubleTapDistancePx
        return if (withinWindow && nearLastTap) {
            phase = DoubleTapDragPhase.ARMED
            anchorPosition = lastTapPos
            true
        } else {
            phase = DoubleTapDragPhase.IDLE
            anchorPosition = pos
            false
        }
    }

    fun remainingHoldMs(nowMs: Long): Long {
        if (phase != DoubleTapDragPhase.ARMED) return 0L
        return (holdEngageMs - (nowMs - pressStartMs)).coerceAtLeast(0L)
    }

    fun onPointerMove(pos: Offset, deltaPx: Float, nowMs: Long): Boolean {
        pressMovedPx += deltaPx
        if (phase != DoubleTapDragPhase.ARMED) return false
        val distFromDown = (pos - pressStartPos).getDistance()
        val elapsed = nowMs - pressStartMs
        if (distFromDown >= engageMovePx || pressMovedPx >= engageMovePx || elapsed >= holdEngageMs) {
            phase = DoubleTapDragPhase.DRAGGING
            lastTapUpMs = 0L
            return true
        }
        return false
    }

    fun onHoldTimeout(nowMs: Long): Boolean {
        if (phase != DoubleTapDragPhase.ARMED) return false
        if (nowMs - pressStartMs < holdEngageMs) return false
        phase = DoubleTapDragPhase.DRAGGING
        lastTapUpMs = 0L
        return true
    }

    fun onPointerUp(pos: Offset, nowMs: Long): Boolean {
        val wasDragging = phase == DoubleTapDragPhase.DRAGGING
        val wasArmed = phase == DoubleTapDragPhase.ARMED
        phase = DoubleTapDragPhase.IDLE

        if (wasDragging) {
            lastTapUpMs = 0L
            return true
        }

        val duration = nowMs - pressStartMs
        val dist = (pos - pressStartPos).getDistance()
        val isCleanTap = duration in 0L..maxTapDurationMs && pressMovedPx <= maxTapMovePx && dist <= maxTapMovePx
        if (isCleanTap && !wasArmed) {
            lastTapUpMs = nowMs
            lastTapPos = pos
        } else {
            lastTapUpMs = 0L
        }
        return false
    }

    fun onCancel(): Boolean {
        val wasDragging = phase == DoubleTapDragPhase.DRAGGING
        phase = DoubleTapDragPhase.IDLE
        lastTapUpMs = 0L
        return wasDragging
    }

    companion object {
        const val DEFAULT_DOUBLE_TAP_WINDOW_MS = 340L
        const val DEFAULT_HOLD_ENGAGE_MS = 140L
        const val DEFAULT_MAX_TAP_DURATION_MS = 260L
    }
}
