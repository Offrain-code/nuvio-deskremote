package com.nuvio.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.AppsSubTab
import com.nuvio.remote.connection.ConnState
import com.nuvio.remote.connection.NuvioConnection
import com.nuvio.remote.connection.NuvioRoute
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.connection.NuvioTab
import com.nuvio.remote.connection.ScreenProfile
import com.nuvio.remote.connection.popNuvioRoute
import com.nuvio.remote.connection.pushNuvioRoute
import com.nuvio.remote.data.AppPrefs
import com.nuvio.remote.discovery.PcService
import com.nuvio.remote.pairing.PairingManager
import com.nuvio.remote.ui.components.AutoUpdateDialog
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.components.StatusLed
import com.nuvio.remote.ui.i18n.AppLanguage
import com.nuvio.remote.ui.i18n.LocalAppLanguage
import com.nuvio.remote.ui.i18n.ResolvedLanguage
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.screens.AppsScreen
import com.nuvio.remote.ui.screens.CinemaDarkScreen
import com.nuvio.remote.ui.screens.LiveTvOverlaySheet
import com.nuvio.remote.ui.screens.RemoteScreen
import com.nuvio.remote.ui.screens.ScreenShareScreen
import com.nuvio.remote.ui.screens.SettingsScreen
import com.nuvio.remote.ui.screens.TonightOverlaySheet
import com.nuvio.remote.ui.screens.TrackpadScreen
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioAmbientBackdropStage
import com.nuvio.remote.ui.theme.NuvioGlassTheme
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.accentForScreen
import com.nuvio.remote.ui.theme.nuvioGlass
import com.nuvio.remote.ui.theme.rememberGlassBackdropState
import com.nuvio.remote.update.AppUpdateController
import com.nuvio.remote.update.ManualUpdateCheckStatus
import com.nuvio.remote.webrtc.ScreenStreamController

/**
 * Visual root using the single Liquid Glass theme, shared atmospheric backdrop stage,
 * and strict separation between Nuvio Mode (Remote, Apps) and PC Mode (Trackpad, Screen).
 */
@Composable
fun NuvioRemoteRoot(
    conn: NuvioConnection,
    pairing: PairingManager,
    controller: ScreenStreamController,
    tab: NuvioTab,
    onTabChange: (NuvioTab) -> Unit,
    appLanguage: AppLanguage,
    resolvedLanguage: ResolvedLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
    hapticsEnabled: Boolean,
    onHapticsChange: (Boolean) -> Unit,
    defaultProfile: ScreenProfile,
    onDefaultProfileChange: (ScreenProfile) -> Unit,
    discovered: PcService?,
    onRefreshDiscovery: () -> Unit,
    onConnect: (host: String, port: Int) -> Unit,
) {
    val context = LocalContext.current
    val appPrefs = remember(context) { AppPrefs(context) }
    val updateController = remember(context, appPrefs) {
        AppUpdateController(context.applicationContext, appPrefs)
    }
    val updateUiState by updateController.uiState.collectAsState()
    val manualUpdateStatus by updateController.manualCheckStatus.collectAsState()
    val backdropState = rememberGlassBackdropState()

    DisposableEffect(updateController) {
        onDispose { updateController.dispose() }
    }
    LaunchedEffect(updateController) {
        updateController.checkForUpdates(manual = false)
    }
    val connState by conn.connState.collectAsState()
    val serverState by conn.serverState.collectAsState()
    val lastError by conn.lastError.collectAsState()
    val textInputFocusTick by conn.textInputFocusTick.collectAsState()
    val pingMs by conn.pingMs.collectAsState()
    val isTunnelActive by conn.isTunnelActive.collectAsState()
    val liveWakeInfo by conn.bridge.wakeInfo.collectAsState()
    val nowPlaying by conn.bridge.nowPlaying.collectAsState()

    val backStack = remember { mutableStateListOf(NuvioRoute.fromTab(tab)) }
    var isScreenImmersive by remember { mutableStateOf(false) }
    var isCinemaMode by rememberSaveable { mutableStateOf(false) }
    var activeAppsSubTab by rememberSaveable { mutableStateOf(AppsSubTab.WATCH) }
    var activeMonitor by remember { mutableIntStateOf(appPrefs.screenMonitor.coerceIn(1, 2)) }
    var curtainActive by remember { mutableStateOf(false) }

    LaunchedEffect(liveWakeInfo) {
        liveWakeInfo?.let { appPrefs.saveWakeInfo(it) }
    }

    fun navigateToTab(targetTab: NuvioTab) {
        val next = pushNuvioRoute(backStack.toList(), NuvioRoute.fromTab(targetTab))
        backStack.clear()
        backStack.addAll(next)
        onTabChange(targetTab)
    }

    LaunchedEffect(tab) {
        syncBackStackForTab(backStack, tab)
        if (tab != NuvioTab.SCREEN) isScreenImmersive = false
    }

    LaunchedEffect(connState) {
        if (connState !is ConnState.Connected) curtainActive = false
    }

    val currentRoute = backStack.lastOrNull() ?: NuvioRoute.Remote
    BackHandler(enabled = backStack.size > 1 && !isScreenImmersive) {
        val popped = popNuvioRoute(backStack.toList())
        backStack.clear()
        backStack.addAll(popped)
        onTabChange(popped.last().tab)
    }

    NuvioGlassTheme(
        hapticsEnabled = hapticsEnabled,
        backdropState = backdropState,
        resolvedLanguage = resolvedLanguage,
    ) {
        val reducedMotion = LocalReducedMotion.current
        val ambientState = if (currentRoute is NuvioRoute.Remote) serverState else NuvioScreenState.HOME

        if (isCinemaMode) {
            CinemaDarkScreen(
                nowPlaying = nowPlaying,
                onKey = { conn.sendKey(it) },
                onSeekTo = { conn.bridge.seekTo(it) },
                onSetVolume = { conn.bridge.setVolume(it) },
                onExit = { isCinemaMode = false },
            )
        } else {
            NuvioAmbientBackdropStage(
                backdropState = backdropState,
                activeContext = ambientState,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isScreenImmersive) {
                                Modifier
                            } else {
                                Modifier.windowInsetsPadding(WindowInsets.systemBars)
                            },
                        ),
                ) {
                    if (!isScreenImmersive) {
                        NuvioGlassTopBar(
                            currentRoute = currentRoute,
                            connState = connState,
                            serverState = serverState,
                            appsSubTab = activeAppsSubTab,
                            pingMs = pingMs,
                            isTunnelActive = isTunnelActive,
                            curtainActive = curtainActive,
                            activeMonitor = activeMonitor,
                            onStatusClick = {
                                if (!lastError.isNullOrBlank()) conn.clearError() else navigateToTab(NuvioTab.SETTINGS)
                            },
                            onSelectNuvioState = { conn.sendSetState(it) },
                            onSelectAppsSubTab = { activeAppsSubTab = it },
                            onAltTab = { conn.sendKey("AltTab") },
                            onToggleCurtain = {
                                val next = !curtainActive
                                curtainActive = next
                                conn.sendWindow(if (next) "curtain_on" else "curtain_off")
                            },
                            onSelectMonitor = { monitor ->
                                activeMonitor = monitor
                                appPrefs.screenMonitor = monitor
                            },
                        )
                        ConnectionErrorBanner(
                            error = lastError,
                            onDismiss = {
                                if (hapticsEnabled) Haptics.tab(context)
                                conn.clearError()
                            },
                        )
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AnimatedContent(
                            targetState = currentRoute,
                            transitionSpec = {
                                val pair = NuvioMotion.morphEnter(reducedMotion) togetherWith
                                    NuvioMotion.morphExit(reducedMotion)
                                pair.using(SizeTransform(clip = false))
                            },
                            label = "nuvio_route_transition",
                        ) { route ->
                            NuvioRouteHost(
                                route = route,
                                conn = conn,
                                pairing = pairing,
                                appPrefs = appPrefs,
                                controller = controller,
                                connState = connState,
                                serverState = serverState,
                                appsSubTab = activeAppsSubTab,
                                wakeInfo = liveWakeInfo ?: appPrefs.savedWakeInfo(),
                                activeMonitor = activeMonitor,
                                onMonitorChange = { monitor ->
                                    activeMonitor = monitor
                                    appPrefs.screenMonitor = monitor
                                },
                                curtainActive = curtainActive,
                                onCurtainChange = { curtainActive = it },
                                textInputFocusTick = textInputFocusTick,
                                appLanguage = appLanguage,
                                onAppLanguageChange = onAppLanguageChange,
                                hapticsEnabled = hapticsEnabled,
                                onHapticsChange = onHapticsChange,
                                defaultProfile = defaultProfile,
                                onDefaultProfileChange = onDefaultProfileChange,
                                discovered = discovered,
                                onRefreshDiscovery = onRefreshDiscovery,
                                onConnect = onConnect,
                                onImmersiveChange = { isScreenImmersive = it },
                                onOpenCinemaMode = { isCinemaMode = true },
                                onNavigateTab = ::navigateToTab,
                                installedVersion = updateController.installedVersion,
                                manualUpdateStatus = manualUpdateStatus,
                                onCheckForUpdates = { updateController.checkForUpdates(manual = true) },
                            )
                        }

                        Phase3NuvioOverlaysHost(
                            currentRoute = currentRoute,
                            conn = conn,
                        )
                    }

                    if (!isScreenImmersive) {
                        NuvioGlassDock(
                            selectedTab = currentRoute.tab,
                            onSelectTab = ::navigateToTab,
                        )
                    }
                }

                AutoUpdateDialog(
                    state = updateUiState,
                    onUpdateNow = { updateController.startDownload() },
                    onLater = { updateController.dismissLater() },
                    onCancelDownload = { updateController.cancelDownload() },
                    onInstallNow = { updateController.installReadyApk() },
                    onRetry = { updateController.retry() },
                    onCloseError = { updateController.closeModal() },
                )
            }
        }
    }
}

private fun syncBackStackForTab(backStack: MutableList<NuvioRoute>, tab: NuvioTab) {
    if (backStack.lastOrNull()?.tab == tab) return
    val next = pushNuvioRoute(backStack.toList(), NuvioRoute.fromTab(tab))
    backStack.clear()
    backStack.addAll(next)
}

@Composable
private fun Phase3NuvioOverlaysHost(currentRoute: NuvioRoute, conn: NuvioConnection) {
    val liveTvScreen by conn.bridge.phase3.liveTv.collectAsState()
    val tonightScreen by conn.bridge.phase3.tonight.collectAsState()
    var dismissedLiveTv by remember { mutableStateOf(false) }
    var dismissedTonight by remember { mutableStateOf(false) }

    LaunchedEffect(liveTvScreen == null) {
        if (liveTvScreen == null) dismissedLiveTv = false
    }
    LaunchedEffect(tonightScreen == null) {
        if (tonightScreen == null) dismissedTonight = false
    }

    val inNuvioMode = currentRoute is NuvioRoute.Remote || currentRoute is NuvioRoute.Apps
    LiveTvOverlaySheet(
        screen = liveTvScreen,
        visible = inNuvioMode && liveTvScreen != null && !dismissedLiveTv,
        onDismiss = { dismissedLiveTv = true },
        onLiveTvDo = { conn.bridge.phase3.sendLiveTvDo(it) },
        onKey = { conn.sendKey(it) },
    )
    TonightOverlaySheet(
        screen = tonightScreen,
        visible = inNuvioMode && tonightScreen != null && !dismissedTonight,
        onDismiss = { dismissedTonight = true },
        onTonightDo = { action, num -> conn.bridge.phase3.sendTonightDo(action, num) },
    )
}

@Composable
private fun NuvioRouteHost(
    route: NuvioRoute,
    conn: NuvioConnection,
    pairing: PairingManager,
    appPrefs: AppPrefs,
    controller: ScreenStreamController,
    connState: ConnState,
    serverState: NuvioScreenState,
    appsSubTab: AppsSubTab,
    wakeInfo: com.nuvio.remote.connection.NuvioWakeInfo?,
    activeMonitor: Int,
    onMonitorChange: (Int) -> Unit,
    curtainActive: Boolean,
    onCurtainChange: (Boolean) -> Unit,
    textInputFocusTick: Long,
    appLanguage: AppLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
    hapticsEnabled: Boolean,
    onHapticsChange: (Boolean) -> Unit,
    defaultProfile: ScreenProfile,
    onDefaultProfileChange: (ScreenProfile) -> Unit,
    discovered: PcService?,
    onRefreshDiscovery: () -> Unit,
    onConnect: (host: String, port: Int) -> Unit,
    onImmersiveChange: (Boolean) -> Unit,
    onOpenCinemaMode: () -> Unit,
    onNavigateTab: (NuvioTab) -> Unit,
    installedVersion: String,
    manualUpdateStatus: ManualUpdateCheckStatus,
    onCheckForUpdates: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (route) {
            NuvioRoute.Remote -> RemoteScreen(
                serverState = serverState,
                bridge = conn.bridge,
                textInputFocusTick = textInputFocusTick,
                onNav = { conn.sendNav(it) },
                onKey = { conn.sendKey(it) },
                onType = { conn.sendType(it) },
                onSelectState = { conn.sendSetState(it) },
                onOpenAppsTab = { onNavigateTab(NuvioTab.APPS) },
                onOpenCinemaMode = onOpenCinemaMode,
            )
            NuvioRoute.Trackpad -> TrackpadScreen(
                conn = conn,
                initialSensitivity = appPrefs.mouseSensitivity,
                onSensitivityChange = { appPrefs.mouseSensitivity = it },
            )
            NuvioRoute.Screen -> ScreenShareScreen(
                conn = conn,
                controller = controller,
                defaultProfile = defaultProfile,
                onDefaultProfileChange = onDefaultProfileChange,
                activeMonitor = activeMonitor,
                onMonitorChange = onMonitorChange,
                onImmersiveChange = onImmersiveChange,
                curtainActive = curtainActive,
                onCurtainChange = onCurtainChange,
            )
            NuvioRoute.Apps -> AppsScreen(
                subTab = appsSubTab,
                bridge = conn.bridge,
                onKey = { conn.sendKey(it) },
                onWindowAction = { conn.sendWindow(it) },
                onTogglePlayerMode = {
                    val next = if (serverState == NuvioScreenState.PLAYER) {
                        NuvioScreenState.HOME
                    } else {
                        NuvioScreenState.PLAYER
                    }
                    conn.sendSetState(next)
                },
                onOpenRemoteTab = { onNavigateTab(NuvioTab.REMOTE) },
            )
            NuvioRoute.Settings -> {
                val tunnelUrl by conn.tunnelUrl.collectAsState()
                SettingsScreen(
                    pairing = pairing,
                    bridge = conn.bridge,
                    wakeInfo = wakeInfo,
                    connState = connState,
                    savedHost = pairing.savedHost ?: "",
                    tunnelUrl = tunnelUrl,
                    onConnect = onConnect,
                    onDisconnect = { conn.disconnect() },
                    appLanguage = appLanguage,
                    onAppLanguageChange = onAppLanguageChange,
                    hapticsEnabled = hapticsEnabled,
                    onHapticsChange = onHapticsChange,
                    defaultProfile = defaultProfile,
                    onDefaultProfileChange = onDefaultProfileChange,
                    discovered = discovered,
                    onRefreshDiscovery = onRefreshDiscovery,
                    installedVersion = installedVersion,
                    manualUpdateStatus = manualUpdateStatus,
                    onCheckForUpdates = onCheckForUpdates,
                )
            }
        }
    }
}

@Composable
private fun NuvioGlassTopBar(
    currentRoute: NuvioRoute,
    connState: ConnState,
    serverState: NuvioScreenState,
    appsSubTab: AppsSubTab,
    pingMs: Int,
    isTunnelActive: Boolean,
    curtainActive: Boolean,
    activeMonitor: Int,
    onStatusClick: () -> Unit,
    onSelectNuvioState: (NuvioScreenState) -> Unit,
    onSelectAppsSubTab: (AppsSubTab) -> Unit,
    onAltTab: () -> Unit,
    onToggleCurtain: () -> Unit,
    onSelectMonitor: (Int) -> Unit,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    val accent = accentForScreen(serverState)
    val barTint = when {
        connState is ConnState.Failed -> scheme.statusError
        connState is ConnState.Connecting -> scheme.statusWarning
        currentRoute is NuvioRoute.Remote && accent.surfaceTint.alpha > 0.01f -> accent.primary
        else -> Color.Unspecified
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(48.dp)
            .nuvioGlass(
                shape = CircleShape,
                variant = GlassVariant.REGULAR,
                elevated = connState is ConnState.Connected,
                accentTint = barTint,
            )
            .padding(start = 14.dp, end = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (hapticsEnabled) Haptics.tab(context)
                    onStatusClick()
                },
        ) {
            StatusLed(connState = connState)
            Spacer(Modifier.width(7.dp))
            NuvioText(
                text = formatConnectionStatus(connState, isTunnelActive, pingMs, lang),
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(6.dp))
        TopBarContextSlot(
            currentRoute = currentRoute,
            connState = connState,
            serverState = serverState,
            appsSubTab = appsSubTab,
            curtainActive = curtainActive,
            activeMonitor = activeMonitor,
            onSelectNuvioState = onSelectNuvioState,
            onSelectAppsSubTab = onSelectAppsSubTab,
            onAltTab = onAltTab,
            onToggleCurtain = onToggleCurtain,
            onSelectMonitor = onSelectMonitor,
        )
    }
}

@Composable
private fun TopBarContextSlot(
    currentRoute: NuvioRoute,
    connState: ConnState,
    serverState: NuvioScreenState,
    appsSubTab: AppsSubTab,
    curtainActive: Boolean,
    activeMonitor: Int,
    onSelectNuvioState: (NuvioScreenState) -> Unit,
    onSelectAppsSubTab: (AppsSubTab) -> Unit,
    onAltTab: () -> Unit,
    onToggleCurtain: () -> Unit,
    onSelectMonitor: (Int) -> Unit,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current

    when (currentRoute) {
        NuvioRoute.Remote -> NuvioModeSegmentedControl(
            serverState = serverState,
            onSelectState = { state ->
                if (hapticsEnabled) Haptics.toggle(context)
                onSelectNuvioState(state)
            },
        )
        NuvioRoute.Apps -> AppsSubTabSegmentedControl(
            selectedTab = appsSubTab,
            onSelect = { target ->
                if (hapticsEnabled) Haptics.toggle(context)
                onSelectAppsSubTab(target)
            },
        )
        NuvioRoute.Trackpad -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TopActionPill(
                label = "Alt+Tab",
                icon = Icons.Rounded.SwapHoriz,
                active = false,
                onClick = {
                    if (hapticsEnabled) Haptics.action(context)
                    onAltTab()
                },
            )
            TopActionPill(
                label = l10n("Curtain", "Cortina"),
                icon = Icons.Rounded.VisibilityOff,
                active = curtainActive,
                onClick = {
                    if (hapticsEnabled) Haptics.toggle(context)
                    onToggleCurtain()
                },
            )
        }
        NuvioRoute.Screen -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TopActionPill(
                label = "PC 1",
                icon = null,
                active = activeMonitor == 1,
                onClick = {
                    if (hapticsEnabled) Haptics.toggle(context)
                    onSelectMonitor(1)
                },
            )
            TopActionPill(
                label = "TV 2",
                icon = null,
                active = activeMonitor == 2,
                onClick = {
                    if (hapticsEnabled) Haptics.toggle(context)
                    onSelectMonitor(2)
                },
            )
        }
        NuvioRoute.Settings -> Box(
            modifier = Modifier
                .height(34.dp)
                .clip(CircleShape)
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, CircleShape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            NuvioText(
                text = if (connState is ConnState.Connected) {
                    l10n("Paired", "Pareamento ativo")
                } else {
                    l10n("Not paired", "Não pareado")
                },
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun NuvioModeSegmentedControl(serverState: NuvioScreenState, onSelectState: (NuvioScreenState) -> Unit) {
    val scheme = LocalNuvioColors.current
    val density = LocalDensity.current
    val reducedMotion = LocalReducedMotion.current
    val lang = LocalAppLanguage.current
    val activeIdx = when (serverState) {
        NuvioScreenState.PLAYER -> 1
        NuvioScreenState.SEARCH -> 2
        NuvioScreenState.HOME, NuvioScreenState.DETAIL, NuvioScreenState.UNKNOWN -> 0
    }
    val items = remember(lang) {
        listOf(
            l10n(lang, "Browse", "Navegar") to NuvioScreenState.HOME,
            "Player" to NuvioScreenState.PLAYER,
            l10n(lang, "Keyboard", "Teclado") to NuvioScreenState.SEARCH,
        )
    }
    val lefts = remember { mutableStateListOf(0, 0, 0) }
    val widths = remember { mutableStateListOf(0, 0, 0) }
    val animLeftPx by animateFloatAsState(
        targetValue = lefts[activeIdx].toFloat(),
        animationSpec = NuvioMotion.smoothSpring(reducedMotion),
        label = "seg_left",
    )
    val animWidthPx by animateFloatAsState(
        targetValue = widths[activeIdx].toFloat(),
        animationSpec = NuvioMotion.smoothSpring(reducedMotion),
        label = "seg_width",
    )
    val padXPx = with(density) { 2.dp.toPx() }
    val pillHeightPx = with(density) { 32.dp.toPx() }
    val pillBrush = remember(scheme.pillActiveBgTop, scheme.pillActiveBgBottom) {
        Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom))
    }

    Box(
        modifier = Modifier
            .height(48.dp)
            .wrapContentWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 6.dp)
                .clip(CircleShape)
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, CircleShape)
                .graphicsLayer(),
        )
        androidx.compose.foundation.Canvas(
            modifier = Modifier.matchParentSize(),
        ) {
            if (animWidthPx > 0f) {
                val top = (size.height - pillHeightPx) * 0.5f
                val corner = pillHeightPx * 0.5f
                drawRoundRect(
                    brush = pillBrush,
                    topLeft = androidx.compose.ui.geometry.Offset(animLeftPx + padXPx, top),
                    size = androidx.compose.ui.geometry.Size(animWidthPx, pillHeightPx),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { idx, (label, targetState) ->
                val selected = idx == activeIdx
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .onGloballyPositioned { coords ->
                            val l = coords.positionInParent().x.toInt()
                            val w = coords.size.width
                            if (lefts[idx] != l) lefts[idx] = l
                            if (widths[idx] != w) widths[idx] = w
                        }
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelectState(targetState) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NuvioText(
                        text = label,
                        style = NuvioTypography.KeyLabelCompact.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        ),
                        color = if (selected) scheme.pillActiveFg else scheme.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppsSubTabSegmentedControl(selectedTab: AppsSubTab, onSelect: (AppsSubTab) -> Unit) {
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    val tabs = AppsSubTab.entries
    Box(
        modifier = Modifier
            .height(48.dp)
            .wrapContentWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 6.dp)
                .clip(CircleShape)
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, CircleShape),
        )
        Row(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = tab == selectedTab
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(tab) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(CircleShape)
                            .then(
                                if (selected) {
                                    Modifier.background(
                                        Brush.verticalGradient(
                                            listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom),
                                        ),
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioText(
                            text = tab.label(lang),
                            style = NuvioTypography.KeyLabelCompact.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            ),
                            color = if (selected) scheme.pillActiveFg else scheme.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopActionPill(label: String, icon: ImageVector?, active: Boolean, onClick: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val fgColor = if (active) scheme.pillActiveFg else scheme.textPrimary
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(CircleShape)
                .then(
                    if (active) {
                        Modifier.background(
                            Brush.verticalGradient(
                                listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom),
                            ),
                        )
                    } else {
                        Modifier
                            .background(scheme.contentCellSurface)
                            .border(1.dp, scheme.contentBorder, CircleShape)
                    },
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            icon?.let {
                NuvioIcon(
                    imageVector = it,
                    contentDescription = label,
                    tint = fgColor,
                    modifier = Modifier.size(14.dp),
                )
            }
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = fgColor,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ConnectionErrorBanner(error: String?, onDismiss: () -> Unit) {
    val err = error?.takeIf { it.isNotBlank() } ?: return
    val scheme = LocalNuvioColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .heightIn(min = 48.dp)
            .nuvioGlass(
                shape = RoundedCornerShape(14.dp),
                variant = GlassVariant.REGULAR,
                elevated = true,
                accentTint = scheme.statusError,
            )
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        NuvioText(
            text = err,
            style = NuvioTypography.KeyLabelCompact,
            color = scheme.statusError,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NuvioGlassDock(selectedTab: NuvioTab, onSelectTab: (NuvioTab) -> Unit) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val reducedMotion = LocalReducedMotion.current
    val scheme = LocalNuvioColors.current
    val tabs = NuvioTab.entries
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .nuvioGlass(
                    shape = CircleShape,
                    variant = GlassVariant.REGULAR,
                    elevated = true,
                )
                .padding(horizontal = 5.dp),
        ) {
            val slotWidth = maxWidth / tabs.size
            val slotWidthPx = with(LocalDensity.current) { slotWidth.toPx() }
            val pillIndexAnim by animateFloatAsState(
                targetValue = selectedIndex.toFloat(),
                animationSpec = NuvioMotion.smoothSpring(reducedMotion),
                label = "dock_pill_offset",
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer { translationX = pillIndexAnim * slotWidthPx }
                    .width(slotWidth)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom),
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { item ->
                    DockTabButton(
                        tab = item,
                        selected = item == selectedTab,
                        reducedMotion = reducedMotion,
                        modifier = Modifier.weight(1f).height(48.dp),
                        onClick = {
                            if (hapticsEnabled) Haptics.tab(context)
                            onSelectTab(item)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DockTabButton(
    tab: NuvioTab,
    selected: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    val label = tab.label(lang)
    val scale by animateFloatAsState(
        targetValue = if (selected && !reducedMotion) 1.03f else 1f,
        animationSpec = NuvioMotion.smoothSpring(reducedMotion),
        label = "dock_item_scale",
    )
    val contentColor = if (selected) scheme.pillActiveFg else scheme.textSecondary
    val icon = when (tab) {
        NuvioTab.REMOTE -> Icons.Rounded.SettingsRemote
        NuvioTab.TRACKPAD -> Icons.Rounded.TouchApp
        NuvioTab.SCREEN -> Icons.Rounded.DesktopWindows
        NuvioTab.APPS -> Icons.Rounded.Apps
        NuvioTab.SETTINGS -> Icons.Rounded.Settings
    }

    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NuvioIcon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.height(2.dp))
        NuvioText(
            text = label,
            style = NuvioTypography.DockTabLabel.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatConnectionStatus(
    connState: ConnState,
    isTunnelActive: Boolean,
    pingMs: Int,
    lang: ResolvedLanguage,
): String = when (connState) {
    is ConnState.Connected -> {
        val routeLabel = if (isTunnelActive) {
            l10n(lang, "Remote", "Remoto")
        } else {
            l10n(lang, "Home (Wi-Fi)", "Casa (Wi-Fi)")
        }
        if (pingMs > 0) {
            "$routeLabel • $pingMs ms"
        } else {
            l10n(lang, "Connected ($routeLabel)", "Conectado ($routeLabel)")
        }
    }
    is ConnState.Connecting -> l10n(lang, "Connecting…", "Conectando…")
    is ConnState.Failed -> l10n(lang, "Offline", "Sem conexão")
    is ConnState.Disconnected -> l10n(lang, "Pair PC", "Parear PC")
}
