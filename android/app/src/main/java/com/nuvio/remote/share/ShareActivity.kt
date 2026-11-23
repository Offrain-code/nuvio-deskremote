package com.nuvio.remote.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.nuvio.remote.MainActivity
import com.nuvio.remote.connection.EndpointUrlResolver
import com.nuvio.remote.data.AppPrefs
import com.nuvio.remote.pairing.PairingManager
import com.nuvio.remote.ui.i18n.ResolvedLanguage
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.i18n.resolveContextLanguage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * "Send to Nuvio" Android Share target.
 * Forwards YouTube, YouTube Music, or direct stream links through the Companion without opening UI.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lang = resolveContextLanguage(this)
        val url = extractSharedUrl(intent)
        val pairing = PairingManager(this)
        val appPrefs = AppPrefs(this)
        val host = pairing.savedHost?.trim().orEmpty().ifEmpty { appPrefs.savedLanHost.trim() }
        val token = pairing.savedToken

        when {
            url == null -> finishWithToast(
                l10n(lang, "No link found to send to Nuvio.", "Nenhum link encontrado para enviar ao Nuvio."),
            )
            host.isEmpty() || token.isBlank() -> {
                finishWithToast(
                    l10n(
                        lang,
                        "Connect and pair Nuvio DeskRemote first.",
                        "Conecte e pareie o Nuvio DeskRemote primeiro.",
                    ),
                )
                startActivity(Intent(this, MainActivity::class.java))
            }
            else -> Thread({ dispatchLink(host, token, url, lang) }, "nuvio-share-open").start()
        }
    }

    private fun dispatchLink(host: String, token: String, url: String, lang: ResolvedLanguage) {
        val message = sendViaCompanion(host, token, url, lang)
        runOnUiThread { finishWithToast(message) }
    }

    private fun sendViaCompanion(host: String, token: String, url: String, lang: ResolvedLanguage): String {
        val wsUrl = EndpointUrlResolver.buildWsUrl(host, 8787)
            ?: return l10n(lang, "Invalid PC address configured.", "Endereço do computador inválido.")

        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference(
            l10n(lang, "Could not reach the computer.", "Não foi possível alcançar o computador."),
        )

        val request = runCatching {
            Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer $token")
                .build()
        }.getOrElse {
            return l10n(lang, "Invalid PC address configured.", "Endereço do computador inválido.")
        }

        val ws = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        JSONObject()
                            .put("type", "hello")
                            .put("name", "Nuvio DeskRemote Share")
                            .put("auth", token)
                            .toString(),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (obj.optString("type")) {
                        "welcome" -> {
                            webSocket.send(
                                JSONObject().put("type", "nuvio_open").put("url", url).toString(),
                            )
                        }
                        "nuvio_open_result" -> {
                            val ok = obj.optBoolean("ok", false)
                            val what = obj.optString("what", "").trim()
                            resultRef.set(
                                if (ok) {
                                    if (what.isNotEmpty()) {
                                        l10n(lang, "Opening $what in Nuvio", "Abrindo $what no Nuvio")
                                    } else {
                                        l10n(lang, "Opening in Nuvio", "Abrindo no Nuvio")
                                    }
                                } else {
                                    l10n(
                                        lang,
                                        "Nuvio could not open this link (check Nuvio link in Settings).",
                                        "O Nuvio não conseguiu abrir esse link (verifique se está vinculado).",
                                    )
                                },
                            )
                            webSocket.close(1000, "done")
                            latch.countDown()
                        }
                        "error" -> {
                            val fallback = l10n(lang, "Failed to send link.", "Erro ao enviar link.")
                            resultRef.set(obj.optString("message", fallback))
                            webSocket.close(1000, "err")
                            latch.countDown()
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == 1008) {
                        resultRef.set(
                            l10n(
                                lang,
                                "Device pairing expired or revoked. Pair again in Settings.",
                                "Pareamento revogado ou expirado. Pareie novamente em Ajustes.",
                            ),
                        )
                    }
                    latch.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    resultRef.set(
                        l10n(lang, "No response from PC on Wi-Fi.", "Sem resposta do computador no Wi-Fi."),
                    )
                    latch.countDown()
                }
            },
        )

        runCatching { latch.await(6, TimeUnit.SECONDS) }
        runCatching { ws.close(1000, "timeout") }
        client.dispatcher.executorService.shutdown()
        return resultRef.get()
    }

    private fun finishWithToast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val combined = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
        ).joinToString(" ")
        return URL_REGEX.find(combined)?.value
    }

    companion object {
        private val URL_REGEX = Regex("""(?i)\b(?:https?|nuvio)://[^\s<>"']+""")
    }
}
