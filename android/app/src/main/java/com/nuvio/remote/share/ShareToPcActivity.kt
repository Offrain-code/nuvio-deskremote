package com.nuvio.remote.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.remote.connection.EndpointUrlResolver
import com.nuvio.remote.data.AppPrefs
import com.nuvio.remote.pairing.PairingManager
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.i18n.resolveContextLanguage
import com.nuvio.remote.ui.theme.AppThemeMode
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioGlassTheme
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass
import com.nuvio.remote.ui.theme.rememberGlassBackdropState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SharedFileMeta(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
)

/**
 * Android Share Sheet target ("Send to PC" — DESIGN-SPEC §5.14.2, PC Mode).
 * Supports URLs (open in PC browser / copy), plain text (PC clipboard), and files (Downloads, LAN_ONLY <= 50 MB).
 */
class ShareToPcActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appPrefs = AppPrefs(this)
        val pairing = PairingManager(this)
        val lang = resolveContextLanguage(this)
        val activeProfile = pairing.activeProfile()
        val host = activeProfile?.host?.takeIf { it.isNotBlank() }
            ?: pairing.savedHost?.trim().orEmpty().ifEmpty { appPrefs.savedLanHost.trim() }
        val pcName = activeProfile?.name?.ifBlank { host } ?: host.ifBlank { "PC" }
        val token = activeProfile?.token?.takeIf { it.isNotBlank() } ?: pairing.savedToken

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val sharedUrl = ShareToPcClient.extractFirstHttpUrl(sharedText)
        val sharedFile = extractSharedFile(intent)

        setContent {
            val themeMode = AppThemeMode.fromWire(appPrefs.appThemeMode)
            val darkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }
            val backdrop = rememberGlassBackdropState()
            NuvioGlassTheme(
                hapticsEnabled = appPrefs.hapticsEnabled,
                darkTheme = darkTheme,
                backdropState = backdrop,
                resolvedLanguage = lang,
            ) {
                ShareToPcSheetContent(
                    pcName = pcName,
                    host = host,
                    token = token,
                    sharedUrl = sharedUrl,
                    sharedText = sharedText,
                    sharedFile = sharedFile,
                    lang = lang,
                    onClose = { finish() },
                )
            }
        }
    }

    private fun extractSharedFile(intent: Intent?): SharedFileMeta? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: return null
        var name = "shared-file"
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) name = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: name
                        if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                    }
                }
        }
        return SharedFileMeta(uri = uri, name = name, sizeBytes = size)
    }
}

@Composable
private fun ShareToPcSheetContent(
    pcName: String,
    host: String,
    token: String,
    sharedUrl: String?,
    sharedText: String,
    sharedFile: SharedFileMeta?,
    lang: com.nuvio.remote.ui.i18n.ResolvedLanguage,
    onClose: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val isLanHost = host.isNotBlank() && !EndpointUrlResolver.isRemoteTunnelHost(host)
    val pairedReady = host.isNotBlank() && token.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.56f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (!busy) onClose() },
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .nuvioGlass(shape = RoundedCornerShape(24.dp), variant = GlassVariant.REGULAR, elevated = true)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ShareToPcHeader(pcName = pcName, isLanHost = isLanHost, onClose = onClose)

            if (!pairedReady) {
                ShareToPcOpaqueNotice(
                    text = l10n(lang, "Connect and pair a PC first.", "Conecte e pareie um PC primeiro."),
                    accent = scheme.statusWarning,
                )
            } else {
                ShareToPcActionCards(
                    host = host,
                    token = token,
                    sharedUrl = sharedUrl,
                    sharedText = sharedText,
                    sharedFile = sharedFile,
                    isLanHost = isLanHost,
                    busy = busy,
                    progress = progress,
                    lang = lang,
                    onBusyChange = { busy = it },
                    onProgressChange = { progress = it },
                    onOutcome = { res ->
                        isError = res is ShareToPcOutcome.Error
                        statusMsg = when (res) {
                            is ShareToPcOutcome.Success -> res.message
                            is ShareToPcOutcome.Error -> res.message
                        }
                    },
                    onClose = onClose,
                )
            }

            statusMsg?.let { msg ->
                ShareToPcOpaqueNotice(
                    text = msg,
                    accent = if (isError) scheme.statusError else scheme.statusOnline,
                )
            }
        }
    }
}

@Composable
private fun ShareToPcActionCards(
    host: String,
    token: String,
    sharedUrl: String?,
    sharedText: String,
    sharedFile: SharedFileMeta?,
    isLanHost: Boolean,
    busy: Boolean,
    progress: Float,
    lang: com.nuvio.remote.ui.i18n.ResolvedLanguage,
    onBusyChange: (Boolean) -> Unit,
    onProgressChange: (Float) -> Unit,
    onOutcome: (ShareToPcOutcome) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    if (sharedUrl != null || sharedText.isNotBlank()) {
        ShareTextUrlCard(
            sharedUrl = sharedUrl,
            sharedText = sharedText,
            busy = busy,
            lang = lang,
            onOpenBrowser = {
                if (sharedUrl == null) return@ShareTextUrlCard
                onBusyChange(true)
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        ShareToPcClient.openUrlOnPc(host, token, sharedUrl, lang)
                    }
                    onBusyChange(false)
                    onOutcome(res)
                }
            },
            onCopyClipboard = {
                val textToCopy = sharedUrl ?: sharedText
                onBusyChange(true)
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        ShareToPcClient.copyTextToPc(host, token, textToCopy, lang)
                    }
                    onBusyChange(false)
                    onOutcome(res)
                }
            },
        )
    }

    if (sharedFile != null) {
        ShareFileCard(
            file = sharedFile,
            isLanHost = isLanHost,
            busy = busy,
            progress = progress,
            lang = lang,
            onUpload = {
                onBusyChange(true)
                onProgressChange(0.02f)
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        ShareToPcClient.uploadFileToPc(
                            contentResolver = context.contentResolver,
                            host = host,
                            token = token,
                            uri = sharedFile.uri,
                            fileName = sharedFile.name,
                            fileSize = sharedFile.sizeBytes,
                            lang = lang,
                            onProgress = { sent, total ->
                                if (total > 0L) {
                                    onProgressChange((sent.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                                }
                            },
                        )
                    }
                    onBusyChange(false)
                    onOutcome(res)
                }
            },
            onCancel = onClose,
        )
    }
}

@Composable
private fun ShareToPcHeader(pcName: String, isLanHost: Boolean, onClose: () -> Unit) {
    val scheme = LocalNuvioColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NuvioText(
                text = l10n("ANDROID SHARE • PC MODE", "ANDROID SHARE • MODO PC"),
                style = NuvioTypography.SectionMicroTitle.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
                color = scheme.ctxPlayer,
                maxLines = 1,
            )
            NuvioText(
                text = l10n("Send to PC", "Enviar para o PC"),
                style = NuvioTypography.DisplayKey.copy(fontWeight = FontWeight.Bold),
                color = scheme.textPrimary,
                maxLines = 1,
            )
            NuvioText(
                text = if (isLanHost) "$pcName • Wi-Fi" else "$pcName • ${l10n("Remote", "Remoto")}",
                style = NuvioTypography.KeyLabelCompact,
                color = if (isLanHost) scheme.statusOnline else scheme.statusWarning,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = l10n("Close", "Fechar"),
                    tint = scheme.textPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ShareTextUrlCard(
    sharedUrl: String?,
    sharedText: String,
    busy: Boolean,
    lang: com.nuvio.remote.ui.i18n.ResolvedLanguage,
    onOpenBrowser: () -> Unit,
    onCopyClipboard: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NuvioText(
            text = sharedUrl ?: sharedText,
            style = NuvioTypography.BodyRegular.copy(fontWeight = FontWeight.Medium),
            color = scheme.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sharedUrl != null) {
                ShareActionButton(
                    label = l10n(lang, "Open in browser", "Abrir no navegador"),
                    primary = true,
                    enabled = !busy,
                    onClick = onOpenBrowser,
                    modifier = Modifier.weight(1f),
                )
            }
            ShareActionButton(
                label = l10n(lang, "Copy to PC", "Copiar p/ PC"),
                primary = sharedUrl == null,
                enabled = !busy,
                onClick = onCopyClipboard,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ShareFileCard(
    file: SharedFileMeta,
    isLanHost: Boolean,
    busy: Boolean,
    progress: Float,
    lang: com.nuvio.remote.ui.i18n.ResolvedLanguage,
    onUpload: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val overLimit = file.sizeBytes > MAX_SHARE_FILE_BYTES
    val blocked = !isLanHost || overLimit

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.contentCardSurface)
            .border(
                1.dp,
                if (blocked) scheme.statusWarning.copy(alpha = 0.65f) else scheme.contentBorder,
                RoundedCornerShape(16.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NuvioText(
                text = file.name,
                style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.Bold),
                color = scheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            NuvioText(
                text = if (file.sizeBytes > 0L) "${ShareToPcClient.formatBytes(file.sizeBytes)} / 50 MB" else "≤ 50 MB",
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.ctxPlayer,
                maxLines = 1,
            )
        }

        if (busy) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(scheme.contentCellSurface),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0.05f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(scheme.ctxPlayer),
                )
            }
        }

        if (blocked) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = scheme.statusWarning,
                    modifier = Modifier.size(16.dp),
                )
                NuvioText(
                    text = if (overLimit) {
                        l10n(lang, "File exceeds 50 MB limit.", "Arquivo excede o limite de 50 MB.")
                    } else {
                        l10n(
                            lang,
                            "File transfer is allowed only on home Wi-Fi (LAN_ONLY).",
                            "Envio de arquivos só no Wi-Fi de casa (LAN_ONLY).",
                        )
                    },
                    style = NuvioTypography.KeyLabelCompact,
                    color = scheme.statusWarning,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ShareActionButton(
                label = l10n(lang, "Save to Downloads", "Salvar em Downloads"),
                primary = true,
                enabled = !busy && !blocked,
                icon = Icons.Rounded.Upload,
                onClick = onUpload,
                modifier = Modifier.weight(1.4f),
            )
            ShareActionButton(
                label = l10n(lang, "Close", "Fechar"),
                primary = false,
                enabled = !busy,
                onClick = onCancel,
                modifier = Modifier.weight(0.9f),
            )
        }
    }
}

@Composable
private fun ShareActionButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val scheme = LocalNuvioColors.current
    val shape = RoundedCornerShape(13.dp)
    val bg = when {
        primary && enabled -> Modifier.background(
            Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)),
            shape,
        )
        else ->
            Modifier
                .background(scheme.contentCellSurface, shape)
                .border(1.dp, scheme.contentBorder, shape)
    }
    val fg = when {
        !enabled -> scheme.textMuted
        primary -> scheme.pillActiveFg
        else -> scheme.textPrimary
    }

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .then(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp).padding(end = 4.dp),
            )
        }
        NuvioText(
            text = label,
            style = NuvioTypography.SectionMicroTitle.copy(fontWeight = FontWeight.Bold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShareToPcOpaqueNotice(text: String, accent: Color) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        NuvioText(
            text = text,
            style = NuvioTypography.KeyLabelCompact.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
        )
    }
}
