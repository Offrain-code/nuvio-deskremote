package com.nuvio.remote.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4LogicTest {
    @Test
    fun `computeCursorFollowPan returns zero when zoom is 1x`() {
        val pan = computeCursorFollowPan(
            currentPan = Offset(20f, -15f),
            cursorNormX = 0.9f,
            cursorNormY = 0.9f,
            size = IntSize(1000, 600),
            zoom = 1.0f,
        )
        assertEquals(Offset.Zero, pan)
    }

    @Test
    fun `computeCursorFollowPan keeps pan stable inside center deadzone`() {
        val pan = computeCursorFollowPan(
            currentPan = Offset.Zero,
            cursorNormX = 0.52f,
            cursorNormY = 0.48f,
            size = IntSize(1000, 600),
            zoom = 2.0f,
        )
        assertEquals(Offset.Zero, pan)
    }

    @Test
    fun `computeCursorFollowPan shifts viewport toward cursor near right and bottom edges`() {
        val pan = computeCursorFollowPan(
            currentPan = Offset.Zero,
            cursorNormX = 0.95f,
            cursorNormY = 0.92f,
            size = IntSize(1000, 600),
            zoom = 2.0f,
        )
        // Moving cursor right/down must pan video left (negative X) and up (negative Y), clamped to max pan
        assertTrue(pan.x < 0f)
        assertTrue(pan.y < 0f)
        assertTrue(pan.x >= -500f)
        assertTrue(pan.y >= -300f)
    }
}
