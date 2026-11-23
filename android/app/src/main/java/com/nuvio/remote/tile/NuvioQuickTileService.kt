package com.nuvio.remote.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.nuvio.remote.MainActivity
import com.nuvio.remote.connection.NuvioWakeOnLan
import com.nuvio.remote.data.AppPrefs
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.i18n.resolveContextLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android Quick Settings tile shortcut.
 * Shows whether the PC is reachable on local Wi-Fi or sends Wake-on-LAN and opens Nuvio DeskRemote.
 */
class NuvioQuickTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pcReachable: Boolean = false

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val lang = resolveContextLanguage(this)
        val prefs = AppPrefs(this)
        val wakeInfo = prefs.savedWakeInfo()
        if (!pcReachable && wakeInfo != null && wakeInfo.macs.isNotEmpty()) {
            updateTile(state = Tile.STATE_ACTIVE, subtitle = l10n(lang, "Waking PC…", "Acordando PC…"))
            serviceScope.launch {
                val sent = NuvioWakeOnLan.sendWake(wakeInfo)
                updateTile(
                    state = if (sent) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
                    subtitle = if (sent) {
                        l10n(lang, "Wake-on-LAN sent", "Wake-on-LAN enviado")
                    } else {
                        l10n(lang, "Wake PC (WoL)", "Acordar PC (WoL)")
                    },
                )
            }
            launchMainApp()
        } else {
            launchMainApp()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun refreshTileState() {
        val lang = resolveContextLanguage(this)
        val prefs = AppPrefs(this)
        val host = prefs.savedLanHost
        val hasWake = prefs.savedWakeInfo()?.macs?.isNotEmpty() == true
        serviceScope.launch {
            val online = probeCompanionHealth(host)
            pcReachable = online
            val tileState = if (online) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            val subtitle = when {
                online -> l10n(lang, "Home • Online", "Casa • Ativo")
                hasWake -> l10n(lang, "Wake PC (WoL)", "Acordar PC (WoL)")
                else -> l10n(lang, "Open remote", "Abrir controle")
            }
            updateTile(state = tileState, subtitle = subtitle)
        }
    }

    private fun updateTile(state: Int, subtitle: String) {
        val tile = qsTile ?: return
        tile.label = "Nuvio DeskRemote"
        tile.state = state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private suspend fun probeCompanionHealth(host: String): Boolean = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext false
        runCatching {
            val conn = (URL("http://$host:8787/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = HEALTH_TIMEOUT_MS
                readTimeout = HEALTH_TIMEOUT_MS
                requestMethod = "GET"
            }
            try {
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val HEALTH_TIMEOUT_MS = 1_200
    }
}
