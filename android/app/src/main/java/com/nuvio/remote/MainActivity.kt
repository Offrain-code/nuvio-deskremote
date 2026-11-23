package com.nuvio.remote

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.remote.connection.ConnState
import com.nuvio.remote.connection.EndpointUrlResolver
import com.nuvio.remote.connection.NuvioConnection
import com.nuvio.remote.connection.NuvioTab
import com.nuvio.remote.connection.ScreenProfile
import com.nuvio.remote.connection.WifiLatencyLock
import com.nuvio.remote.data.AppPrefs
import com.nuvio.remote.discovery.PcService
import com.nuvio.remote.discovery.discoverUdp
import com.nuvio.remote.media.NowPlayingMediaBridge
import com.nuvio.remote.media.NowPlayingMediaService
import com.nuvio.remote.pairing.PairingManager
import com.nuvio.remote.ui.NuvioRemoteRoot
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.i18n.AppLanguage
import com.nuvio.remote.ui.i18n.AppLocaleState
import com.nuvio.remote.ui.i18n.resolveAppLanguage
import com.nuvio.remote.webrtc.ScreenStreamController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Color as AndroidColor

/**
 * Single Activity entry point.
 * Keeps the connection and WebRTC controller scoped to the Activity lifecycle and
 * automatically reconnects on resume.
 */
class MainActivity : ComponentActivity() {
    private val scope = MainScope()
    private lateinit var appPrefs: AppPrefs
    private lateinit var pairing: PairingManager
    private lateinit var conn: NuvioConnection
    private lateinit var controller: ScreenStreamController
    private lateinit var wifiLatencyLock: WifiLatencyLock
    private var activeTab: NuvioTab = NuvioTab.REMOTE

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        initializeServices()

        setContent {
            val initialTab = remember {
                if (!pairing.isPaired() && resolveInitialHost().isBlank()) NuvioTab.SETTINGS else NuvioTab.REMOTE
            }
            var tab by remember { mutableStateOf(initialTab) }
            activeTab = tab
            var hapticsEnabled by remember { mutableStateOf(appPrefs.hapticsEnabled) }
            var appLanguage by remember { mutableStateOf(AppLanguage.fromWire(appPrefs.appLanguage)) }
            val resolvedLanguage = resolveAppLanguage(appLanguage)
            AppLocaleState.current = resolvedLanguage
            var defaultProfile by remember {
                mutableStateOf(
                    ScreenProfile.entries.find { it.wire == appPrefs.screenProfile }
                        ?: ScreenProfile.P720_60,
                )
            }
            var discovered by remember { mutableStateOf<PcService?>(null) }

            LaunchedEffect(Unit) {
                conn.ensureConnected(resolveInitialHost())
                discoverInBackground { found ->
                    discovered = found
                    applyDiscoveredService(found)
                }
            }

            NuvioRemoteRoot(
                conn = conn,
                pairing = pairing,
                controller = controller,
                tab = tab,
                onTabChange = { tab = it },
                appLanguage = appLanguage,
                resolvedLanguage = resolvedLanguage,
                onAppLanguageChange = { next ->
                    appLanguage = next
                    appPrefs.appLanguage = next.wire
                    AppLocaleState.current = resolveAppLanguage(next)
                },
                hapticsEnabled = hapticsEnabled,
                onHapticsChange = {
                    hapticsEnabled = it
                    appPrefs.hapticsEnabled = it
                },
                defaultProfile = defaultProfile,
                onDefaultProfileChange = {
                    defaultProfile = it
                    appPrefs.screenProfile = it.wire
                },
                discovered = discovered,
                onRefreshDiscovery = {
                    refreshDiscovery { found -> discovered = found }
                },
                onConnect = ::connectToTarget,
            )
        }
    }

    private fun initializeServices() {
        appPrefs = AppPrefs(this)
        AppLocaleState.current = resolveAppLanguage(AppLanguage.fromWire(appPrefs.appLanguage))
        pairing = PairingManager(this)
        conn = NuvioConnection(
            authProvider = { pairing.savedToken },
            onTunnelUrlDiscovered = { tun -> appPrefs.savedTunnelUrl = tun },
            onTokenReceived = { tok -> pairing.savedToken = tok },
            initialTunnelUrl = appPrefs.savedTunnelUrl,
            initialScreenAudioEnabled = appPrefs.screenAudioEnabled,
        )
        conn.setPreferredLanHost(appPrefs.savedLanHost)
        controller = ScreenStreamController(conn)
        wifiLatencyLock = WifiLatencyLock(this)
        scope.launch {
            combine(
                conn.appInForegroundState,
                conn.connState,
                conn.isTunnelActive,
            ) { foreground, state, tunnel ->
                foreground && state is ConnState.Connected && !tunnel
            }.collect { active ->
                wifiLatencyLock.setActive(active)
            }
        }
        NowPlayingMediaBridge.onPlayPause = { conn.sendKey("Space") }
        NowPlayingMediaBridge.onSeekTo = { pos -> conn.bridge.seekTo(pos) }
        NowPlayingMediaBridge.onSeekDelta = { delta ->
            val current = conn.bridge.nowPlaying.value
            if (current != null && current.durationMs > 0L) {
                val target = (current.positionAt(System.currentTimeMillis()) + delta)
                    .coerceIn(0L, current.durationMs)
                conn.bridge.seekTo(target)
            } else {
                conn.sendKey(if (delta < 0L) "Left" else "Right")
            }
        }
        NowPlayingMediaBridge.onNext = { conn.sendKey("PageDown") }
        NowPlayingMediaBridge.onPrevious = { conn.sendKey("PageUp") }
        NowPlayingMediaBridge.onSetVolume = { vol -> conn.bridge.setVolume(vol) }
        scope.launch {
            conn.bridge.nowPlaying.collect { state ->
                NowPlayingMediaService.sync(this@MainActivity, state)
            }
        }
    }

    private fun resolveInitialHost(): String = pairing.savedHost?.trim().orEmpty()
        .ifEmpty { appPrefs.savedLanHost.ifBlank { DEFAULT_HOST } }

    private fun discoverInBackground(onFound: (PcService) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val found = discoverUdp().firstOrNull() ?: return@launch
            withContext(Dispatchers.Main) { onFound(found) }
        }
    }

    private fun applyDiscoveredService(found: PcService) {
        if (!shouldAutoConnectDiscovered(pairing.isPaired(), pairing.savedHost, pairing.savedServerId, found)) {
            return
        }
        appPrefs.savedLanHost = found.ip
        conn.setPreferredLanHost(found.ip)
        if (pairing.savedHost != found.ip) {
            pairing.savedHost = found.ip
            conn.connect(found.ip, found.port)
        }
    }

    private fun refreshDiscovery(onUpdated: (PcService?) -> Unit) {
        scope.launch {
            onUpdated(null)
            val found = withContext(Dispatchers.IO) { discoverUdp().firstOrNull() }
            onUpdated(found)
            if (found != null) {
                if (shouldAutoConnectDiscovered(pairing.isPaired(), pairing.savedHost, pairing.savedServerId, found)) {
                    appPrefs.savedLanHost = found.ip
                    conn.setPreferredLanHost(found.ip)
                    pairing.savedHost = found.ip
                    conn.connect(found.ip, found.port)
                }
            } else {
                val fallback = appPrefs.savedLanHost.ifBlank { DEFAULT_HOST }
                if (fallback.isNotBlank()) {
                    conn.connect(fallback, DEFAULT_PORT)
                }
            }
        }
    }

    private fun connectToTarget(host: String, port: Int) {
        val clean = host.trim()
        if (clean.isEmpty()) return
        val isRemoteUrl = EndpointUrlResolver.isRemoteTunnelHost(clean)
        if (!isRemoteUrl && EndpointUrlResolver.isPrivateLanHost(clean)) {
            appPrefs.savedLanHost = EndpointUrlResolver.extractHostOnly(clean)
            conn.setPreferredLanHost(appPrefs.savedLanHost)
        }
        pairing.savedHost = clean
        conn.connect(clean, port)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::conn.isInitialized && (activeTab == NuvioTab.REMOTE || activeTab == NuvioTab.SCREEN)) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (appPrefs.hapticsEnabled) Haptics.volume(this)
                    conn.sendKey("VolumeUp")
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (appPrefs.hapticsEnabled) Haptics.volume(this)
                    conn.sendKey("VolumeDown")
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        if (::conn.isInitialized) {
            conn.setAppForeground(true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::conn.isInitialized) {
            conn.ensureConnected(resolveInitialHost())
        }
    }

    override fun onStop() {
        if (::wifiLatencyLock.isInitialized) {
            wifiLatencyLock.setActive(false)
        }
        if (::conn.isInitialized) {
            conn.setAppForeground(false)
        }
        super.onStop()
    }

    override fun onDestroy() {
        NowPlayingMediaBridge.onPlayPause = null
        NowPlayingMediaBridge.onSeekTo = null
        NowPlayingMediaBridge.onSeekDelta = null
        NowPlayingMediaBridge.onNext = null
        NowPlayingMediaBridge.onPrevious = null
        NowPlayingMediaBridge.onSetVolume = null
        NowPlayingMediaService.sync(this, null)
        scope.cancel()
        if (::wifiLatencyLock.isInitialized) wifiLatencyLock.release()
        if (::controller.isInitialized) controller.release()
        if (::conn.isInitialized) conn.disconnect()
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_HOST = ""
        private const val DEFAULT_PORT = 8787

        /**
         * Prevents zero-click session token theft via spoofed UDP discovery replies on foreign Wi-Fi.
         * Auto-connects only when already paired AND either the IP matches the saved PC or the
         * discovered `server_id` matches the paired PC's `savedServerId`.
         */
        internal fun shouldAutoConnectDiscovered(
            isPaired: Boolean,
            savedHost: String?,
            savedServerId: String?,
            discovered: PcService,
        ): Boolean {
            if (!isPaired || !EndpointUrlResolver.isPrivateLanHost(discovered.ip)) return false
            val currentHost = savedHost?.trim().orEmpty()
            if (currentHost.isNotEmpty() && EndpointUrlResolver.extractHostOnly(currentHost) == discovered.ip) {
                return true
            }
            val expectedServerId = savedServerId?.trim().orEmpty()
            return expectedServerId.isNotEmpty() &&
                discovered.serverId.isNotBlank() &&
                discovered.serverId == expectedServerId
        }
    }
}
