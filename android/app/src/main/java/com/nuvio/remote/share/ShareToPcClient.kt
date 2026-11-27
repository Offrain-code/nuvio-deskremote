package com.nuvio.remote.share

import android.content.ContentResolver
import android.net.Uri
import com.nuvio.remote.connection.EndpointUrlResolver
import com.nuvio.remote.ui.i18n.ResolvedLanguage
import com.nuvio.remote.ui.i18n.l10n
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal const val MAX_SHARE_FILE_BYTES: Long = 50L * 1024L * 1024L

internal sealed interface ShareToPcOutcome {
    data class Success(val message: String) : ShareToPcOutcome
    data class Error(val message: String) : ShareToPcOutcome
}

internal object ShareToPcClient {
    private val URL_REGEX = Regex("""(?i)\bhttps?://[^\s<>"']+""")

    fun extractFirstHttpUrl(text: String?): String? = if (text.isNullOrBlank()) null else URL_REGEX.find(text)?.value

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    fun openUrlOnPc(host: String, token: String, url: String, lang: ResolvedLanguage): ShareToPcOutcome = runWsCommand(
        host = host,
        token = token,
        lang = lang,
        payload = JSONObject().put("type", "pc_open_url").put("url", url),
        expectReplyType = "pc_open_url_result",
        successFallback = l10n(lang, "Opened in PC browser.", "Aberto no navegador do PC."),
    )

    fun copyTextToPc(host: String, token: String, text: String, lang: ResolvedLanguage): ShareToPcOutcome =
        runWsCommand(
            host = host,
            token = token,
            lang = lang,
            payload = JSONObject().put("type", "clipboard_set").put("text", text),
            expectReplyType = null,
            successFallback = l10n(lang, "Copied to PC clipboard.", "Copiado para a área de transferência do PC."),
        )

    fun uploadFileToPc(
        contentResolver: ContentResolver,
        host: String,
        token: String,
        uri: Uri,
        fileName: String,
        fileSize: Long,
        lang: ResolvedLanguage,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit,
    ): ShareToPcOutcome {
        if (EndpointUrlResolver.isRemoteTunnelHost(host)) {
            return ShareToPcOutcome.Error(
                l10n(
                    lang,
                    "File upload is allowed only on home Wi-Fi (LAN_ONLY).",
                    "Envio de arquivos disponível apenas no Wi-Fi de casa (LAN_ONLY).",
                ),
            )
        }
        if (fileSize > MAX_SHARE_FILE_BYTES) {
            return ShareToPcOutcome.Error(
                l10n(lang, "File exceeds 50 MB limit.", "Arquivo excede o limite de 50 MB."),
            )
        }
        val uploadUrl = EndpointUrlResolver.buildLanHttpUrl(host, 8787, "/api/v1/pc/upload")
            ?: return ShareToPcOutcome.Error(
                l10n(lang, "Invalid PC address.", "Endereço do computador inválido."),
            )
        val encodedName = runCatching { URLEncoder.encode(fileName, "UTF-8") }.getOrDefault("file.bin")
        val client = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val body = StreamingUriRequestBody(
            contentResolver = contentResolver,
            uri = uri,
            totalBytes = fileSize,
            onProgress = onProgress,
        )
        return try {
            val req = Request.Builder()
                .url(uploadUrl)
                .header("Authorization", "Bearer $token")
                .header("X-File-Name", encodedName)
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(raw) }.getOrNull()
                when (resp.code) {
                    200 -> {
                        val saved = obj?.optString("filename", fileName).orEmpty().ifBlank { fileName }
                        ShareToPcOutcome.Success(
                            l10n(lang, "Saved to Downloads: $saved", "Salvo em Downloads: $saved"),
                        )
                    }
                    403 -> ShareToPcOutcome.Error(
                        l10n(
                            lang,
                            "Blocked outside home Wi-Fi (LAN_ONLY).",
                            "Bloqueado fora do Wi-Fi de casa (LAN_ONLY).",
                        ),
                    )
                    413 -> ShareToPcOutcome.Error(
                        l10n(lang, "File exceeds 50 MB limit.", "Arquivo excede o limite de 50 MB."),
                    )
                    else -> ShareToPcOutcome.Error(
                        l10n(lang, "Upload failed (HTTP ${resp.code}).", "Falha no envio (HTTP ${resp.code})."),
                    )
                }
            }
        } catch (_: Exception) {
            ShareToPcOutcome.Error(
                l10n(lang, "Could not reach PC on Wi-Fi.", "Não foi possível alcançar o PC no Wi-Fi."),
            )
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun runWsCommand(
        host: String,
        token: String,
        lang: ResolvedLanguage,
        payload: JSONObject,
        expectReplyType: String?,
        successFallback: String,
    ): ShareToPcOutcome {
        val wsUrl = EndpointUrlResolver.buildWsUrl(host, 8787)
            ?: return ShareToPcOutcome.Error(l10n(lang, "Invalid PC address.", "Endereço do computador inválido."))
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<ShareToPcOutcome>(
            ShareToPcOutcome.Error(
                l10n(lang, "Could not reach the computer.", "Não foi possível alcançar o computador."),
            ),
        )
        val request = runCatching {
            Request.Builder().url(wsUrl).header("Authorization", "Bearer $token").build()
        }.getOrElse {
            return ShareToPcOutcome.Error(l10n(lang, "Invalid PC address.", "Endereço do computador inválido."))
        }
        val ws = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        JSONObject()
                            .put("type", "hello")
                            .put("name", "Nuvio DeskRemote Share PC")
                            .put("auth", token)
                            .toString(),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (obj.optString("type")) {
                        "welcome" -> {
                            webSocket.send(payload.toString())
                            if (expectReplyType == null) {
                                resultRef.set(ShareToPcOutcome.Success(successFallback))
                                webSocket.close(1000, "done")
                                latch.countDown()
                            }
                        }
                        expectReplyType -> {
                            val ok = obj.optBoolean("ok", false)
                            resultRef.set(
                                if (ok) {
                                    ShareToPcOutcome.Success(successFallback)
                                } else {
                                    ShareToPcOutcome.Error(
                                        l10n(lang, "PC rejected this request.", "O PC recusou esta ação."),
                                    )
                                },
                            )
                            webSocket.close(1000, "done")
                            latch.countDown()
                        }
                        "error" -> {
                            val msg = obj.optString("message", l10n(lang, "Request failed.", "Falha no envio."))
                            resultRef.set(ShareToPcOutcome.Error(msg))
                            webSocket.close(1000, "err")
                            latch.countDown()
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    resultRef.set(
                        ShareToPcOutcome.Error(
                            l10n(lang, "No response from PC.", "Sem resposta do computador."),
                        ),
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
}

private class StreamingUriRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val totalBytes: Long,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = if (totalBytes > 0L) totalBytes else -1L
    override fun writeTo(sink: BufferedSink) {
        val stream = contentResolver.openInputStream(uri) ?: error("Unable to open shared file")
        stream.use { input ->
            val buf = ByteArray(16 * 1024)
            var sent = 0L
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                sink.write(buf, 0, read)
                sent += read
                onProgress(sent, totalBytes)
            }
        }
    }
}
