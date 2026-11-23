package com.nuvio.remote.connection

import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.ui.geometry.Offset
import com.nuvio.remote.ui.components.TapSnapState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.LowLatencyWebRtcDecoderFactory

class NuvioLowLatencyTest {
    @Test
    fun `heartbeat interval adapts to screen share LAN vs idle vs tunnel`() {
        assertEquals(
            HEARTBEAT_SCREEN_LAN_MS,
            resolveHeartbeatIntervalMs(inForeground = true, screenStatus = "running", isTunnel = false),
        )
        assertEquals(
            HEARTBEAT_IDLE_LAN_MS,
            resolveHeartbeatIntervalMs(inForeground = true, screenStatus = "stopped", isTunnel = false),
        )
        assertEquals(
            HEARTBEAT_TUNNEL_MS,
            resolveHeartbeatIntervalMs(inForeground = true, screenStatus = "running", isTunnel = true),
        )
        assertEquals(
            HEARTBEAT_TUNNEL_MS,
            resolveHeartbeatIntervalMs(inForeground = false, screenStatus = "running", isTunnel = false),
        )
    }

    @Test
    fun `wifi lock selects low latency mode on Android 10 plus and high perf below`() {
        assertEquals(
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
            WifiLatencyLock.resolveWifiLockMode(Build.VERSION_CODES.Q),
        )
        @Suppress("DEPRECATION")
        assertEquals(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            WifiLatencyLock.resolveWifiLockMode(Build.VERSION_CODES.O),
        )
    }

    @Test
    fun `tap snap helper keeps first tap exact and snaps quick nearby second tap for double click`() {
        val snap = TapSnapState(windowMs = 340L)
        val first = snap.resolveTap(Offset(100f, 200f), nowMs = 1_000L, maxDistancePx = 50f)
        assertEquals(Offset(100f, 200f), first)

        val secondQuickNearby = snap.resolveTap(Offset(118f, 212f), nowMs = 1_160L, maxDistancePx = 50f)
        assertEquals(Offset(100f, 200f), secondQuickNearby)

        // Third tap starts a fresh anchor instead of sticking to the first point
        val third = snap.resolveTap(Offset(300f, 400f), nowMs = 1_260L, maxDistancePx = 50f)
        assertEquals(Offset(300f, 400f), third)

        // Tap outside the double-tap window does not snap
        val delayed = snap.resolveTap(Offset(310f, 405f), nowMs = 2_000L, maxDistancePx = 50f)
        assertEquals(Offset(310f, 405f), delayed)
    }

    @Test
    fun `webrtc low latency decoder format gate enables standard key on Android 11 plus`() {
        assertTrue(LowLatencyWebRtcDecoderFactory.shouldSetStandardLowLatencyKey(Build.VERSION_CODES.R))
        assertTrue(LowLatencyWebRtcDecoderFactory.shouldSetStandardLowLatencyKey(Build.VERSION_CODES.VANILLA_ICE_CREAM))
        assertFalse(LowLatencyWebRtcDecoderFactory.shouldSetStandardLowLatencyKey(Build.VERSION_CODES.Q))
    }
}
