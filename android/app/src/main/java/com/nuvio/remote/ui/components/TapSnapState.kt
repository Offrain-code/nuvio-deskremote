package com.nuvio.remote.ui.components

import androidx.compose.ui.geometry.Offset

/**
 * Dispatches immediate single taps (0ms double-tap delay in Compose) while preserving native
 * Windows double-clicks: a quick second tap near the first reuses the exact first-tap coordinate
 * in Direct Touch mode so Windows reliably recognizes WM_LBUTTONDBLCLK.
 */
internal class TapSnapState(
    private val windowMs: Long = DEFAULT_DOUBLE_TAP_WINDOW_MS,
) {
    private var lastTapMs = 0L
    private var lastTapPos = Offset.Zero
    private var lastWasSnapped = false

    fun resolveTap(pos: Offset, nowMs: Long, maxDistancePx: Float): Offset {
        val withinWindow = lastTapMs > 0L && (nowMs - lastTapMs) in 0L..windowMs
        val nearPrev = (pos - lastTapPos).getDistance() <= maxDistancePx
        if (!lastWasSnapped && withinWindow && nearPrev) {
            lastTapMs = nowMs
            lastWasSnapped = true
            return lastTapPos
        }
        lastTapMs = nowMs
        lastTapPos = pos
        lastWasSnapped = false
        return pos
    }

    companion object {
        const val DEFAULT_DOUBLE_TAP_WINDOW_MS = 340L
    }
}
