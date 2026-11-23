package com.nuvio.remote.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nuvio.remote.connection.NuvioConnection
import com.nuvio.remote.connection.ScreenProfile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.LowLatencyWebRtcDecoderFactory
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebRTC flow for the Screen tab (SPEC section 5):
 * creates a recvonly offer, sends screen_start over WS, consumes webrtc_answer, and
 * renders the Companion H.264 stream onto SurfaceViewRenderer.
 * Lifecycle: starts only when entering the Screen tab; stops in onDispose.
 */
class ScreenStreamController(private val conn: NuvioConnection) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rendererLock = Any()
    private val firstFrameNotified = AtomicBoolean(false)
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var egl: EglBase? = null
    private var videoTrack: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private var activeProfile: ScreenProfile? = null
    private var activeMonitor: Int = 1
    private var activeRestartTick: Int = -1
    private val initializedRenderers = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<SurfaceViewRenderer, Boolean>(),
    )

    val status = MutableStateFlow("idle") // idle | offering | waiting_answer | live | closed
    val hasFirstFrame = MutableStateFlow(false)

    private val frameForwardingSink = VideoSink { frame: VideoFrame ->
        val target = synchronized(rendererLock) { renderer }
        target?.onFrame(frame)
        if (firstFrameNotified.compareAndSet(false, true)) {
            mainHandler.post {
                hasFirstFrame.value = true
                conn.clearScreenFrame()
                conn.sendWebRtcLive(true)
            }
        }
    }

    fun initialize(context: Context) {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions(),
        )
        egl = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(LowLatencyWebRtcDecoderFactory.create(egl!!.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun initSurfaceOnce(surface: SurfaceViewRenderer) {
        if (initializedRenderers.add(surface)) {
            runCatching {
                surface.init(egl!!.eglBaseContext, null)
                surface.setEnableHardwareScaler(true)
                surface.setZOrderMediaOverlay(true)
            }.onFailure {
                initializedRenderers.remove(surface)
            }
        }
    }

    /**
     * Reattaches only the SurfaceView when toggling Portrait <-> Fullscreen without restarting
     * the WebRTC session or PC encoder; restarts only on profile/monitor change or reconnect.
     */
    fun attachOrStart(
        context: Context,
        profile: ScreenProfile,
        surface: SurfaceViewRenderer,
        monitorIndex: Int = 1,
        restartTick: Int = 0,
    ) {
        val currentState = status.value
        val sameStream = pc != null &&
            activeProfile == profile &&
            activeMonitor == monitorIndex &&
            activeRestartTick == restartTick &&
            currentState != "idle" &&
            currentState != "closed"
        if (sameStream) {
            attachRenderer(surface)
            return
        }
        activeRestartTick = restartTick
        start(context, profile, surface, monitorIndex)
    }

    /** Called when entering the Screen tab or switching streaming quality profile. */
    fun start(context: Context, profile: ScreenProfile, surface: SurfaceViewRenderer, monitorIndex: Int = 1) {
        initialize(context)
        activeProfile = profile
        activeMonitor = monitorIndex
        firstFrameNotified.set(false)
        hasFirstFrame.value = false

        videoTrack?.let { t -> runCatching { t.removeSink(frameForwardingSink) } }
        videoTrack = null
        runCatching { pc?.close() }
        pc = null
        conn.clearAnswerSdp()

        synchronized(rendererLock) {
            renderer = surface
        }
        initSurfaceOnce(surface)

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory?.createPeerConnection(rtcConfig, PeerObserver()) ?: run {
            status.value = "closed"
            return
        }
        val init = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.RECV_ONLY,
        )
        pc!!.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init)

        status.value = "offering"
        pc!!.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc?.setLocalDescription(NoopSdp, desc)
                    conn.screenStart(profile, monitorIndex, desc.description)
                    status.value = "waiting_answer"
                }
                override fun onCreateFailure(error: String) {
                    status.value = "closed"
                }
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String) = Unit
            },
            MediaConstraints(),
        )
    }

    fun applyAnswer(sdp: String) {
        if (sdp.isBlank()) return
        val currentPc = this.pc ?: return
        currentPc.setRemoteDescription(NoopSdp, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        status.value = "live"
    }

    fun stop() {
        val wasFirstFrame = firstFrameNotified.getAndSet(false)
        hasFirstFrame.value = false
        activeProfile = null
        activeRestartTick = -1
        videoTrack?.let { t -> runCatching { t.removeSink(frameForwardingSink) } }
        videoTrack = null
        val currentRenderer = synchronized(rendererLock) {
            val prev = renderer
            renderer = null
            prev
        }
        currentRenderer?.let { r ->
            initializedRenderers.remove(r)
            runCatching { r.release() }
        }
        runCatching { pc?.close() }
        pc = null
        conn.clearAnswerSdp()
        if (wasFirstFrame) {
            conn.sendWebRtcLive(false)
        }
        status.value = "closed"
    }

    fun release() {
        stop()
        runCatching { factory?.dispose() }
        factory = null
        runCatching { egl?.release() }
        egl = null
    }

    fun attachRenderer(surface: SurfaceViewRenderer) {
        initSurfaceOnce(surface)
        synchronized(rendererLock) {
            renderer = surface
        }
    }

    fun detachRenderer(surface: SurfaceViewRenderer) {
        val wasCurrent = synchronized(rendererLock) {
            val match = renderer === surface
            if (match) renderer = null
            match
        }
        if (wasCurrent && initializedRenderers.remove(surface)) {
            runCatching { surface.release() }
        }
    }

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            conn.sendIceCandidate(candidate.sdp, candidate.sdpMid ?: "0")
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> status.value = "live"
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED,
                -> {
                    if (firstFrameNotified.getAndSet(false)) {
                        conn.sendWebRtcLive(false)
                    }
                    hasFirstFrame.value = false
                    status.value = "closed"
                }
                else -> Unit
            }
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) {
                videoTrack = track
                mainHandler.post {
                    synchronized(rendererLock) { renderer }?.let { surf ->
                        initSurfaceOnce(surf)
                    }
                    runCatching { track.addSink(frameForwardingSink) }
                }
            } else if (track is AudioTrack) {
                track.setVolume(0.0) // screen track is video-only (PCM audio arrives over dedicated stream)
            }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private object NoopSdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    /** Stream HUD telemetry (status and ICE state). */
    fun hud(): JSONObject {
        val currentPc = this.pc ?: return JSONObject().put("status", "closed")
        return JSONObject()
            .put("status", status.value)
            .put("ice", currentPc.iceConnectionState().name)
    }
}
