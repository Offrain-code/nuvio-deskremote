package com.nuvio.remote.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.nuvio.remote.MainActivity
import com.nuvio.remote.R
import com.nuvio.remote.connection.NuvioNowPlaying
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.i18n.resolveContextLanguage
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Lightweight static bridge between the active connection and lock-screen media controls.
 */
object NowPlayingMediaBridge {
    @Volatile
    var onPlayPause: (() -> Unit)? = null

    @Volatile
    var onSeekTo: ((Long) -> Unit)? = null

    @Volatile
    var onSeekDelta: ((Long) -> Unit)? = null

    @Volatile
    var onNext: (() -> Unit)? = null

    @Volatile
    var onPrevious: (() -> Unit)? = null

    @Volatile
    var onSetVolume: ((Int) -> Unit)? = null

    @Volatile
    var latest: NuvioNowPlaying? = null
}

/**
 * Native MediaSession controls shown in notifications and on the lock screen during Nuvio playback.
 */
class NowPlayingMediaService : Service() {
    private val imageExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nuvio-media-art").apply { isDaemon = true }
    }
    private lateinit var notifications: NotificationManager
    private lateinit var session: MediaSession
    private var cachedArtUrl: String? = null
    private var cachedArt: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val lang = resolveContextLanguage(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                l10n(lang, "Nuvio Playback", "Reprodução no Nuvio"),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = l10n(
                    lang,
                    "Lock-screen playback controls for Nuvio",
                    "Controles de reprodução do Nuvio na tela de bloqueio",
                )
                setShowBadge(false)
            },
        )
        session = MediaSession(this, "NuvioRemoteMedia").apply {
            setCallback(sessionCallback)
            setSessionActivity(openAppPendingIntent())
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> NowPlayingMediaBridge.onPlayPause?.invoke()
            ACTION_NEXT -> NowPlayingMediaBridge.onNext?.invoke()
            ACTION_PREV -> NowPlayingMediaBridge.onPrevious?.invoke()
            ACTION_BACK -> NowPlayingMediaBridge.onSeekDelta?.invoke(-10_000L)
            ACTION_FWD -> NowPlayingMediaBridge.onSeekDelta?.invoke(10_000L)
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val state = NowPlayingMediaBridge.latest
        if (state == null) {
            startMinimalForeground()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        renderNowPlaying(state)
        return START_NOT_STICKY
    }

    private fun startMinimalForeground() {
        val lang = resolveContextLanguage(this)
        val placeholder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(l10n(lang, "Nuvio Playback", "Reprodução no Nuvio"))
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, placeholder)
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            session.isActive = false
            session.release()
        }
        imageExecutor.shutdownNow()
        super.onDestroy()
    }

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            if (NowPlayingMediaBridge.latest?.isPlaying != true) {
                NowPlayingMediaBridge.onPlayPause?.invoke()
            }
        }

        override fun onPause() {
            if (NowPlayingMediaBridge.latest?.isPlaying != false) {
                NowPlayingMediaBridge.onPlayPause?.invoke()
            }
        }

        override fun onSkipToNext() {
            NowPlayingMediaBridge.onNext?.invoke()
        }

        override fun onSkipToPrevious() {
            NowPlayingMediaBridge.onPrevious?.invoke()
        }

        override fun onRewind() {
            NowPlayingMediaBridge.onSeekDelta?.invoke(-10_000L)
        }

        override fun onFastForward() {
            NowPlayingMediaBridge.onSeekDelta?.invoke(10_000L)
        }

        override fun onSeekTo(pos: Long) {
            NowPlayingMediaBridge.onSeekTo?.invoke(pos)
        }
    }

    private fun renderNowPlaying(state: NuvioNowPlaying) {
        updatePlaybackSession(state)
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val url = state.imageUrl
        if (url.isNullOrBlank()) {
            cachedArtUrl = null
            cachedArt = null
        } else if (url != cachedArtUrl) {
            cachedArtUrl = url
            imageExecutor.execute {
                val bmp = downloadBitmap(url)
                if (bmp != null && cachedArtUrl == url) {
                    cachedArt = bmp
                    NowPlayingMediaBridge.latest?.let { current ->
                        updatePlaybackSession(current)
                        notifications.notify(NOTIFICATION_ID, buildNotification(current))
                    }
                }
            }
        }
    }

    private fun updatePlaybackSession(state: NuvioNowPlaying) {
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.subtitle.orEmpty())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
            .apply { cachedArt?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build()
        session.setMetadata(meta)

        val actions = PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_SEEK_TO or
            if (state.isMusic) {
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT
            } else {
                PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD
            }

        val pbState = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (state.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                state.positionAt(System.currentTimeMillis()),
                if (state.isPlaying) (state.speedPercent ?: 100) / 100f else 0f,
                SystemClock.elapsedRealtime(),
            )
            .build()
        session.setPlaybackState(pbState)

        state.volume?.let { level ->
            val max = state.volumeMax.coerceAtLeast(1)
            session.setPlaybackToRemote(
                object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, max, level.coerceIn(0, max)) {
                    override fun onSetVolumeTo(volume: Int) {
                        currentVolume = volume.coerceIn(0, max)
                        NowPlayingMediaBridge.onSetVolume?.invoke(currentVolume)
                    }

                    override fun onAdjustVolume(direction: Int) {
                        val next = (currentVolume + direction * 5).coerceIn(0, max)
                        currentVolume = next
                        NowPlayingMediaBridge.onSetVolume?.invoke(next)
                    }
                },
            )
        }
    }

    private fun buildNotification(state: NuvioNowPlaying): Notification {
        val lang = resolveContextLanguage(this)
        val prevIntent = serviceActionIntent(if (state.isMusic) ACTION_PREV else ACTION_BACK, 1)
        val playIntent = serviceActionIntent(ACTION_PLAY_PAUSE, 2)
        val nextIntent = serviceActionIntent(if (state.isMusic) ACTION_NEXT else ACTION_FWD, 3)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(state.title)
            .setContentText(
                state.subtitle ?: if (state.isMusic) {
                    l10n(lang, "Nuvio Music", "Música no Nuvio")
                } else {
                    l10n(lang, "Nuvio Player", "Player Nuvio")
                },
            )
            .setLargeIcon(cachedArt)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (state.isMusic) l10n(lang, "Previous", "Anterior") else "-10s",
                    prevIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (state.isPlaying) l10n(lang, "Pause", "Pausar") else l10n(lang, "Play", "Tocar"),
                    playIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (state.isMusic) l10n(lang, "Next", "Próxima") else "+10s",
                    nextIntent,
                ).build(),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun serviceActionIntent(action: String, code: Int): PendingIntent {
        val intent = Intent(this, NowPlayingMediaService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            code,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun downloadBitmap(url: String): Bitmap? = runCatching {
        val parsed = URL(url)
        if (parsed.protocol != "https" && parsed.protocol != "http") return@runCatching null
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 3500
        }
        try {
            val rawBytes = conn.inputStream.use { it.readBytes() }
            if (rawBytes.isEmpty()) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > MAX_ART_PX || bounds.outHeight / sampleSize > MAX_ART_PX) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    companion object {
        private const val MAX_ART_PX = 512
        private const val CHANNEL_ID = "nuvio_now_playing"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_PLAY_PAUSE = "com.nuvio.remote.media.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.nuvio.remote.media.NEXT"
        private const val ACTION_PREV = "com.nuvio.remote.media.PREV"
        private const val ACTION_BACK = "com.nuvio.remote.media.BACK"
        private const val ACTION_FWD = "com.nuvio.remote.media.FWD"
        private const val ACTION_STOP = "com.nuvio.remote.media.STOP"

        fun sync(context: Context, state: NuvioNowPlaying?) {
            NowPlayingMediaBridge.latest = state
            val intent = Intent(context, NowPlayingMediaService::class.java)
            if (state == null) {
                runCatching { context.stopService(intent) }
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            runCatching { context.startForegroundService(intent) }
        }
    }
}
