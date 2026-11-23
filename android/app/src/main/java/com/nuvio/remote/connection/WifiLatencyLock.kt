package com.nuvio.remote.connection

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Holds a low-latency Wi-Fi lock (WIFI_MODE_FULL_LOW_LATENCY on Android 10+)
 * while the app is active in the foreground on LAN to prevent 80-100ms DTIM sleep spikes.
 */
class WifiLatencyLock(context: Context) {
    private val lock: WifiManager.WifiLock? = createWifiLock(context.applicationContext)

    fun setActive(enabled: Boolean) {
        val wifiLock = lock ?: return
        runCatching {
            if (enabled && !wifiLock.isHeld) {
                wifiLock.acquire()
            } else if (!enabled && wifiLock.isHeld) {
                wifiLock.release()
            }
        }
    }

    fun release() {
        setActive(false)
    }

    companion object {
        internal fun resolveWifiLockMode(sdkInt: Int): Int {
            return if (sdkInt >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
        }

        private fun createWifiLock(appContext: Context): WifiManager.WifiLock? {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val mode = resolveWifiLockMode(Build.VERSION.SDK_INT)
            return runCatching {
                wm.createWifiLock(mode, "nuvio:low_latency").apply {
                    setReferenceCounted(false)
                }
            }.getOrNull()
        }
    }
}
