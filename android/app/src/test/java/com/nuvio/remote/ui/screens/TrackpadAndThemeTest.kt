package com.nuvio.remote.ui.screens

import com.nuvio.remote.MainActivity
import com.nuvio.remote.connection.NuvioTab
import com.nuvio.remote.ui.components.resolveSwipeStepDirection
import com.nuvio.remote.ui.theme.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackpadAndThemeTest {
    @Test
    fun appThemeModeFromWireResolvesAllModesAndFallsBackToSystem() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromWire("system"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromWire("dark"))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromWire("light"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromWire(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromWire("unknown"))
    }

    @Test
    fun classifyThreeFingerSwipeRespectsThresholdAndDominantAxis() {
        assertNull(classifyThreeFingerSwipe(dx = 20f, dy = -30f, thresholdPx = 48f))
        assertEquals(ThreeFingerDirection.UP, classifyThreeFingerSwipe(dx = 10f, dy = -60f, thresholdPx = 48f))
        assertEquals(ThreeFingerDirection.DOWN, classifyThreeFingerSwipe(dx = -15f, dy = 52f, thresholdPx = 48f))
        assertEquals(ThreeFingerDirection.LEFT, classifyThreeFingerSwipe(dx = -55f, dy = 12f, thresholdPx = 48f))
        assertEquals(ThreeFingerDirection.RIGHT, classifyThreeFingerSwipe(dx = 64f, dy = -20f, thresholdPx = 48f))
    }

    @Test
    fun resolveSwipeStepDirectionClassifiesAllFourDirections() {
        assertNull(resolveSwipeStepDirection(accX = 12f, accY = -10f, stepPx = 30f))
        assertEquals("right", resolveSwipeStepDirection(accX = 35f, accY = 8f, stepPx = 30f))
        assertEquals("left", resolveSwipeStepDirection(accX = -40f, accY = -5f, stepPx = 30f))
        assertEquals("down", resolveSwipeStepDirection(accX = 5f, accY = 32f, stepPx = 30f))
        assertEquals("up", resolveSwipeStepDirection(accX = -4f, accY = -36f, stepPx = 30f))
    }

    @Test
    fun resolveTabFromShortcutExtraMapsValidTabsOnly() {
        assertEquals(NuvioTab.TRACKPAD, MainActivity.resolveTabFromShortcutExtra("trackpad"))
        assertEquals(NuvioTab.SCREEN, MainActivity.resolveTabFromShortcutExtra("SCREEN"))
        assertEquals(NuvioTab.APPS, MainActivity.resolveTabFromShortcutExtra("apps"))
        assertEquals(NuvioTab.REMOTE, MainActivity.resolveTabFromShortcutExtra("remote"))
        assertNull(MainActivity.resolveTabFromShortcutExtra("invalid"))
        assertNull(MainActivity.resolveTabFromShortcutExtra(null))
    }
}
