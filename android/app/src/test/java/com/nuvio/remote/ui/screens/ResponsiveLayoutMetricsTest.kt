package com.nuvio.remote.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveLayoutMetricsTest {
    @Test
    fun `computeCockpitMetrics scales safely for 320dp narrow phones without horizontal overlap`() {
        val maxWidth = 320.dp
        val m = computeCockpitMetrics(
            maxWidth = maxWidth,
            maxHeight = 560.dp,
            isPlayer = false,
            isSheetExpanded = false,
            fontScale = 1.0f,
        )
        assertEquals(10.dp, m.horizontalPadding)
        assertEquals(48.dp, m.rockerWidth)
        assertEquals(144.dp, m.rockerHeight)
        assertEquals(48.dp, m.centerOkSize)
        val totalUsed = m.horizontalPadding * 2 + m.rockerWidth * 2 + m.dpadDiameter + 16.dp
        assertTrue("totalUsed=$totalUsed should fit within $maxWidth", totalUsed <= maxWidth)
    }

    @Test
    fun `computeCockpitMetrics uses CompactStandard on 360dp and large font`() {
        val m = computeCockpitMetrics(
            maxWidth = 360.dp,
            maxHeight = 620.dp,
            isPlayer = false,
            isSheetExpanded = false,
            fontScale = 1.25f,
        )
        assertEquals(14.dp, m.horizontalPadding)
        assertEquals(50.dp, m.rockerWidth)
        assertEquals(52.dp, m.centerOkSize)
        val totalUsed = m.horizontalPadding * 2 + m.rockerWidth * 2 + m.dpadDiameter + 16.dp
        assertTrue("totalUsed=$totalUsed should fit within 360.dp", totalUsed <= 360.dp)
        assertTrue(
            "D-Pad inner diameter (${m.dpadDiameter - 4.dp}) must fit 2x48dp + OK (${m.centerOkSize}) without overlap",
            m.dpadDiameter - 4.dp >= 96.dp + m.centerOkSize,
        )
    }

    @Test
    fun `computeCockpitMetrics keeps zero D-Pad overlap in CompactStandard Player`() {
        val m = computeCockpitMetrics(
            maxWidth = 360.dp,
            maxHeight = 640.dp,
            isPlayer = true,
            isSheetExpanded = true,
            fontScale = 1.0f,
        )
        assertEquals(160.dp, m.dpadDiameter)
        assertEquals(48.dp, m.centerOkSize)
        assertTrue(
            "D-Pad inner diameter (${m.dpadDiameter - 4.dp}) must fit 2x48dp + OK (${m.centerOkSize})",
            m.dpadDiameter - 4.dp >= 96.dp + m.centerOkSize,
        )
    }

    @Test
    fun `computeCockpitMetrics uses ExpandedComfort on large screens when not in player`() {
        val m = computeCockpitMetrics(
            maxWidth = 412.dp,
            maxHeight = 760.dp,
            isPlayer = false,
            isSheetExpanded = false,
            fontScale = 1.0f,
        )
        assertEquals(16.dp, m.horizontalPadding)
        assertEquals(52.dp, m.rockerWidth)
        assertEquals(170.dp, m.rockerHeight)
        assertEquals(186.dp, m.dpadDiameter)
        assertEquals(64.dp, m.centerOkSize)
        assertTrue(
            "D-Pad inner diameter (${m.dpadDiameter - 4.dp}) must fit 2x48dp + OK (${m.centerOkSize})",
            m.dpadDiameter - 4.dp >= 96.dp + m.centerOkSize,
        )
    }

    @Test
    fun `resolveTrackpadQuickActionsMode covers all 3 breakpoints and accessibility fontScale`() {
        assertEquals(
            TrackpadQuickActionsMode.STANDARD_4_COL,
            resolveTrackpadQuickActionsMode(maxWidth = 360.dp, fontScale = 1.0f),
        )
        assertEquals(
            TrackpadQuickActionsMode.COMPACT_4_COL,
            resolveTrackpadQuickActionsMode(maxWidth = 340.dp, fontScale = 1.0f),
        )
        assertEquals(
            TrackpadQuickActionsMode.COMPACT_4_COL,
            resolveTrackpadQuickActionsMode(maxWidth = 380.dp, fontScale = 1.2f),
        )
        assertEquals(
            TrackpadQuickActionsMode.ACCESSIBLE_GRID_2X2,
            resolveTrackpadQuickActionsMode(maxWidth = 320.dp, fontScale = 1.0f),
        )
        assertEquals(
            TrackpadQuickActionsMode.ACCESSIBLE_GRID_2X2,
            resolveTrackpadQuickActionsMode(maxWidth = 400.dp, fontScale = 1.3f),
        )
    }
}
