package com.nuvio.remote.connection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Base64
import com.nuvio.remote.ui.i18n.l10nCurrent
import com.nuvio.remote.ui.theme.isBottomFrameRegionLight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

internal const val FRAME_LUMA_SAMPLE_INTERVAL_MS = 800L
internal const val HEARTBEAT_INITIAL_DELAY_MS = 350L
internal const val HEARTBEAT_SCREEN_LAN_MS = 1_500L
internal const val HEARTBEAT_IDLE_LAN_MS = 2_500L
internal const val HEARTBEAT_TUNNEL_MS = 5_000L

internal fun shouldSampleFrameLuma(prevElapsedMs: Long, nowElapsedMs: Long): Boolean =
    prevElapsedMs <= 0L || nowElapsedMs - prevElapsedMs >= FRAME_LUMA_SAMPLE_INTERVAL_MS

internal fun resolveHeartbeatIntervalMs(inForeground: Boolean, screenStatus: String, isTunnel: Boolean): Long {
    return when {
        isTunnel || !inForeground -> HEARTBEAT_TUNNEL_MS
        screenStatus == "running" || screenStatus == "starting" -> HEARTBEAT_SCREEN_LAN_MS
        else -> HEARTBEAT_IDLE_LAN_MS
    }
}

private class TcpNoDelaySocketFactory : SocketFactory() {
    private val delegate = getDefault()

    private fun tune(socket: Socket): Socket = socket.apply {
        runCatching { tcpNoDelay = true }
    }

    override fun createSocket(): Socket = tune(delegate.createSocket())
    override fun createSocket(host: String?, port: Int): Socket = tune(delegate.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        tune(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress?, port: Int): Socket = tune(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        tune(delegate.createSocket(address, port, localAddress, localPort))
}

/**
 * WebSocket client for the Nuvio DeskRemote Companion (PROTOCOL.md).
 * Includes automatic reconnection with exponential backoff, LAN -> Remote Tunnel fallback,
 * real-time PCM audio playback (AudioTrack), and stale-socket generation guards.
 */
sealed interface ConnState {
    data object Disconnected : ConnState
    data object Connecting : ConnState
    data class Connected(val host: String, val serverVersion: String) : ConnState
    data class Failed(val reason: String) : ConnState
}

class NuvioConnection(
    private val authProvider: () -> String = { "" },
    private val onTunnelUrlDiscovered: (String) -> Unit = {},
    private val onTokenReceived: (String) -> Unit = {},
    initialTunnelUrl: String = "",
    initialScreenAudioEnabled: Boolean = true,
) {
    private val client = OkHttpClient.Builder()
        .socketFactory(TcpNoDelaySocketFactory())
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketGen = AtomicInteger(0)

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var targetHost = ""

    @Volatile
    private var targetPort = 8787

    @Volatile
    private var preferredLanHost = ""

    @Volatile
    private var autoReconnect = false
    private var reconnectAttempt = 0

    @Volatile
    private var lastScreenStartKey = ""

    @Volatile
    private var lastScreenStartAt = 0L

    @Volatile
    private var lastPingSentAt = 0L

    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile
    private var udpSocket: java.net.DatagramSocket? = null

    @Volatile
    private var udpAddress: java.net.InetAddress? = null
    private val udpPort = 8768

    private val _isTunnelActive = MutableStateFlow(false)
    val isTunnelActive: StateFlow<Boolean> = _isTunnelActive

    @Volatile
    private var appInForeground = true
    private val _appInForeground = MutableStateFlow(true)
    val appInForegroundState: StateFlow<Boolean> = _appInForeground

    // Conflated frame decoder using a 3-bitmap ring buffer and inBitmap reuse
    @Volatile
    private var latestFramePacket: ByteString? = null
    private val frameDecoderRunning = AtomicBoolean(false)
    private val frameRing = arrayOfNulls<Bitmap>(3)
    private var frameRingIdx = 0

    // Real monitor dimensions reported by the server in welcome (used for local cursor prediction)
    private val monitorResolutions = java.util.concurrent.ConcurrentHashMap<Int, Pair<Float, Float>>()

    @Volatile
    private var activeMonitorIndex = 1

    // Timestamp of the last local pointer event to suppress stale server cursor echoes on high-latency links
    @Volatile
    private var lastLocalPointerMs: Long = 0L

    // Low-latency audio player (24,000 Hz, 16-bit PCM Stereo) + DSP LoudnessEnhancer gain
    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var audioTrackIsTunnel = false

    @Volatile
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private val audioLock = Any()

    private val _connState = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val connState: StateFlow<ConnState> = _connState

    private val _serverState = MutableStateFlow(NuvioScreenState.HOME)
    val serverState: StateFlow<NuvioScreenState> = _serverState

    private val _playerActive = MutableStateFlow(false)
    val playerActive: StateFlow<Boolean> = _playerActive

    /** Log-backed confirmation (PROTOCOL section 2): true when PlaybackStartTrace is confirmed on PC. */
    private val _stateConfirmed = MutableStateFlow(false)
    val stateConfirmed: StateFlow<Boolean> = _stateConfirmed

    private val _screenStatus = MutableStateFlow("stopped")
    val screenStatus: StateFlow<String> = _screenStatus

    /** Real-time decoded MJPEG fallback frame for the Screen tab. */
    private val _screenFrame = MutableStateFlow<Bitmap?>(null)
    val screenFrame: StateFlow<Bitmap?> = _screenFrame

    /** Indicates whether a fallback frame is active without recomposing on every 60fps bitmap emission. */
    private val _hasScreenFrame = MutableStateFlow(false)
    val hasScreenFrame: StateFlow<Boolean> = _hasScreenFrame

    /** Bottom-region luminance computed off the UI thread with an 800 ms throttle. */
    private val _isFrameBottomLight = MutableStateFlow(false)
    val isFrameBottomLight: StateFlow<Boolean> = _isFrameBottomLight

    private val lastFrameLumaCheckMs = AtomicLong(0L)

    /** Actual aspect ratio of the streamed display (updated when monitor/resolution changes). */
    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect: StateFlow<Float> = _videoAspect

    /** WebRTC SDP answer received from the Companion server (Screen tab). */
    private val _answerSdp = MutableStateFlow<String?>(null)
    val answerSdp: StateFlow<String?> = _answerSdp

    /** Normalized cursor coordinates (0..1) rendered locally at 120Hz and synced with the PC. */
    private val _remoteCursor = MutableStateFlow<Triple<Float, Float, Boolean>>(Triple(0.5f, 0.5f, true))
    val remoteCursor: StateFlow<Triple<Float, Float, Boolean>> = _remoteCursor

    /** Keeps the zero-latency local cursor overlay enabled (`draw_mouse=0` on the server). */
    private val _needFallbackCursor = MutableStateFlow(true)
    val needFallbackCursor: StateFlow<Boolean> = _needFallbackCursor

    /** Counter incremented whenever the user clicks a text input field (I-Beam cursor) on the PC. */
    private val _textInputFocusTick = MutableStateFlow(0L)
    val textInputFocusTick: StateFlow<Long> = _textInputFocusTick

    /** Optional Cloudflare Quick Tunnel URL for remote access outside home Wi-Fi. */
    private val _tunnelUrl = MutableStateFlow<String?>(initialTunnelUrl.takeIf { it.isNotBlank() })
    val tunnelUrl: StateFlow<String?> = _tunnelUrl

    /** Whether live PC audio playback is enabled during Screen Share. */
    private val _screenAudioEnabled = MutableStateFlow(initialScreenAudioEnabled)
    val screenAudioEnabled: StateFlow<Boolean> = _screenAudioEnabled

    /** Measured WebSocket RTT latency in milliseconds via ping/pong. */
    private val _pingMs = MutableStateFlow(0)
    val pingMs: StateFlow<Int> = _pingMs

    /** Latest text retrieved from the PC clipboard. */
    private val _remoteClipboard = MutableStateFlow("")
    val remoteClipboard: StateFlow<String> = _remoteClipboard

    /** Counter signalling arrival of a new PC clipboard payload. */
    private val _clipboardUpdatedTick = MutableStateFlow(0L)
    val clipboardUpdatedTick: StateFlow<Long> = _clipboardUpdatedTick

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /** Native bridge to Nuvio HTPC (Now Playing, subtitles, audio, sources, queue, catalog). */
    val bridge: NuvioBridgeStateHolder = NuvioBridgeStateHolder(::send)

    fun setPreferredLanHost(host: String) {
        val clean = host.trim()
        if (clean.isNotEmpty() && !isRemoteTunnelHost(clean)) {
            preferredLanHost = clean
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    fun clearAnswerSdp() {
        _answerSdp.value = null
    }

    fun setScreenAudioEnabled(enabled: Boolean) {
        _screenAudioEnabled.value = enabled
        if (!enabled) {
            stopAudioTrack()
        }
        send("screen_audio") {
            put("enabled", enabled)
        }
    }

    private fun ensureAudioTrack(): AudioTrack? {
        val isTunnel = _isTunnelActive.value
        val existing = audioTrack
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED && audioTrackIsTunnel == isTunnel) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { existing.play() }
            }
            return existing
        }
        synchronized(audioLock) {
            val again = audioTrack
            if (again != null && again.state == AudioTrack.STATE_INITIALIZED && audioTrackIsTunnel == isTunnel) {
                return again
            }
            if (again != null) {
                runCatching {
                    loudnessEnhancer?.enabled = false
                    loudnessEnhancer?.release()
                }
                loudnessEnhancer = null
                audioTrack = null
                runCatching {
                    again.pause()
                    again.flush()
                    again.release()
                }
            }
            return runCatching {
                val sampleRate = 24000
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(3840)
                // LAN: 4x (~160ms) low latency; 4G/5G tunnel: 6x (~240ms) to absorb jitter without crackles
                val bufSize = if (isTunnel) {
                    (minBuf * 6).coerceAtLeast(24576)
                } else {
                    (minBuf * 4).coerceAtLeast(15360)
                }
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isTunnel) {
                    builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                val track = builder.build()
                runCatching { track.setVolume(AudioTrack.getMaxVolume()) }
                runCatching {
                    loudnessEnhancer?.release()
                    loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(track.audioSessionId).apply {
                        setTargetGain(1200) // +12 dB DSP loudness boost on Android
                        enabled = true
                    }
                }
                track.play()
                audioTrackIsTunnel = isTunnel
                audioTrack = track
                track
            }.getOrNull()
        }
    }

    private fun stopAudioTrack() {
        synchronized(audioLock) {
            runCatching {
                loudnessEnhancer?.enabled = false
                loudnessEnhancer?.release()
            }
            loudnessEnhancer = null
            val t = audioTrack
            audioTrack = null
            if (t != null) {
                runCatching {
                    t.pause()
                    t.flush()
                    t.release()
                }
            }
        }
    }

    private fun isRemoteTunnelHost(host: String): Boolean = EndpointUrlResolver.isRemoteTunnelHost(host)

    private fun buildWsUrl(host: String, port: Int): String? = EndpointUrlResolver.buildWsUrl(host, port)

    private fun displayHost(host: String): String {
        val clean = EndpointUrlResolver.stripSchemeAndSlash(host)
        return if (clean.endsWith(".trycloudflare.com")) {
            "${l10nCurrent("Remote", "Remoto")} • " + clean.removeSuffix(".trycloudflare.com").take(12)
        } else {
            clean
        }
    }

    fun connect(host: String, port: Int = 8787) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return
        if (!isRemoteTunnelHost(cleanHost)) {
            preferredLanHost = cleanHost
        }
        targetHost = cleanHost
        targetPort = port
        autoReconnect = true
        reconnectAttempt = 0
        reconnectJob?.cancel()
        openSocket(cleanHost, port)
    }

    fun ensureConnected(fallbackHost: String = "") {
        if (!appInForeground) return
        val cur = _connState.value
        if (cur is ConnState.Connected || cur is ConnState.Connecting) return
        val host = if (targetHost.isNotBlank()) targetHost else fallbackHost
        if (host.isNotBlank()) {
            if (!isRemoteTunnelHost(host)) {
                preferredLanHost = host
            }
            targetHost = host
            autoReconnect = true
            reconnectAttempt = 0
            reconnectJob?.cancel()
            openSocket(targetHost, targetPort)
        }
    }

    fun setAppForeground(inForeground: Boolean) {
        appInForeground = inForeground
        _appInForeground.value = inForeground
        if (!inForeground) {
            reconnectJob?.cancel()
            reconnectJob = null
            if (_screenStatus.value != "stopped") {
                screenStop()
            }
        } else if (autoReconnect && targetHost.isNotBlank()) {
            ensureConnected(targetHost)
        }
    }

    private fun openSocket(host: String, port: Int) {
        val myGen = socketGen.incrementAndGet()
        heartbeatJob?.cancel()
        ws?.let { old ->
            runCatching { old.close(1000, "reconnecting") }
        }
        ws = null
        _connState.value = ConnState.Connecting
        _lastError.value = null

        val token = runCatching { authProvider() }.getOrDefault("")
        val wsUrl = buildWsUrl(host, port)
        if (wsUrl == null) {
            _connState.value = ConnState.Failed(
                l10nCurrent("Invalid PC address", "Endereço do PC inválido"),
            )
            return
        }
        val request = runCatching {
            val builder = Request.Builder().url(wsUrl)
            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            builder.build()
        }.getOrElse {
            _connState.value = ConnState.Failed(
                l10nCurrent("Invalid PC address", "Endereço do PC inválido"),
            )
            return
        }

        val isTunnel = isRemoteTunnelHost(host)
        _isTunnelActive.value = isTunnel

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socketGen.get() != myGen) {
                    runCatching { webSocket.close(1000, "superseded") }
                    return
                }
                reconnectAttempt = 0
                val tok = runCatching { authProvider() }.getOrDefault("")
                webSocket.send(
                    JSONObject()
                        .put("type", "hello")
                        .put("name", "Nuvio DeskRemote Android")
                        .put("auth", tok)
                        .toString(),
                )
                webSocket.send(JSONObject().put("type", "get_state").toString())
                startHeartbeat(webSocket, myGen)
                scope.launch(Dispatchers.IO) {
                    if (isTunnel) {
                        // Over remote tunnel (4G/5G), always use binary WebSocket packets for mouse input
                        runCatching { udpSocket?.close() }
                        udpSocket = null
                        udpAddress = null
                    } else {
                        runCatching {
                            val clean = host.trim()
                                .removePrefix("ws://")
                                .removePrefix("http://")
                                .substringBefore(':')
                            udpAddress = java.net.InetAddress.getByName(clean)
                            runCatching { udpSocket?.close() }
                            udpSocket = java.net.DatagramSocket().apply {
                                trafficClass = 0x10 // IPTOS_LOWDELAY
                                sendBufferSize = 8192
                            }
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socketGen.get() != myGen) return
                handleServerMessage(displayHost(host), text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (socketGen.get() != myGen) return
                val size = bytes.size
                if (size < 6) return
                val type = bytes[0].toInt() and 0xFF
                // Packet 0x06: Windows PCM audio (24kHz Stereo s16le): [0]=6, [1]=1, [2..3]=samples, [4..]=PCM
                if (type == 6 && size > 4) {
                    if (_screenAudioEnabled.value) {
                        val pcm = bytes.toByteArray()
                        val track = ensureAudioTrack()
                        if (track != null) {
                            runCatching {
                                track.write(pcm, 4, pcm.size - 4, AudioTrack.WRITE_NON_BLOCKING)
                            }
                        }
                    }
                    return
                }
                // Packet 0x05: server detected a click inside a text input (I-Beam cursor) -> open soft keyboard
                if (type == 5) {
                    if ((bytes[1].toInt() and 0xFF) == 1) {
                        _textInputFocusTick.value = System.currentTimeMillis()
                    }
                    return
                }
                // Packet 0x04: fast Windows cursor update [0]=4 [1..2]=cx [3..4]=cy [5]=vis
                if (type == 4) {
                    val antiEchoMs = if (_isTunnelActive.value) 180L else 70L
                    if (android.os.SystemClock.elapsedRealtime() - lastLocalPointerMs > antiEchoMs) {
                        val cxRaw = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
                        val cyRaw = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
                        val curVis = (bytes[5].toInt() and 0xFF) == 1
                        _remoteCursor.value = Triple(cxRaw / 65535f, cyRaw / 65535f, curVis)
                    }
                    return
                }
                // MJPEG frame: [0]=1 [1]=mon [2..3]=w [4..5]=h [6..7]=cx [8..9]=cy [10]=vis [11]=flags [12..]=JPEG
                if (type == 1 && size > 12) {
                    val mon = bytes[1].toInt() and 0xFF
                    if (mon in 0..4) activeMonitorIndex = mon
                    val antiEchoMs = if (_isTunnelActive.value) 200L else 85L
                    if (android.os.SystemClock.elapsedRealtime() - lastLocalPointerMs > antiEchoMs) {
                        val cxRaw = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
                        val cyRaw = ((bytes[8].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF)
                        val curVis = (bytes[10].toInt() and 0xFF) == 1
                        _remoteCursor.value = Triple(cxRaw / 65535f, cyRaw / 65535f, curVis)
                    }
                    _needFallbackCursor.value = true

                    latestFramePacket = bytes
                    startFrameDecoderLoop()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runCatching { webSocket.close(code, reason) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socketGen.get() != myGen) return
                heartbeatJob?.cancel()
                stopAudioTrack()
                latestFramePacket = null
                publishScreenFrame(null)
                bridge.resetOnDisconnect()
                lastScreenStartKey = ""
                _connState.value = ConnState.Disconnected
                if (code == 1008) {
                    autoReconnect = false
                    _lastError.value = l10nCurrent(
                        "Pairing required or revoked. Pair this phone again in Settings.",
                        "Pareamento revogado ou necessário. Pareie este celular novamente em Ajustes.",
                    )
                    return
                }
                if (autoReconnect && code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socketGen.get() != myGen) return
                heartbeatJob?.cancel()
                stopAudioTrack()
                latestFramePacket = null
                publishScreenFrame(null)
                bridge.resetOnDisconnect()
                lastScreenStartKey = ""
                _connState.value = ConnState.Failed(
                    t.message ?: l10nCurrent("connection failed", "falha de conexão"),
                )
                if (autoReconnect) {
                    scheduleReconnect()
                }
            }
        }

        ws = client.newWebSocket(request, listener)
    }

    private fun startFrameDecoderLoop() {
        if (!frameDecoderRunning.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            try {
                while (true) {
                    val pkt = latestFramePacket ?: break
                    latestFramePacket = null
                    val raw = pkt.toByteArray()
                    if (raw.size > 12) {
                        val slot = frameRingIdx
                        val targetBuf = frameRing[slot]
                        val decodeOpts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                            inMutable = true
                            if (targetBuf != null && !targetBuf.isRecycled) {
                                inBitmap = targetBuf
                            }
                        }
                        val bmp = runCatching {
                            BitmapFactory.decodeByteArray(raw, 12, raw.size - 12, decodeOpts)
                        }.getOrNull() ?: runCatching {
                            // If dimensions changed (e.g., monitor switch), allocate a fresh buffer for this ring slot
                            val fallbackOpts = BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.RGB_565
                                inMutable = true
                            }
                            BitmapFactory.decodeByteArray(raw, 12, raw.size - 12, fallbackOpts)
                        }.getOrNull()

                        if (bmp != null) {
                            frameRing[slot] = bmp
                            frameRingIdx = (slot + 1) % 3
                            if (bmp.width > 0 && bmp.height > 0) {
                                val nextAspect = bmp.width.toFloat() / bmp.height.toFloat()
                                if (kotlin.math.abs(_videoAspect.value - nextAspect) > 0.005f) {
                                    _videoAspect.value = nextAspect
                                }
                            }
                            publishScreenFrame(bmp)
                        }
                    }
                    if (latestFramePacket == null) break
                }
            } finally {
                frameDecoderRunning.set(false)
                if (latestFramePacket != null) {
                    startFrameDecoderLoop()
                }
            }
        }
    }

    private fun startHeartbeat(webSocket: WebSocket, gen: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay(HEARTBEAT_INITIAL_DELAY_MS)
            while (isActive && socketGen.get() == gen) {
                if (socketGen.get() == gen) {
                    val ts = System.currentTimeMillis()
                    lastPingSentAt = ts
                    val sent = webSocket.send(
                        JSONObject().put("type", "ping").put("clientTs", ts).toString(),
                    )
                    if (!sent) break
                }
                delay(
                    resolveHeartbeatIntervalMs(
                        inForeground = appInForeground,
                        screenStatus = _screenStatus.value,
                        isTunnel = _isTunnelActive.value,
                    ),
                )
            }
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnect || !appInForeground) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val savedTun = _tunnelUrl.value?.trim()?.takeIf { it.isNotBlank() }
            val lanHost = preferredLanHost.trim()
            val hostToUse = targetHost.ifBlank { lanHost }
            if (hostToUse.isBlank() && savedTun == null) return@launch
            val nextAttempt = reconnectAttempt++
            delay(computeReconnectDelayMs(nextAttempt, hostToUse, savedTun))
            if (!autoReconnect || !appInForeground) return@launch
            val st = _connState.value
            if (st is ConnState.Connected || st is ConnState.Connecting) return@launch
            val (nextHost, nextPort) = resolveReconnectTarget(nextAttempt, hostToUse, lanHost, savedTun)
                ?: return@launch
            openSocket(nextHost, nextPort)
        }
    }

    private fun computeReconnectDelayMs(attempt: Int, hostToUse: String, savedTun: String?): Long {
        val preferQuickTunnelFallback = attempt == 0 && savedTun != null && isLanHost(hostToUse)
        if (preferQuickTunnelFallback) return 1200L
        val delays = longArrayOf(2000L, 4500L, 9000L, 18000L)
        return delays[attempt.coerceAtMost(delays.lastIndex)]
    }

    private fun resolveReconnectTarget(
        attempt: Int,
        hostToUse: String,
        lanHost: String,
        savedTun: String?,
    ): Pair<String, Int>? {
        val alternateToOtherRoute = savedTun != null && attempt % 2 == 1
        val firstFallbackToTunnel = attempt == 0 && savedTun != null && isLanHost(hostToUse)
        return when {
            alternateToOtherRoute && isRemoteTunnelHost(hostToUse) && lanHost.isNotBlank() -> lanHost to 8787
            alternateToOtherRoute || firstFallbackToTunnel -> savedTun?.let { it to 443 }
            hostToUse.isNotBlank() -> hostToUse to targetPort
            else -> savedTun?.let { it to 443 }
        }
    }

    private fun isLanHost(host: String): Boolean = host.isNotBlank() && !isRemoteTunnelHost(host)

    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        socketGen.incrementAndGet()
        stopAudioTrack()
        ws?.close(1000, "app closing")
        ws = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        udpAddress = null
        publishScreenFrame(null)
        bridge.resetOnDisconnect()
        _connState.value = ConnState.Disconnected
    }

    private fun publishScreenFrame(bmp: Bitmap?) {
        if (bmp == null) {
            _screenFrame.value = null
            _hasScreenFrame.value = false
            _isFrameBottomLight.value = false
            lastFrameLumaCheckMs.set(0L)
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = lastFrameLumaCheckMs.get()
        if (shouldSampleFrameLuma(prev, now) && lastFrameLumaCheckMs.compareAndSet(prev, now)) {
            _isFrameBottomLight.value = isBottomFrameRegionLight(bmp)
        }
        _screenFrame.value = bmp
        if (!_hasScreenFrame.value) {
            _hasScreenFrame.value = true
        }
    }

    // ---------- Outbound commands (PROTOCOL.md §3) ----------

    fun sendNav(action: String) = send("nav") { put("action", action) }
    fun sendKey(name: String, repeat: Int = 1) = send("key") {
        put("name", name)
        if (repeat > 1) put("repeat", repeat)
    }
    fun sendType(text: String) = send("type") { put("text", text) }

    private val pendingUdpDx = AtomicInteger(0)
    private val pendingUdpDy = AtomicInteger(0)

    // Packs (monitor shl 32) or (ix shl 16) or iy, or -1L when no absolute move is pending
    private val pendingAbsPacked = AtomicLong(-1L)
    private val pointerSignal = Semaphore(0)
    private val pointerWorkerStarted = AtomicBoolean(false)

    private fun ensurePointerWorkerStarted() {
        if (!pointerWorkerStarted.compareAndSet(false, true)) return
        Thread(
            {
                val relBuf = ByteArray(5).apply { this[0] = 0x02 }
                val absBuf = ByteArray(6).apply { this[0] = 0x03 }
                val relPacket = DatagramPacket(relBuf, 5)
                val absPacket = DatagramPacket(absBuf, 6)
                while (!Thread.currentThread().isInterrupted) {
                    if (runCatching { pointerSignal.acquire() }.isFailure) break
                    pointerSignal.drainPermits()
                    drainPendingPointers(relBuf, absBuf, relPacket, absPacket)
                }
            },
            "nuvio-pointer-sender",
        ).apply { isDaemon = true }.start()
    }

    private fun drainPendingPointers(
        relBuf: ByteArray,
        absBuf: ByteArray,
        relPacket: DatagramPacket,
        absPacket: DatagramPacket,
    ) {
        while (true) {
            var didWork = false
            val sx = pendingUdpDx.getAndSet(0).coerceIn(-4000, 4000)
            val sy = pendingUdpDy.getAndSet(0).coerceIn(-4000, 4000)
            if (sx != 0 || sy != 0) {
                didWork = true
                relBuf[1] = ((sx shr 8) and 0xFF).toByte()
                relBuf[2] = (sx and 0xFF).toByte()
                relBuf[3] = ((sy shr 8) and 0xFF).toByte()
                relBuf[4] = (sy and 0xFF).toByte()
                dispatchPointerPacket(relBuf, relPacket, 5)
            }

            val absVal = pendingAbsPacked.getAndSet(-1L)
            if (absVal != -1L) {
                didWork = true
                val mon = ((absVal ushr 32) and 0xFF).toInt()
                val ix = ((absVal ushr 16) and 0xFFFF).toInt()
                val iy = (absVal and 0xFFFF).toInt()
                absBuf[1] = mon.toByte()
                absBuf[2] = ((ix shr 8) and 0xFF).toByte()
                absBuf[3] = (ix and 0xFF).toByte()
                absBuf[4] = ((iy shr 8) and 0xFF).toByte()
                absBuf[5] = (iy and 0xFF).toByte()
                dispatchPointerPacket(absBuf, absPacket, 6)
            }

            if (!didWork) break
            // In tunnel/TCP mode, batch micro-deltas in 6ms windows (~160Hz) for smooth motion without TCP congestion
            if (_isTunnelActive.value || udpSocket == null || udpAddress == null) {
                runCatching { Thread.sleep(6L) }
            }
        }
    }

    private fun dispatchPointerPacket(buf: ByteArray, packet: DatagramPacket, length: Int) {
        val sock = udpSocket
        val addr = udpAddress
        if (sock != null && addr != null && !sock.isClosed) {
            packet.setAddress(addr)
            packet.setPort(udpPort)
            packet.setLength(length)
            runCatching { sock.send(packet) }
        } else {
            ws?.send(buf.toByteString(0, length))
        }
    }

    private fun triggerPointerFlush() {
        ensurePointerWorkerStarted()
        if (pointerSignal.availablePermits() == 0) {
            pointerSignal.release()
        }
    }

    /**
     * Sends zero-delay relative mouse motion with local prediction using real monitor dimensions:
     * - On LAN (Wi-Fi): dispatches immediately via UDP (port 8768, 120 Hz);
     * - Over Tunnel (4G/5G): coalesces deltas in short windows to keep TCP uncongested.
     */
    fun sendMouseMove(dx: Int, dy: Int): Boolean {
        if (dx == 0 && dy == 0) return true
        lastLocalPointerMs = android.os.SystemClock.elapsedRealtime()
        if (_screenStatus.value == "running") {
            val cur = _remoteCursor.value
            val dims = monitorResolutions[activeMonitorIndex]
                ?: monitorResolutions[1]
                ?: Pair(1920f, 1080f)
            val nx = (cur.first + dx / dims.first).coerceIn(0f, 1f)
            val ny = (cur.second + dy / dims.second).coerceIn(0f, 1f)
            _remoteCursor.value = Triple(nx, ny, true)
        }
        pendingUdpDx.addAndGet(dx)
        pendingUdpDy.addAndGet(dy)
        triggerPointerFlush()
        return true
    }

    fun sendMouseAbs(
        nx: Float,
        ny: Float,
        monitor: Int = 1,
        click: MouseButtons? = null,
        doubleClick: MouseButtons? = null,
        down: MouseButtons? = null,
        up: MouseButtons? = null,
    ): Boolean {
        val cnx = nx.coerceIn(0f, 1f)
        val cny = ny.coerceIn(0f, 1f)
        lastLocalPointerMs = android.os.SystemClock.elapsedRealtime()
        // Immediately update the local cursor overlay in the app for instant 120 FPS visual feedback
        _remoteCursor.value = Triple(cnx, cny, true)
        // For pure pointer movement (finger drag without clicks), coalesce and dispatch via UDP (LAN) or binary WS (4G)
        if (click == null && doubleClick == null && down == null && up == null) {
            val ix = (cnx * 65535f).toInt().coerceIn(0, 65535)
            val iy = (cny * 65535f).toInt().coerceIn(0, 65535)
            val mon = monitor.coerceIn(1, 4).toLong()
            val packed = (mon shl 32) or (ix.toLong() shl 16) or iy.toLong()
            pendingAbsPacked.set(packed)
            triggerPointerFlush()
            return true
        }
        pendingAbsPacked.set(-1L)
        val currentWs = ws ?: return false
        val sb = java.lang.StringBuilder(128)
        sb.append("""{"type":"mouse","abs":{"nx":$cnx,"ny":$cny,"monitor":$monitor}""")
        click?.let { sb.append(""","click":"${it.name.lowercase()}"""") }
        doubleClick?.let { sb.append(""","doubleClick":"${it.name.lowercase()}"""") }
        down?.let { sb.append(""","down":"${it.name.lowercase()}"""") }
        up?.let { sb.append(""","up":"${it.name.lowercase()}"""") }
        sb.append("}")
        return currentWs.send(sb.toString())
    }

    fun sendMouseDoubleClick(button: MouseButtons) = send("mouse") { put("doubleClick", button.name.lowercase()) }

    fun sendMouseClick(button: MouseButtons) = send("mouse") { put("click", button.name.lowercase()) }
    fun sendMouseHold(button: MouseButtons, down: Boolean) =
        send("mouse") { put(if (down) "down" else "up", button.name.lowercase()) }
    fun sendMouseScroll(delta: Int) = send("mouse") { put("scroll", delta) }
    fun sendSetState(screen: NuvioScreenState) = send("state") { put("screen", screen.name.lowercase()) }
    fun ping() {
        val ts = System.currentTimeMillis()
        lastPingSentAt = ts
        send("ping") { put("clientTs", ts) }
    }

    fun sendClipboardSet(text: String) = send("clipboard_set") { put("text", text) }
    fun requestClipboardGet() = send("clipboard_get")

    fun screenStart(profile: ScreenProfile, monitor: Int = 1, offerSdp: String? = null): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val key = "${profile.wire}|$monitor"
        val curStatus = _screenStatus.value
        if (offerSdp.isNullOrBlank() &&
            key == lastScreenStartKey &&
            (curStatus == "starting" || curStatus == "running") &&
            now - lastScreenStartAt < 300L
        ) {
            return true
        }
        lastScreenStartKey = key
        lastScreenStartAt = now
        activeMonitorIndex = monitor
        _needFallbackCursor.value = true
        val cur = _remoteCursor.value
        if (!cur.third) {
            _remoteCursor.value = Triple(cur.first, cur.second, true)
        }
        monitorResolutions[monitor]?.let { (mw, mh) ->
            if (mw > 0f && mh > 0f) {
                _videoAspect.value = mw / mh
            }
        }
        return send("screen_start") {
            put("profile", profile.wire)
            put("monitor", monitor)
            put("audio", _screenAudioEnabled.value)
            if (!offerSdp.isNullOrBlank()) {
                put("offer", offerSdp)
            }
        }
    }
    fun clearScreenFrame() {
        latestFramePacket = null
        publishScreenFrame(null)
    }

    fun sendWebRtcLive(live: Boolean): Boolean = send("screen_webrtc_state") {
        put("live", live)
    }

    fun screenStop(): Boolean {
        lastScreenStartKey = ""
        latestFramePacket = null
        publishScreenFrame(null)
        _needFallbackCursor.value = false
        _screenStatus.value = "stopped"
        stopAudioTrack()
        val currentWs = ws
        if (currentWs == null || _connState.value !is ConnState.Connected) return false
        return currentWs.send(JSONObject().put("type", "screen_stop").toString())
    }

    /** Sends a trickle ICE candidate from the app to the server. */
    fun sendIceCandidate(candidate: String, mid: String) = send("webrtc_candidate") {
        put("candidate", candidate)
        put("mid", mid)
    }

    /** Win32 window control actions (Apps / Screen tabs). */
    fun sendWindow(action: String) = send("window") { put("action", action) }
    fun sendWindowAction(action: String) = sendWindow(action)

    private fun send(type: String, body: JSONObject.() -> Unit = {}): Boolean {
        val currentWs = ws
        if (currentWs == null || _connState.value !is ConnState.Connected) {
            if (autoReconnect && targetHost.isNotBlank()) {
                ensureConnected()
            }
            return false
        }
        val obj = JSONObject()
        obj.put("type", type)
        body(obj)
        return currentWs.send(obj.toString())
    }

    private fun handleServerMessage(host: String, text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = obj.optString("type")
        if (bridge.handleServerMessage(type, obj)) return
        when (type) {
            "welcome" -> {
                _lastError.value = null
                _connState.value = ConnState.Connected(host, obj.optString("version", "?"))
                val tun = obj.optString("tunnelUrl", "").trim()
                if (tun.isNotEmpty() && tun != "null" && EndpointUrlResolver.isAllowedTunnelUrl(tun)) {
                    _tunnelUrl.value = tun
                    onTunnelUrlDiscovered(tun)
                }
                val currentTok = runCatching { authProvider() }.getOrDefault("")
                val paired = obj.optString("pairedToken", "").trim()
                if (currentTok.isBlank() && paired.startsWith("nuvio_sec_") && paired.length >= 32) {
                    onTokenReceived(paired)
                }
                obj.optJSONArray("monitors")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val idx = m.optInt("index", -1)
                        val mw = m.optDouble("width", 0.0).toFloat()
                        val mh = m.optDouble("height", 0.0).toFloat()
                        if (idx >= 0 && mw > 0f && mh > 0f) {
                            monitorResolutions[idx] = Pair(mw, mh)
                        }
                    }
                    monitorResolutions[activeMonitorIndex]?.let { (mw, mh) ->
                        if (mw > 0f && mh > 0f) _videoAspect.value = mw / mh
                    }
                }
                obj.optJSONObject("state")?.let { st ->
                    applyScreen(st.optString("screen", "home"), st.optBoolean("confirmed", false))
                }
            }
            "tunnel" -> {
                val tun = obj.optString("url", "").trim()
                if (tun.isEmpty() || tun == "null") {
                    _tunnelUrl.value = null
                    onTunnelUrlDiscovered("")
                } else if (EndpointUrlResolver.isAllowedTunnelUrl(tun)) {
                    _tunnelUrl.value = tun
                    onTunnelUrlDiscovered(tun)
                }
            }
            "pong" -> {
                val echoedTs = obj.optLong("clientTs", 0L)
                val refTs = if (echoedTs > 0L) echoedTs else lastPingSentAt
                if (refTs > 0L) {
                    val rtt = (System.currentTimeMillis() - refTs).toInt().coerceIn(1, 9999)
                    _pingMs.value = rtt
                }
            }
            "clipboard_data" -> {
                _remoteClipboard.value = obj.optString("text", "")
                _clipboardUpdatedTick.value = System.currentTimeMillis()
            }
            "state" -> {
                _lastError.value = null
                applyScreen(
                    obj.optString("screen", "home"),
                    obj.optBoolean("confirmed", false),
                )
            }
            "screen_status" -> handleScreenStatus(obj)
            "screen_frame" -> {
                val b64 = obj.optString("data", "")
                if (b64.isNotEmpty()) {
                    runCatching {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            publishScreenFrame(bmp)
                            _screenStatus.value = "running"
                        }
                    }
                }
            }
            "webrtc_answer" -> _answerSdp.value = obj.optString("sdp")
            "error" ->
                _lastError.value =
                    "${obj.optString("code", "ERROR")} — ${obj.optString("message")}"
        }
    }

    private fun applyScreen(screen: String, confirmed: Boolean) {
        _serverState.value = when (screen.lowercase()) {
            "home" -> NuvioScreenState.HOME
            "detail" -> NuvioScreenState.DETAIL
            "player" -> NuvioScreenState.PLAYER
            "search" -> NuvioScreenState.SEARCH
            else -> NuvioScreenState.UNKNOWN
        }
        _playerActive.value = _serverState.value == NuvioScreenState.PLAYER
        _stateConfirmed.value = confirmed
    }

    private fun handleScreenStatus(obj: JSONObject) {
        val st = obj.optString("status", "stopped")
        _screenStatus.value = st
        if (st == "running" || st == "starting") {
            _needFallbackCursor.value = true
        }
        if (st == "stopped") {
            latestFramePacket = null
            publishScreenFrame(null)
            _needFallbackCursor.value = false
            stopAudioTrack()
        }
    }
}
