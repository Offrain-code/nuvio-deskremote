package com.nuvio.remote.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenShareGestureClassifierTest {

    @Test
    fun `vertical scroll with natural finger jitter locks into SCROLL and never triggers zoom`() {
        val classifier = MultiTouchGestureClassifier(decisionDistancePx = 40f, pinchRatioThreshold = 0.14f)
        var totalScroll = 0

        repeat(6) { idx ->
            val jitter = if (idx % 2 == 0) 1.015f else 0.99f
            val res = classifier.process(zoomChange = jitter, panChange = Offset(1f, 12f), currentZoom = 1f)
            assertTrue(res.mode != MultiTouchMode.PINCH_ZOOM)
            totalScroll += res.scrollSteps
        }
        assertEquals(MultiTouchMode.SCROLL, classifier.mode)
        assertTrue(totalScroll > 0)

        // Even if finger spacing varies significantly once locked in SCROLL, never switch to zoom
        val afterLock = classifier.process(zoomChange = 1.25f, panChange = Offset(0f, 20f), currentZoom = 1f)
        assertEquals(MultiTouchMode.SCROLL, afterLock.mode)
        assertEquals(1f, afterLock.zoomChange)
    }

    @Test
    fun `true pinch gesture locks into PINCH_ZOOM and never sends scroll`() {
        val classifier = MultiTouchGestureClassifier(decisionDistancePx = 40f, pinchRatioThreshold = 0.14f)

        val d1 = classifier.process(zoomChange = 1.08f, panChange = Offset(2f, 3f), currentZoom = 1f)
        assertEquals(0, d1.scrollSteps)

        val d2 = classifier.process(zoomChange = 1.08f, panChange = Offset(1f, 4f), currentZoom = 1f)
        assertEquals(MultiTouchMode.PINCH_ZOOM, d2.mode)
        assertEquals(0, d2.scrollSteps)
        assertEquals(1.08f, d2.zoomChange)
    }

    @Test
    fun `reset clears locked mode for the next gesture`() {
        val classifier = MultiTouchGestureClassifier(decisionDistancePx = 30f, pinchRatioThreshold = 0.14f)
        classifier.process(zoomChange = 1.20f, panChange = Offset.Zero, currentZoom = 1f)
        assertEquals(MultiTouchMode.PINCH_ZOOM, classifier.mode)

        classifier.reset()
        assertEquals(MultiTouchMode.UNDECIDED, classifier.mode)
    }

    @Test
    fun `isPointInsideVideoRect and isPointInRightScrollStrip detect letterbox bars and scroll strip`() {
        val origin = Offset(200f, 0f)
        val videoSize = IntSize(800, 640)
        assertFalse(isPointInsideVideoRect(Offset(100f, 300f), origin, videoSize))
        assertTrue(isPointInsideVideoRect(Offset(500f, 300f), origin, videoSize))
        assertFalse(isPointInsideVideoRect(Offset(1100f, 300f), origin, videoSize))

        assertTrue(isPointInRightScrollStrip(Offset(1180f, 300f), containerWidth = 1200, stripWidthPx = 64f))
        assertFalse(isPointInRightScrollStrip(Offset(900f, 300f), containerWidth = 1200, stripWidthPx = 64f))
    }
}
