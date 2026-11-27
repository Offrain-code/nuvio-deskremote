package com.nuvio.remote.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.nuvio.remote.MainActivity
import com.nuvio.remote.R
import com.nuvio.remote.connection.WifiLatencyLock
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.i18n.resolveContextLanguage

/** Static bridge connecting PrivateListeningService notification actions to the live session. */
object PrivateListeningBridge {
    @Volatile
    var onMutePc: (() -> Unit)? = null

    @Volatile
    var onStop: (() -> Unit)? = null
}

/**
 * Foreground service keeping WASAPI loopback Private Listening alive while the phone screen is off
 * (DESIGN-SPEC §5.14.1, PC Mode exclusive).
 */
class PrivateListeningService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiLatencyLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val lang = resolveContextLanguage(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                l10n(lang, "Private Listening", "Escuta Privada"),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = l10n(
                    lang,
                    "Keeps PC audio streaming active while the screen is off",
                    "Mantém o áudio do PC ativo com a tela apagada",
                )
                setShowBadge(false)
            },
        )
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NuvioRemote:PrivateAudio")?.apply {
            setReferenceCounted(false)
            runCatching { acquire(MAX_WAKE_LOCK_MS) }
        }
        wifiLock = WifiLatencyLock(applicationContext).apply { setActive(true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE -> {
                PrivateListeningBridge.onMutePc?.invoke()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                PrivateListeningBridge.onStop?.invoke()
                stopSelfClean()
                return START_NOT_STICKY
            }
        }
        val pcName = intent?.getStringExtra(EXTRA_PC_NAME)?.trim().orEmpty().ifEmpty { "PC" }
        val pingMs = intent?.getIntExtra(EXTRA_PING_MS, 0) ?: 0
        val notification = buildNotification(pcName, pingMs)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        wifiLock?.release()
        wifiLock = null
        super.onDestroy()
    }

    private fun stopSelfClean() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun buildNotification(pcName: String, pingMs: Int): Notification {
        val lang = resolveContextLanguage(this)
        val title = l10n(lang, "Private Listening • $pcName", "Escuta Privada • $pcName")
        val subtitle = if (pingMs > 0) {
            l10n(lang, "$pingMs ms • Audio active with screen off", "$pingMs ms • Áudio ativo c/ tela apagada")
        } else {
            l10n(lang, "Audio active with screen off", "Áudio ativo com a tela apagada")
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("nuvio_initial_tab", "screen")
        }
        val openPi = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val mutePi = PendingIntent.getService(
            this,
            11,
            Intent(this, PrivateListeningService::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this,
            12,
            Intent(this, PrivateListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(
                Notification.Action.Builder(null, l10n(lang, "Mute", "Mudo"), mutePi).build(),
            )
            .addAction(
                Notification.Action.Builder(null, l10n(lang, "Stop", "Parar"), stopPi).build(),
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "nuvio_private_listening"
        private const val NOTIFICATION_ID = 4102
        private const val MAX_WAKE_LOCK_MS = 4L * 60L * 60L * 1000L // 4 hours safety cap
        private const val ACTION_MUTE = "com.nuvio.remote.action.PRIVATE_AUDIO_MUTE"
        private const val ACTION_STOP = "com.nuvio.remote.action.PRIVATE_AUDIO_STOP"
        private const val EXTRA_PC_NAME = "pc_name"
        private const val EXTRA_PING_MS = "ping_ms"

        fun start(context: Context, pcName: String, pingMs: Int) {
            val intent = Intent(context, PrivateListeningService::class.java)
                .putExtra(EXTRA_PC_NAME, pcName)
                .putExtra(EXTRA_PING_MS, pingMs)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, PrivateListeningService::class.java))
            }
        }
    }
}
