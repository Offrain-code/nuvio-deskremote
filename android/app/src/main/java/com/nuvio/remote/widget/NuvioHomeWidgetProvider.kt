package com.nuvio.remote.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.nuvio.remote.MainActivity
import com.nuvio.remote.R
import com.nuvio.remote.connection.EndpointUrlResolver
import com.nuvio.remote.connection.NuvioNowPlaying
import com.nuvio.remote.connection.NuvioWakeOnLan
import com.nuvio.remote.data.AppPrefs
import com.nuvio.remote.media.NowPlayingMediaBridge
import com.nuvio.remote.pairing.PairingManager
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.i18n.resolveContextLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val ACTION_WIDGET_WAKE = "com.nuvio.remote.widget.ACTION_WAKE"
private const val ACTION_WIDGET_KEY = "com.nuvio.remote.widget.ACTION_KEY"
private const val EXTRA_KEY_NAME = "key_name"
private const val HEALTH_TIMEOUT_MS = 1_200
private const val WS_TIMEOUT_SEC = 4L

/**
 * Compact 4x1 Home Screen Widget (DESIGN-SPEC §5.13 Fase 2C):
 * Offline -> Wake PC (WoL); Online -> Play/Pause, Vol -, Vol +, Mute.
 */
class NuvioCompactWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        NuvioWidgetUpdater.refreshInBackground(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        handleWidgetAction(this, context, intent)
    }
}

/**
 * Expanded 4x2 Home Screen Widget (DESIGN-SPEC §5.13 Fase 2C):
 * Now Playing metadata + progress bar + Vol -, Mute, Play, Vol +, Open (or WoL when offline).
 */
class NuvioExpandedWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        NuvioWidgetUpdater.refreshInBackground(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        handleWidgetAction(this, context, intent)
    }
}

private fun handleWidgetAction(receiver: AppWidgetProvider, context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (action != ACTION_WIDGET_WAKE && action != ACTION_WIDGET_KEY) return
    val pending = receiver.goAsync()
    val appCtx = context.applicationContext
    NuvioWidgetUpdater.scope.launch {
        try {
            when (action) {
                ACTION_WIDGET_WAKE -> {
                    val wakeInfo = AppPrefs(appCtx).savedWakeInfo()
                    if (wakeInfo != null && wakeInfo.macs.isNotEmpty()) {
                        NuvioWakeOnLan.sendWake(wakeInfo)
                    }
                    NuvioWidgetUpdater.syncFromApp(appCtx, online = false, nowPlaying = null, waking = true)
                }
                ACTION_WIDGET_KEY -> {
                    val key = intent.getStringExtra(EXTRA_KEY_NAME).orEmpty()
                    if (key.isNotEmpty()) {
                        dispatchWidgetKey(appCtx, key)
                    }
                }
            }
        } finally {
            pending.finish()
        }
    }
}

private fun dispatchWidgetKey(context: Context, keyName: String) {
    if (keyName == "Space" && NowPlayingMediaBridge.onPlayPause != null) {
        NowPlayingMediaBridge.onPlayPause?.invoke()
        return
    }
    val pairing = PairingManager(context)
    val prefs = AppPrefs(context)
    val host = pairing.savedHost?.trim().orEmpty().ifEmpty { prefs.savedLanHost.trim() }
    val token = pairing.savedToken
    if (host.isEmpty() || token.isBlank()) return
    val wsUrl = EndpointUrlResolver.buildWsUrl(host, 8787) ?: return

    val client = OkHttpClient.Builder()
        .connectTimeout(WS_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(WS_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()
    val latch = CountDownLatch(1)
    val request = runCatching {
        Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()
    }.getOrNull() ?: return

    val ws = client.newWebSocket(
        request,
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    JSONObject()
                        .put("type", "hello")
                        .put("name", "Nuvio Widget")
                        .put("auth", token)
                        .toString(),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (obj.optString("type") == "welcome") {
                    webSocket.send(JSONObject().put("type", "key").put("name", keyName).toString())
                    webSocket.close(1000, "done")
                    latch.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                latch.countDown()
            }
        },
    )
    runCatching { latch.await(WS_TIMEOUT_SEC, TimeUnit.SECONDS) }
    runCatching { ws.close(1000, "timeout") }
    client.dispatcher.executorService.shutdown()
}

/**
 * Coordinates rendering of both 4x1 and 4x2 Nuvio Home Widgets.
 */
object NuvioWidgetUpdater {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refreshInBackground(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            val host = AppPrefs(appCtx).savedLanHost
            val online = probeHealth(host)
            syncFromApp(appCtx, online = online, nowPlaying = NowPlayingMediaBridge.latest)
        }
    }

    fun syncFromApp(context: Context, online: Boolean, nowPlaying: NuvioNowPlaying?, waking: Boolean = false) {
        val appCtx = context.applicationContext
        val manager = AppWidgetManager.getInstance(appCtx) ?: return
        val compactIds = manager.getAppWidgetIds(ComponentName(appCtx, NuvioCompactWidgetProvider::class.java))
        val expandedIds = manager.getAppWidgetIds(ComponentName(appCtx, NuvioExpandedWidgetProvider::class.java))
        if (compactIds.isEmpty() && expandedIds.isEmpty()) return

        if (compactIds.isNotEmpty()) {
            val views = buildCompactViews(appCtx, online, nowPlaying, waking)
            compactIds.forEach { id -> manager.updateAppWidget(id, views) }
        }
        if (expandedIds.isNotEmpty()) {
            val views = buildExpandedViews(appCtx, online, nowPlaying, waking)
            expandedIds.forEach { id -> manager.updateAppWidget(id, views) }
        }
    }

    private fun buildCompactViews(
        context: Context,
        online: Boolean,
        nowPlaying: NuvioNowPlaying?,
        waking: Boolean,
    ): RemoteViews {
        val lang = resolveContextLanguage(context)
        val views = RemoteViews(context.packageName, R.layout.widget_nuvio_4x1)
        val title = nowPlaying?.title?.takeIf { it.isNotBlank() } ?: "Nuvio DeskRemote"
        val subtitle = when {
            waking -> l10n(lang, "Waking PC (WoL)…", "Acordando PC (WoL)…")
            online && nowPlaying != null -> if (nowPlaying.isPlaying) {
                l10n(lang, "Online • Playing", "Online • Tocando")
            } else {
                l10n(lang, "Online • Paused", "Online • Pausado")
            }
            online -> l10n(lang, "Home • Online", "Casa • Online")
            else -> l10n(lang, "Offline • Tap to wake", "Offline • Toque p/ acordar")
        }
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_subtitle, subtitle)
        views.setViewVisibility(R.id.widget_offline_actions, if (online) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_online_actions, if (online) View.VISIBLE else View.GONE)
        views.setTextViewText(
            R.id.widget_btn_wake,
            if (waking) l10n(lang, "Waking…", "Acordando…") else l10n(lang, "Wake PC (WoL)", "Acordar PC (WoL)"),
        )
        bindWidgetActions(views, context, NuvioCompactWidgetProvider::class.java, baseCode = 100)
        return views
    }

    private fun buildExpandedViews(
        context: Context,
        online: Boolean,
        nowPlaying: NuvioNowPlaying?,
        waking: Boolean,
    ): RemoteViews {
        val lang = resolveContextLanguage(context)
        val views = RemoteViews(context.packageName, R.layout.widget_nuvio_4x2)
        val title = nowPlaying?.title?.takeIf { it.isNotBlank() } ?: "Nuvio DeskRemote"
        val playingLabel = if (nowPlaying?.isPlaying == true) {
            l10n(lang, "Playing on Nuvio HTPC", "Tocando no Nuvio HTPC")
        } else {
            l10n(lang, "Paused", "Pausado")
        }
        val subtitle = when {
            waking -> l10n(lang, "Wake-on-LAN sent…", "Wake-on-LAN enviado…")
            online && nowPlaying != null -> nowPlaying.subtitle?.takeIf { it.isNotBlank() } ?: playingLabel
            online -> l10n(lang, "Connected • Ready to control", "Conectado • Pronto para controlar")
            else -> l10n(lang, "PC Offline • Send Wake-on-LAN", "PC Offline • Enviar Wake-on-LAN")
        }
        val progress = if (nowPlaying != null && nowPlaying.durationMs > 0L) {
            val pos = nowPlaying.positionAt(System.currentTimeMillis())
            ((pos * 100L) / nowPlaying.durationMs).toInt().coerceIn(0, 100)
        } else {
            0
        }
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_subtitle, subtitle)
        views.setProgressBar(R.id.widget_progress, 100, progress, false)
        views.setViewVisibility(R.id.widget_offline_actions, if (online) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_online_actions, if (online) View.VISIBLE else View.GONE)
        views.setTextViewText(
            R.id.widget_btn_wake,
            if (waking) l10n(lang, "Waking PC…", "Acordando PC…") else l10n(lang, "Wake PC (WoL)", "Acordar PC (WoL)"),
        )
        bindWidgetActions(views, context, NuvioExpandedWidgetProvider::class.java, baseCode = 200)
        views.setOnClickPendingIntent(R.id.widget_btn_open, openAppIntent(context, 206))
        return views
    }

    private fun bindWidgetActions(views: RemoteViews, context: Context, cls: Class<*>, baseCode: Int) {
        views.setOnClickPendingIntent(R.id.widget_status_area, openAppIntent(context, baseCode))
        views.setOnClickPendingIntent(
            R.id.widget_btn_wake,
            actionIntent(context, cls, ACTION_WIDGET_WAKE, null, baseCode + 1),
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_play,
            actionIntent(context, cls, ACTION_WIDGET_KEY, "Space", baseCode + 2),
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_vol_down,
            actionIntent(context, cls, ACTION_WIDGET_KEY, "VolumeDown", baseCode + 3),
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_vol_up,
            actionIntent(context, cls, ACTION_WIDGET_KEY, "VolumeUp", baseCode + 4),
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_mute,
            actionIntent(context, cls, ACTION_WIDGET_KEY, "VolumeMute", baseCode + 5),
        )
    }

    private fun openAppIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(
        context: Context,
        cls: Class<*>,
        action: String,
        keyName: String?,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, cls).apply {
            this.action = action
            if (keyName != null) putExtra(EXTRA_KEY_NAME, keyName)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun probeHealth(host: String): Boolean {
        if (host.isBlank()) return false
        return runCatching {
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
}
