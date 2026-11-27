package com.nuvio.remote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nuvio.remote.connection.ConnState
import com.nuvio.remote.connection.NuvioBridgeStateHolder
import com.nuvio.remote.connection.NuvioBridgeStatus
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.connection.NuvioWakeInfo
import com.nuvio.remote.connection.NuvioWakeOnLan
import com.nuvio.remote.connection.ScreenProfile
import com.nuvio.remote.discovery.PcService
import com.nuvio.remote.pairing.PairResult
import com.nuvio.remote.pairing.PairingManager
import com.nuvio.remote.pairing.RequestPinResult
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioGlassSwitch
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.components.StatusLed
import com.nuvio.remote.ui.i18n.AppLanguage
import com.nuvio.remote.ui.i18n.LocalAppLanguage
import com.nuvio.remote.ui.i18n.ResolvedLanguage
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.AppThemeMode
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.accentForScreen
import com.nuvio.remote.ui.theme.nuvioGlass
import com.nuvio.remote.update.ManualUpdateCheckStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings tab with clean opaque cards, 6-digit PIN pairing inside a Liquid Glass dialog,
 * live EN/PT-BR language switcher, and jargon-free copy.
 */
@Composable
fun SettingsScreen(
    pairing: PairingManager,
    bridge: NuvioBridgeStateHolder,
    wakeInfo: NuvioWakeInfo?,
    connState: ConnState,
    savedHost: String,
    tunnelUrl: String?,
    onConnect: (host: String, port: Int) -> Unit,
    onDisconnect: () -> Unit = {},
    appLanguage: AppLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
    appThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
    onAppThemeModeChange: (AppThemeMode) -> Unit = {},
    hapticsEnabled: Boolean,
    onHapticsChange: (Boolean) -> Unit,
    defaultProfile: ScreenProfile,
    onDefaultProfileChange: (ScreenProfile) -> Unit,
    discovered: PcService?,
    onRefreshDiscovery: () -> Unit,
    installedVersion: String = "0.5.1",
    manualUpdateStatus: ManualUpdateCheckStatus = ManualUpdateCheckStatus.IDLE,
    onCheckForUpdates: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val lang = LocalAppLanguage.current
    var hostInput by remember(savedHost) { mutableStateOf(savedHost.ifBlank { PairingManager.DEFAULT_HOST }) }
    var isPaired by remember(pairing, connState) { mutableStateOf(pairing.isPaired()) }
    var showPinDialog by remember { mutableStateOf(false) }
    var qrScanTarget by remember { mutableStateOf<String?>(null) }
    var qrBridgePin by remember { mutableStateOf<String?>(null) }
    var qrPairStatus by remember { mutableStateOf<String?>(null) }
    var savedProfiles by remember(pairing, connState) { mutableStateOf(pairing.savedProfiles()) }
    var activeProfileId by remember(pairing, connState) { mutableStateOf(pairing.activeProfile()?.id) }
    val ctx = LocalContext.current
    val settingsPrefs = remember(ctx) { com.nuvio.remote.data.AppPrefs(ctx) }
    var pauseOnCall by remember { mutableStateOf(settingsPrefs.pauseOnCall) }
    var resumeAfterCall by remember { mutableStateOf(settingsPrefs.resumeAfterCall) }
    val showOnboarding = !isPaired && savedHost.isBlank()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            if (showOnboarding) {
                OnboardingConnectionSection(
                    discovered = discovered,
                    hostInput = hostInput,
                    qrStatusMessage = qrPairStatus,
                    onHostInputChange = { hostInput = it },
                    onUseDiscovered = { svc ->
                        hostInput = svc.ip
                        onConnect(svc.ip, svc.port)
                    },
                    onRefreshDiscovery = onRefreshDiscovery,
                    onOpenQrScanner = { qrScanTarget = "pc" },
                    onUseManualHost = {
                        val target = hostInput.trim()
                        if (target.isNotEmpty()) onConnect(target, 8787)
                    },
                    onOpenPinDialog = { showPinDialog = true },
                )
            } else {
                ConnectionAndPairingCard(
                    connState = connState,
                    wakeInfo = wakeInfo,
                    hostInput = hostInput,
                    onHostInputChange = { hostInput = it },
                    isPaired = isPaired,
                    discovered = discovered,
                    onUseDiscovered = { svc ->
                        hostInput = svc.ip
                        onConnect(svc.ip, svc.port)
                    },
                    onConnect = {
                        val target = hostInput.trim()
                        if (target.isNotEmpty()) onConnect(target, 8787)
                    },
                    onRefreshDiscovery = onRefreshDiscovery,
                    onOpenQrScanner = { qrScanTarget = "pc" },
                    onOpenPairDialog = { showPinDialog = true },
                    onUnpair = {
                        val targetHost = hostInput.trim().ifEmpty { savedHost }
                        isPaired = false
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                if (targetHost.isNotEmpty()) {
                                    pairing.revokeCurrentDevice(targetHost)
                                }
                            }
                            pairing.unpair()
                            onDisconnect()
                        }
                    },
                )
            }
        }

        if (savedProfiles.isNotEmpty()) {
            item {
                SavedPcsSettingsCard(
                    profiles = savedProfiles,
                    activeProfileId = activeProfileId,
                    onSelectProfile = { profile ->
                        val switched = pairing.switchActiveProfile(profile.id) ?: profile
                        savedProfiles = pairing.savedProfiles()
                        activeProfileId = switched.id
                        hostInput = switched.host
                        isPaired = pairing.isPaired()
                        onConnect(switched.host, switched.port)
                    },
                    onRenameProfile = { id, nextName ->
                        savedProfiles = pairing.renameProfile(id, nextName)
                    },
                    onRemoveProfile = { id ->
                        val removingActive = id == activeProfileId
                        savedProfiles = pairing.removeProfile(id)
                        activeProfileId = pairing.activeProfile()?.id
                        isPaired = pairing.isPaired()
                        if (removingActive) {
                            val next = pairing.activeProfile()
                            if (next != null) {
                                hostInput = next.host
                                onConnect(next.host, next.port)
                            } else {
                                onDisconnect()
                            }
                        }
                    },
                )
            }
        }

        item {
            NuvioBridgePairingCard(
                bridge = bridge,
                externalPin = qrBridgePin,
                onConsumeExternalPin = { qrBridgePin = null },
                onOpenQrScanner = { qrScanTarget = "bridge" },
            )
        }

        item {
            RemoteAccessCard(
                tunnelUrl = tunnelUrl,
                onConnectRemote = { url -> onConnect(url, 443) },
            )
        }

        item {
            CallAutoPauseSettingsCard(
                pauseOnCall = pauseOnCall,
                resumeAfterCall = resumeAfterCall,
                onPauseOnCallChange = { enabled ->
                    pauseOnCall = enabled
                    settingsPrefs.pauseOnCall = enabled
                },
                onResumeAfterCallChange = { enabled ->
                    resumeAfterCall = enabled
                    settingsPrefs.resumeAfterCall = enabled
                },
            )
        }

        item {
            InteractionAndScreenCard(
                appLanguage = appLanguage,
                onAppLanguageChange = onAppLanguageChange,
                appThemeMode = appThemeMode,
                onAppThemeModeChange = onAppThemeModeChange,
                hapticsEnabled = hapticsEnabled,
                onHapticsChange = onHapticsChange,
                defaultProfile = defaultProfile,
                onSelectProfile = onDefaultProfileChange,
                installedVersion = installedVersion,
                manualUpdateStatus = manualUpdateStatus,
                onCheckForUpdates = onCheckForUpdates,
            )
        }
    }

    if (showPinDialog) {
        PairPinDialog(
            pairing = pairing,
            host = hostInput.trim().ifEmpty { PairingManager.DEFAULT_HOST },
            onDismiss = { showPinDialog = false },
            onPairedSuccess = {
                isPaired = true
                showPinDialog = false
                onConnect(hostInput.trim().ifEmpty { PairingManager.DEFAULT_HOST }, 8787)
            },
        )
    }

    if (qrScanTarget != null) {
        val target = qrScanTarget
        QrScannerDialog(
            onScanned = { payload ->
                qrScanTarget = null
                handleQrPayload(
                    target = target,
                    payload = payload,
                    lang = lang,
                    pairing = pairing,
                    bridge = bridge,
                    scope = coroutineScope,
                    onHostResolved = { hostInput = it },
                    onStatus = { qrPairStatus = it },
                    onPaired = { host, port ->
                        isPaired = true
                        qrPairStatus = null
                        onConnect(host, port)
                    },
                    onBridgePin = { qrBridgePin = it },
                    onConnectOnly = onConnect,
                )
            },
            onManualFallback = {
                qrScanTarget = null
                if (target == "pc") showPinDialog = true
            },
            onDismiss = { qrScanTarget = null },
        )
    }
}

private fun formatPairFailureMessage(res: PairResult, host: String, lang: ResolvedLanguage): String = when (res) {
    is PairResult.WrongPin -> l10n(
        lang,
        "QR code expired or invalid. Generate a new code on the PC.",
        "Código QR expirado ou incorreto. Gere um novo código no PC.",
    )
    is PairResult.RateLimited -> l10n(
        lang,
        "Too many attempts. Wait ${res.retryAfterSeconds}s.",
        "Muitas tentativas. Aguarde ${res.retryAfterSeconds}s.",
    )
    PairResult.LanOnly -> l10n(
        lang,
        "Pairing is only allowed on local Wi-Fi.",
        "Pareamento permitido somente no Wi-Fi local.",
    )
    PairResult.NetworkError -> l10n(
        lang,
        "Could not reach $host on Wi-Fi.",
        "Não foi possível alcançar $host no Wi-Fi.",
    )
    is PairResult.Paired -> ""
}

@Suppress("LongParameterList")
private fun handleQrPayload(
    target: String?,
    payload: com.nuvio.remote.connection.QrPairPayload,
    lang: ResolvedLanguage,
    pairing: PairingManager,
    bridge: NuvioBridgeStateHolder,
    scope: kotlinx.coroutines.CoroutineScope,
    onHostResolved: (String) -> Unit,
    onStatus: (String?) -> Unit,
    onPaired: (String, Int) -> Unit,
    onBridgePin: (String) -> Unit,
    onConnectOnly: (String, Int) -> Unit,
) {
    val scannedHost = payload.host?.trim().orEmpty()
    val scannedPin = payload.pin?.trim().orEmpty()
    when {
        target == "bridge" -> if (scannedPin.length == 6) bridge.pairWithPin(scannedPin)
        scannedHost.isNotEmpty() && scannedPin.length == 6 -> {
            onHostResolved(scannedHost)
            onStatus(l10n(lang, "Pairing with $scannedHost…", "Pareando com $scannedHost…"))
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    pairing.pair(scannedHost, payload.port ?: 8765, scannedPin)
                }
                if (res is PairResult.Paired) {
                    val wsPort = if (payload.port == null || payload.port == 8765) 8787 else payload.port
                    onPaired(scannedHost, wsPort)
                } else {
                    onStatus(formatPairFailureMessage(res, scannedHost, lang))
                }
            }
        }
        scannedHost.isNotEmpty() -> {
            onHostResolved(scannedHost)
            onConnectOnly(scannedHost, payload.port ?: 8787)
        }
        scannedPin.length == 6 -> {
            onBridgePin(scannedPin)
            bridge.pairWithPin(scannedPin)
        }
    }
}

@Composable
private fun ConnectionAndPairingCard(
    connState: ConnState,
    wakeInfo: NuvioWakeInfo?,
    hostInput: String,
    onHostInputChange: (String) -> Unit,
    isPaired: Boolean,
    discovered: PcService?,
    onUseDiscovered: (PcService) -> Unit,
    onConnect: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onOpenPairDialog: () -> Unit,
    onUnpair: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    val isOffline = connState is ConnState.Disconnected || connState is ConnState.Failed
    val statusText = when (connState) {
        is ConnState.Connected -> l10n(
            lang,
            "Connected over local Wi-Fi (${connState.host})",
            "Conectado pela rede Wi-Fi de casa (${connState.host})",
        )
        is ConnState.Connecting -> l10n(lang, "Connecting to PC…", "Conectando ao computador…")
        is ConnState.Failed -> l10n(lang, "PC offline or asleep", "Computador desligado ou em suspensão")
        is ConnState.Disconnected -> l10n(lang, "PC offline or asleep", "Computador desligado ou em suspensão")
    }

    SettingsSectionCard(title = l10n("PC CONNECTION", "CONEXÃO COM O COMPUTADOR")) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioText(
                    text = l10n("Primary Computer", "Computador Principal"),
                    style = NuvioTypography.KeyLabelPrimary,
                    color = scheme.textPrimary,
                )
                StatusLed(connState = connState)
            }
            NuvioText(
                text = statusText,
                style = NuvioTypography.KeyShortcutBadge,
                color = if (connState is ConnState.Connected) scheme.statusOnline else scheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = l10n("Pairing status", "Estado do pareamento"),
                    tint = if (isPaired) scheme.statusOnline else scheme.textMuted,
                    modifier = Modifier.size(14.dp),
                )
                NuvioText(
                    text = if (isPaired) {
                        l10n("Device paired and authorized", "Dispositivo pareado e autorizado")
                    } else {
                        l10n(
                            "Pairing pending (local Wi-Fi only)",
                            "Pareamento pendente (somente no Wi-Fi de casa)",
                        )
                    },
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.textMuted,
                )
            }
        }

        if (discovered != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioIcon(
                        imageVector = Icons.Rounded.Wifi,
                        contentDescription = l10n("Computer on network", "Computador na rede"),
                        tint = scheme.statusOnline,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        NuvioText(
                            text = discovered.hostname,
                            style = NuvioTypography.KeyLabelCompact,
                            color = scheme.textPrimary,
                            maxLines = 1,
                        )
                        NuvioText(
                            text = discovered.ip,
                            style = NuvioTypography.KeyShortcutBadge,
                            color = scheme.textMuted,
                            maxLines = 1,
                        )
                    }
                }
                SettingsButton(
                    label = l10n("Use", "Usar"),
                    highlighted = false,
                    onClick = { onUseDiscovered(discovered) },
                )
            }
        }

        HostInputBox(
            value = hostInput,
            onValueChange = onHostInputChange,
            placeholder = l10n("PC local Wi-Fi address", "Endereço do PC no Wi-Fi"),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsButton(
                label = l10n("Scan Wi-Fi", "Buscar Wi-Fi"),
                highlighted = false,
                onClick = onRefreshDiscovery,
                modifier = Modifier.weight(1f),
            )
            SettingsButton(
                label = l10n("Scan QR", "Escanear QR"),
                highlighted = false,
                onClick = onOpenQrScanner,
                modifier = Modifier.weight(1f),
            )
            SettingsButton(
                label = if (isPaired) l10n("New PIN", "Novo código") else l10n("PIN", "Código"),
                highlighted = false,
                onClick = onOpenPairDialog,
                modifier = Modifier.weight(0.95f),
            )
        }

        ConnectAndWakeSection(
            isOffline = isOffline,
            wakeInfo = wakeInfo,
            onConnect = onConnect,
        )

        if (isPaired) {
            SettingsButton(
                label = l10n("Unpair this phone", "Remover pareamento deste celular"),
                highlighted = false,
                onClick = onUnpair,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ConnectAndWakeSection(isOffline: Boolean, wakeInfo: NuvioWakeInfo?, onConnect: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var wakeFeedback by remember { mutableStateOf<String?>(null) }
    var wakeSuccess by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsButton(
            label = if (isOffline) l10n("Reconnect", "Reconectar") else l10n("Connect now", "Conectar agora"),
            highlighted = !isOffline,
            onClick = onConnect,
            modifier = Modifier.weight(1f),
        )
        SettingsButton(
            label = l10n("Wake PC", "Acordar PC"),
            highlighted = isOffline,
            onClick = {
                if (wakeInfo == null || wakeInfo.macs.isEmpty()) {
                    wakeSuccess = false
                    wakeFeedback = l10n(
                        lang,
                        "Connect to Nuvio at least once to save your PC MAC address.",
                        "Conecte ao Nuvio pelo menos uma vez para salvar o endereço MAC do PC.",
                    )
                } else {
                    wakeSuccess = false
                    wakeFeedback = l10n(
                        lang,
                        "Sending Wake-on-LAN packet over Wi-Fi…",
                        "Enviando pacote Wake-on-LAN na rede Wi-Fi…",
                    )
                    scope.launch {
                        val sent = NuvioWakeOnLan.sendWake(wakeInfo)
                        wakeSuccess = sent
                        wakeFeedback = if (sent) {
                            l10n(
                                lang,
                                "Magic packet sent to PC (${wakeInfo.macs.size} adapter).",
                                "Pacote mágico enviado ao PC (${wakeInfo.macs.size} adaptador).",
                            )
                        } else {
                            l10n(
                                lang,
                                "Failed to send Wake-on-LAN. Check that you are on local Wi-Fi.",
                                "Falha ao enviar Wake-on-LAN. Confira se está no Wi-Fi de casa.",
                            )
                        }
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
    }

    wakeFeedback?.let { msg ->
        NuvioText(
            text = msg,
            style = NuvioTypography.KeyShortcutBadge,
            color = if (wakeSuccess) scheme.statusOnline else scheme.statusWarning,
        )
    }
}

@Composable
private fun NuvioBridgePairingCard(
    bridge: NuvioBridgeStateHolder,
    externalPin: String?,
    onConsumeExternalPin: () -> Unit,
    onOpenQrScanner: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val bridgeStatus by bridge.status.collectAsState()
    val pairFeedback by bridge.pairFeedback.collectAsState()
    var pinInput by remember { mutableStateOf("") }
    var showPinBox by remember { mutableStateOf(false) }
    val linked = bridgeStatus == NuvioBridgeStatus.CONNECTED

    if (!externalPin.isNullOrBlank()) {
        pinInput = externalPin.filter(Char::isDigit).take(6)
        showPinBox = true
        onConsumeExternalPin()
    }

    SettingsSectionCard(title = l10n("DIRECT NUVIO LINK", "CONTROLE DIRETO DO NUVIO")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (linked) scheme.statusOnline else scheme.statusWarning),
                )
                NuvioText(
                    text = if (linked) {
                        l10n("Synced with Nuvio on PC", "Sincronizado com o Nuvio no PC")
                    } else {
                        l10n("6-digit Nuvio code pending", "Código de 6 dígitos do Nuvio pendente")
                    },
                    style = NuvioTypography.KeyLabelCompact,
                    color = if (linked) scheme.statusOnline else scheme.textPrimary,
                )
            }
            val playerAccent = accentForScreen(NuvioScreenState.PLAYER)
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(playerAccent.surfaceTint)
                    .border(1.dp, playerAccent.primary.copy(alpha = 0.35f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                NuvioText(
                    text = if (linked) l10n("Active", "Ativo") else l10n("6-digit PIN", "Código 6 dígitos"),
                    style = NuvioTypography.KeyShortcutBadge,
                    color = playerAccent.primary,
                )
            }
        }

        NuvioText(
            text = if (linked && !showPinBox) {
                l10n(
                    "Receives artwork, progress, subtitles, audio tracks, sources, and music queue live.",
                    "Recebe pôster, progresso, legendas, faixas de áudio, fontes e fila de música em tempo real.",
                )
            } else {
                l10n(
                    "Open Settings > Mobile Remote in Nuvio on your PC and enter the code shown:",
                    "Abra Ajustes > Controle pelo celular no Nuvio do PC e digite o código exibido na tela:",
                )
            },
            style = NuvioTypography.KeyShortcutBadge,
            color = scheme.textMuted,
        )

        if (!linked || showPinBox) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(scheme.contentCellSurface)
                            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (pinInput.isEmpty()) {
                            NuvioText(
                                text = "000 000",
                                style = NuvioTypography.KeyLabelPrimary,
                                color = scheme.textMuted,
                            )
                        }
                        BasicTextField(
                            value = pinInput,
                            onValueChange = { raw -> pinInput = raw.filter(Char::isDigit).take(6) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            cursorBrush = SolidColor(scheme.textPrimary),
                            textStyle = NuvioTypography.KeyLabelPrimary.copy(
                                color = scheme.textPrimary,
                                textAlign = TextAlign.Center,
                                letterSpacing = 2.sp,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                SettingsButton(
                    label = "QR",
                    highlighted = false,
                    onClick = onOpenQrScanner,
                    modifier = Modifier.weight(0.55f),
                )
                SettingsButton(
                    label = l10n("Link", "Vincular"),
                    highlighted = true,
                    enabled = pinInput.length == 6,
                    onClick = {
                        bridge.pairWithPin(pinInput)
                        pinInput = ""
                        showPinBox = false
                    },
                    modifier = Modifier.weight(0.85f),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsButton(
                    label = l10n("Scan QR", "Escanear QR"),
                    highlighted = false,
                    onClick = onOpenQrScanner,
                    modifier = Modifier.weight(1f),
                )
                SettingsButton(
                    label = l10n("New code", "Novo código"),
                    highlighted = false,
                    onClick = { showPinBox = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        pairFeedback?.let { fb ->
            NuvioText(
                text = fb.message,
                style = NuvioTypography.KeyShortcutBadge,
                color = if (fb.ok) scheme.statusOnline else scheme.statusError,
            )
        }
    }
}

@Composable
private fun RemoteAccessCard(tunnelUrl: String?, onConnectRemote: (String) -> Unit) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    var copiedNotice by remember(tunnelUrl) { mutableStateOf(false) }
    val hasTunnel = !tunnelUrl.isNullOrBlank()

    SettingsSectionCard(title = l10n("REMOTE ACCESS", "ACESSO FORA DE CASA")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (hasTunnel) scheme.statusOnline else scheme.textMuted),
            )
            NuvioText(
                text = if (hasTunnel) {
                    l10n("Ready for mobile data (4G/5G)", "Pronto para usar no 4G/5G")
                } else {
                    l10n("Remote tunnel off or waiting for PC", "Aguardando endereço remoto do computador")
                },
                style = NuvioTypography.KeyLabelCompact,
                color = if (hasTunnel) scheme.statusOnline else scheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        NuvioText(
            text = if (copiedNotice) {
                l10n(
                    "Remote URL copied to clipboard.",
                    "Endereço remoto copiado para a área de transferência.",
                )
            } else if (hasTunnel) {
                l10n(
                    "When leaving home Wi-Fi, the app keeps a secure connection through your PC's remote tunnel URL.",
                    "Quando sair do Wi-Fi de casa, o aplicativo mantém a conexão segura pelo endereço " +
                        "remoto gerado no computador.",
                )
            } else {
                l10n(
                    "Optional: enable Remote Tunnel in the Windows Companion on home Wi-Fi to sync access away.",
                    "Conecte ao computador no Wi-Fi de casa para sincronizar automaticamente o acesso fora de casa.",
                )
            },
            style = NuvioTypography.KeyShortcutBadge,
            color = if (copiedNotice) scheme.statusOnline else scheme.textMuted,
        )

        if (hasTunnel) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsButton(
                    label = l10n("Connect remote", "Conectar remoto"),
                    highlighted = true,
                    onClick = { onConnectRemote(tunnelUrl!!) },
                    modifier = Modifier.weight(1.25f),
                )
                SettingsButton(
                    label = l10n("Copy link", "Copiar link"),
                    highlighted = false,
                    onClick = {
                        val mgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        mgr?.setPrimaryClip(ClipData.newPlainText("Nuvio DeskRemote", tunnelUrl))
                        if (hapticsEnabled) Haptics.action(context)
                        copiedNotice = true
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun InteractionAndScreenCard(
    appLanguage: AppLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
    appThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
    onAppThemeModeChange: (AppThemeMode) -> Unit = {},
    hapticsEnabled: Boolean,
    onHapticsChange: (Boolean) -> Unit,
    defaultProfile: ScreenProfile,
    onSelectProfile: (ScreenProfile) -> Unit,
    installedVersion: String,
    manualUpdateStatus: ManualUpdateCheckStatus,
    onCheckForUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = LocalNuvioColors.current

    SettingsSectionCard(title = l10n("INTERACTION & DISPLAY", "INTERAÇÃO E TELA")) {
        NuvioText(
            text = l10n("App language:", "Idioma do aplicativo:"),
            style = NuvioTypography.KeyShortcutBadge,
            color = scheme.textSecondary,
        )

        LanguageSelectorRow(
            selectedLanguage = appLanguage,
            hapticsEnabled = hapticsEnabled,
            onSelectLanguage = onAppLanguageChange,
        )

        NuvioText(
            text = l10n("Appearance:", "Aparência:"),
            style = NuvioTypography.KeyShortcutBadge,
            color = scheme.textSecondary,
        )

        ThemeSelectorRow(
            selectedTheme = appThemeMode,
            hapticsEnabled = hapticsEnabled,
            onSelectTheme = onAppThemeModeChange,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                .clickable {
                    val next = !hapticsEnabled
                    if (next) Haptics.toggle(context)
                    onHapticsChange(next)
                }
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                NuvioText(
                    text = l10n("Haptic feedback", "Vibração tátil ao tocar"),
                    style = NuvioTypography.KeyLabelPrimary,
                    color = scheme.textPrimary,
                )
                NuvioText(
                    text = l10n(
                        "Tactile response on buttons and trackpad",
                        "Resposta física ao tocar nos botões e trackpad",
                    ),
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.textMuted,
                )
            }
            NuvioGlassSwitch(
                checked = hapticsEnabled,
                onCheckedChange = { next ->
                    if (next) Haptics.toggle(context)
                    onHapticsChange(next)
                },
            )
        }

        NuvioText(
            text = l10n("Default screen share quality:", "Qualidade padrão do espelhamento de tela:"),
            style = NuvioTypography.KeyShortcutBadge,
            color = scheme.textSecondary,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp)),
            )
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScreenProfile.entries.forEach { profile ->
                    val selected = profile == defaultProfile
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (hapticsEnabled) Haptics.tab(context)
                                onSelectProfile(profile)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 3.dp)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom),
                                        ),
                                    ),
                            )
                        }
                        NuvioText(
                            text = profile.shortLabel.replace(" ", " • "),
                            style = NuvioTypography.KeyShortcutBadge,
                            color = if (selected) scheme.pillActiveFg else scheme.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }

        SettingsButton(
            label = if (manualUpdateStatus == ManualUpdateCheckStatus.CHECKING) {
                l10n("Checking for updates…", "Verificando atualizações…")
            } else {
                l10n("Check for updates (v$installedVersion)", "Verificar atualizações (v$installedVersion)")
            },
            highlighted = false,
            enabled = manualUpdateStatus != ManualUpdateCheckStatus.CHECKING,
            onClick = onCheckForUpdates,
            modifier = Modifier.fillMaxWidth(),
        )

        if (manualUpdateStatus == ManualUpdateCheckStatus.UP_TO_DATE) {
            NuvioText(
                text = l10n(
                    "You are on the latest version (v$installedVersion).",
                    "Você já está na versão mais recente (v$installedVersion).",
                ),
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.statusOnline,
            )
        } else if (manualUpdateStatus == ManualUpdateCheckStatus.FAILED) {
            NuvioText(
                text = l10n(
                    "Could not reach GitHub Releases. Check your internet connection.",
                    "Não foi possível consultar o GitHub Releases. Verifique sua conexão.",
                ),
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.statusWarning,
            )
        }

        NuvioText(
            text = l10n(
                "Nuvio DeskRemote v$installedVersion • Liquid Glass optical theme.",
                "Nuvio DeskRemote v$installedVersion • Tema óptico Liquid Glass.",
            ),
            style = NuvioTypography.KeyShortcutBadge,
            color = scheme.textMuted,
        )
    }
}

@Composable
private fun ThemeSelectorRow(
    selectedTheme: AppThemeMode,
    hapticsEnabled: Boolean,
    onSelectTheme: (AppThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val scheme = LocalNuvioColors.current
    val options = listOf(
        AppThemeMode.SYSTEM to l10n("System", "Sistema"),
        AppThemeMode.DARK to l10n("Dark", "Escuro"),
        AppThemeMode.LIGHT to l10n("Light", "Claro"),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp)),
        )
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (theme, label) ->
                val selected = theme == selectedTheme
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (hapticsEnabled) Haptics.tab(context)
                            onSelectTheme(theme)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 3.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom),
                                    ),
                                ),
                        )
                    }
                    NuvioText(
                        text = label,
                        style = NuvioTypography.KeyShortcutBadge.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (selected) scheme.pillActiveFg else scheme.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageSelectorRow(
    selectedLanguage: AppLanguage,
    hapticsEnabled: Boolean,
    onSelectLanguage: (AppLanguage) -> Unit,
) {
    val context = LocalContext.current
    val scheme = LocalNuvioColors.current
    val options = listOf(
        AppLanguage.SYSTEM to l10n("System", "Sistema"),
        AppLanguage.ENGLISH to "English",
        AppLanguage.PORTUGUESE_BR to "Português",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp)),
        )
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (language, label) ->
                val selected = language == selectedLanguage
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (hapticsEnabled) Haptics.tab(context)
                            onSelectLanguage(language)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 3.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom),
                                    ),
                                ),
                        )
                    }
                    NuvioText(
                        text = label,
                        style = NuvioTypography.KeyShortcutBadge.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (selected) scheme.pillActiveFg else scheme.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PairPinDialog(pairing: PairingManager, host: String, onDismiss: () -> Unit, onPairedSuccess: () -> Unit) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember(lang) {
        mutableStateOf(
            l10n(
                lang,
                "1. Tap request to show the 6-digit code on your PC screen.\n" +
                    "2. Enter the digits below to authorize this phone.",
                "1. Toque em solicitar para exibir o código de 6 dígitos na tela do computador.\n" +
                    "2. Digite os números abaixo para autorizar este celular.",
            ),
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .nuvioGlass(
                    shape = RoundedCornerShape(26.dp),
                    variant = GlassVariant.REGULAR,
                    elevated = true,
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NuvioText(
                text = l10n("PIN CODE PAIRING", "PAREAMENTO POR CÓDIGO"),
                style = NuvioTypography.SectionMicroTitle,
                color = scheme.textPrimary,
            )
            NuvioText(
                text = statusMessage,
                style = NuvioTypography.BodyRegular,
                color = scheme.textSecondary,
            )

            SettingsButton(
                label = l10n("Request code on PC", "Solicitar código no computador"),
                highlighted = false,
                enabled = !busy,
                onClick = {
                    busy = true
                    statusMessage = l10n(
                        lang,
                        "Requesting 6-digit code on the PC screen…",
                        "Solicitando código de 6 dígitos na tela do computador…",
                    )
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { pairing.requestPin(host) }
                        busy = false
                        statusMessage = when (res) {
                            is RequestPinResult.Success -> l10n(
                                lang,
                                "Code displayed on PC for ${res.expiresInSeconds}s.",
                                "Código exibido no computador por ${res.expiresInSeconds}s.",
                            )
                            is RequestPinResult.LanOnly -> l10n(
                                lang,
                                "Only available on the same local Wi-Fi as your PC.",
                                "Disponível apenas conectado ao mesmo Wi-Fi do PC.",
                            )
                            is RequestPinResult.RateLimited -> l10n(
                                lang,
                                "Too many attempts. Wait ${res.retryAfterSeconds}s.",
                                "Muitas tentativas. Aguarde ${res.retryAfterSeconds}s.",
                            )
                            is RequestPinResult.NetworkError -> l10n(
                                lang,
                                "PC did not respond at this address.",
                                "Computador não respondeu neste endereço.",
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            PinCodeBoxes(
                pin = pin,
                onPinChange = { raw -> pin = raw.filter { it.isDigit() }.take(6) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsButton(
                    label = l10n("Cancel", "Cancelar"),
                    highlighted = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                SettingsButton(
                    label = l10n("Confirm", "Confirmar"),
                    highlighted = true,
                    enabled = !busy && pin.length == 6,
                    onClick = {
                        busy = true
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { pairing.pair(host, pin = pin) }
                            busy = false
                            when (res) {
                                is PairResult.Paired -> {
                                    if (hapticsEnabled) Haptics.ok(context)
                                    onPairedSuccess()
                                }
                                is PairResult.WrongPin -> {
                                    statusMessage = l10n(
                                        lang,
                                        "Incorrect code. Attempts left: ${res.attemptsRemaining}.",
                                        "Código incorreto. Tentativas restantes: ${res.attemptsRemaining}.",
                                    )
                                }
                                is PairResult.LanOnly -> {
                                    statusMessage = l10n(
                                        lang,
                                        "PIN pairing only works on the same Wi-Fi as your PC.",
                                        "O pareamento por código só funciona no mesmo Wi-Fi do PC.",
                                    )
                                }
                                is PairResult.RateLimited -> {
                                    statusMessage = l10n(
                                        lang,
                                        "Temporarily blocked. Wait ${res.retryAfterSeconds}s.",
                                        "Bloqueado temporariamente. Aguarde ${res.retryAfterSeconds}s.",
                                    )
                                }
                                is PairResult.NetworkError -> {
                                    statusMessage = l10n(
                                        lang,
                                        "Could not reach the computer.",
                                        "Não foi possível comunicar com o computador.",
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1.2f),
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun PinCodeBoxes(pin: String, onPinChange: (String) -> Unit) {
    val scheme = LocalNuvioColors.current

    BasicTextField(
        value = pin,
        onValueChange = onPinChange,
        singleLine = true,
        cursorBrush = SolidColor(scheme.textPrimary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(6) { idx ->
                    val ch = pin.getOrNull(idx)?.toString() ?: ""
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(scheme.contentCellSurface)
                            .border(
                                width = if (idx == pin.length && pin.length < 6) 1.5.dp else 1.dp,
                                color = if (idx == pin.length && pin.length < 6) {
                                    scheme.glassRim
                                } else {
                                    scheme.contentBorder
                                },
                                shape = RoundedCornerShape(14.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioText(
                            text = if (ch.isEmpty()) "•" else ch,
                            style = NuvioTypography.DisplayKey.copy(
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum",
                            ),
                            color = if (ch.isEmpty()) scheme.textMuted else scheme.textPrimary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsSectionCard(title: String, content: @Composable () -> Unit) {
    val scheme = LocalNuvioColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(22.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NuvioText(
            text = title,
            style = NuvioTypography.SectionMicroTitle,
            color = scheme.textMuted,
        )
        content()
    }
}

@Composable
private fun HostInputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(13.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                NuvioText(placeholder, style = NuvioTypography.BodyRegular, color = scheme.textMuted)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(scheme.textPrimary),
                textStyle = NuvioTypography.BodyRegular.copy(color = scheme.textPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingsButton(
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = LocalNuvioColors.current
    val fg = if (highlighted) scheme.pillActiveFg else scheme.textPrimary
    val bgModifier = if (highlighted) {
        Modifier.background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
    } else {
        Modifier
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(13.dp))
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(13.dp))
                .then(bgModifier)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = if (enabled) fg else scheme.textMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
