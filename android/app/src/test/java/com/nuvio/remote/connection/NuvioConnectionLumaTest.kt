package com.nuvio.remote.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioConnectionLumaTest {
    @Test
    fun `first decoded frame samples immediately even right after boot`() {
        assertTrue(shouldSampleFrameLuma(prevElapsedMs = 0L, nowElapsedMs = 120L))
        assertTrue(shouldSampleFrameLuma(prevElapsedMs = 0L, nowElapsedMs = 9_500L))
    }

    @Test
    fun `throttles repeated luma checks inside 800ms window and allows afterwards`() {
        val t0 = 50_000L
        assertFalse(shouldSampleFrameLuma(prevElapsedMs = t0, nowElapsedMs = t0 + 16L))
        assertFalse(shouldSampleFrameLuma(prevElapsedMs = t0, nowElapsedMs = t0 + 799L))
        assertTrue(shouldSampleFrameLuma(prevElapsedMs = t0, nowElapsedMs = t0 + 800L))
        assertTrue(shouldSampleFrameLuma(prevElapsedMs = t0, nowElapsedMs = t0 + 1_200L))
    }
}
