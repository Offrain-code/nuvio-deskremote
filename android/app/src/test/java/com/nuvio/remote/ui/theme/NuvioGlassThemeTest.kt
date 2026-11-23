package com.nuvio.remote.ui.theme

import android.os.Build
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioGlassThemeTest {
    @Test
    fun `resolveGlassApiTier maps legacy blur and lens tiers cleanly`() {
        assertEquals(GlassApiTier.LEGACY_PRE_31, resolveGlassApiTier(Build.VERSION_CODES.Q))
        assertEquals(GlassApiTier.LEGACY_PRE_31, resolveGlassApiTier(Build.VERSION_CODES.R))
        assertEquals(GlassApiTier.BLUR_31_32, resolveGlassApiTier(Build.VERSION_CODES.S))
        assertEquals(GlassApiTier.BLUR_31_32, resolveGlassApiTier(Build.VERSION_CODES.S_V2))
        assertEquals(GlassApiTier.LENS_33_PLUS, resolveGlassApiTier(Build.VERSION_CODES.TIRAMISU))
        assertEquals(GlassApiTier.LENS_33_PLUS, resolveGlassApiTier(Build.VERSION_CODES.VANILLA_ICE_CREAM))
    }

    @Test
    fun `computeClearScrimAlpha applies 35 percent scrim on light backdrop`() {
        assertEquals(0.35f, computeClearScrimAlpha(isBackdropLight = true, surfaceAlpha = 1f), 0.001f)
        assertEquals(0.273f, computeClearScrimAlpha(isBackdropLight = true, surfaceAlpha = 0.78f), 0.001f)
        assertEquals(0.18f, computeClearScrimAlpha(isBackdropLight = false, surfaceAlpha = 1f), 0.001f)
    }

    @Test
    fun `clear variant keeps white overlay text and bright active pill in both themes`() {
        listOf(DarkNuvioColors, LightNuvioColors).forEach { scheme ->
            assertEquals(Color.White, scheme.clearOverlayText)
            assertEquals(Color.White, scheme.clearPillActiveBgTop)
            assertEquals(Color(0xFF090A0D), scheme.clearPillActiveFg)
        }
    }

    @Test
    fun `resolveClearBackdropLight defaults safely on live WebRTC stream without bitmap`() {
        assertTrue(
            resolveClearBackdropLight(
                hasFallbackBitmap = false,
                fallbackBitmapLight = false,
                streamStatus = "live",
                rtcSampledLight = null,
            ),
        )
        assertFalse(
            resolveClearBackdropLight(
                hasFallbackBitmap = false,
                fallbackBitmapLight = false,
                streamStatus = "live",
                rtcSampledLight = false,
            ),
        )
        assertTrue(
            resolveClearBackdropLight(
                hasFallbackBitmap = true,
                fallbackBitmapLight = true,
                streamStatus = "live",
                rtcSampledLight = false,
            ),
        )
        assertFalse(
            resolveClearBackdropLight(
                hasFallbackBitmap = false,
                fallbackBitmapLight = false,
                streamStatus = "idle",
                rtcSampledLight = true,
            ),
        )
    }

    @Test
    fun `motion durations stay within approved 250 to 400ms window`() {
        assertTrue(NuvioMotion.ENTER_DURATION_MS in 250..400)
        assertTrue(NuvioMotion.EXIT_DURATION_MS in 250..400)
    }

    @Test
    fun `resolveClearSurfaceAlphas matches Phase A2 resting active and light ranges`() {
        val restingDark = resolveClearSurfaceAlphas(
            tier = GlassApiTier.LENS_33_PLUS,
            isBackdropLight = false,
            resting = true,
        )
        assertTrue(restingDark.effectiveBottomAlpha in 0.22f..0.28f)

        val activeDark = resolveClearSurfaceAlphas(
            tier = GlassApiTier.BLUR_31_32,
            isBackdropLight = false,
            resting = false,
        )
        assertTrue(activeDark.effectiveBottomAlpha in 0.34f..0.40f)

        val restingLight = resolveClearSurfaceAlphas(
            tier = GlassApiTier.LENS_33_PLUS,
            isBackdropLight = true,
            resting = true,
        )
        assertTrue(restingLight.effectiveBottomAlpha in 0.40f..0.44f)

        val activeLight = resolveClearSurfaceAlphas(
            tier = GlassApiTier.LENS_33_PLUS,
            isBackdropLight = true,
            resting = false,
        )
        assertTrue(activeLight.effectiveBottomAlpha in 0.56f..0.62f)
    }

    @Test
    fun `resolveClearSurfaceAlphas preserves API below 31 structured opacity fallbacks`() {
        val legacyRestDark = resolveClearSurfaceAlphas(
            tier = GlassApiTier.LEGACY_PRE_31,
            isBackdropLight = false,
            resting = true,
        )
        val legacyRestLight = resolveClearSurfaceAlphas(
            tier = GlassApiTier.LEGACY_PRE_31,
            isBackdropLight = true,
            resting = true,
        )
        val legacyActiveDark = resolveClearSurfaceAlphas(
            tier = GlassApiTier.LEGACY_PRE_31,
            isBackdropLight = false,
            resting = false,
        )
        val legacyActiveLight = resolveClearSurfaceAlphas(
            tier = GlassApiTier.LEGACY_PRE_31,
            isBackdropLight = true,
            resting = false,
        )
        assertTrue(legacyRestDark.effectiveBottomAlpha in 0.44f..0.50f)
        assertTrue(legacyRestLight.effectiveBottomAlpha in 0.56f..0.62f)
        assertTrue(legacyActiveDark.effectiveBottomAlpha in 0.66f..0.72f)
        assertTrue(legacyActiveLight.effectiveBottomAlpha in 0.76f..0.82f)
    }
}
