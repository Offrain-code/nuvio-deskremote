package com.nuvio.remote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.NuvioBridgeStateHolder
import com.nuvio.remote.connection.NuvioBridgeStatus
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.LiquidCircleButton
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.components.PrecisionSwipePad
import com.nuvio.remote.ui.components.RemotePadModeToggle
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.accentForScreen
import com.nuvio.remote.ui.theme.nuvioGlass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REPEAT_INITIAL_DELAY_MS = 340L
private const val REPEAT_INTERVAL_MS = 115L

internal data class RemoteCockpitMetrics(
    val horizontalPadding: Dp,
    val rockerWidth: Dp,
    val rockerHeight: Dp,
    val dpadDiameter: Dp,
    val centerOkSize: Dp,
    val satelliteSize: Dp,
    val sideGap: Dp,
)

private val COMPACT_NARROW_MAX_WIDTH = 345.dp
private val COMPACT_NARROW_MAX_HEIGHT = 430.dp
private val COMPACT_STD_MAX_WIDTH = 375.dp
private val COMPACT_STD_MAX_HEIGHT = 490.dp
private const val LARGE_FONT_SCALE = 1.20f

internal fun computeCockpitMetrics(
    maxWidth: Dp,
    maxHeight: Dp,
    isPlayer: Boolean,
    isSheetExpanded: Boolean = false,
    fontScale: Float = 1f,
): RemoteCockpitMetrics {
    val isNarrow = maxWidth < COMPACT_NARROW_MAX_WIDTH || maxHeight < COMPACT_NARROW_MAX_HEIGHT
    if (isNarrow) {
        val hPad = 10.dp
        val rockerW = 48.dp
        val maxFitDpad = (maxWidth - hPad * 2 - rockerW * 2 - 16.dp).coerceAtLeast(144.dp)
        return RemoteCockpitMetrics(
            horizontalPadding = hPad,
            rockerWidth = rockerW,
            rockerHeight = 144.dp,
            dpadDiameter = minOf(156.dp, maxFitDpad),
            centerOkSize = 48.dp,
            satelliteSize = 36.dp,
            sideGap = 4.dp,
        )
    }

    val isStandard = maxWidth <= COMPACT_STD_MAX_WIDTH ||
        maxHeight <= COMPACT_STD_MAX_HEIGHT ||
        isPlayer ||
        isSheetExpanded ||
        fontScale >= LARGE_FONT_SCALE
    if (isStandard) {
        val hPad = 14.dp
        val rockerW = if (maxWidth < 360.dp) 48.dp else 50.dp
        val targetDpad = if (isPlayer) 160.dp else 166.dp
        val maxFitDpad = (maxWidth - hPad * 2 - rockerW * 2 - 16.dp).coerceAtLeast(144.dp)
        return RemoteCockpitMetrics(
            horizontalPadding = hPad,
            rockerWidth = rockerW,
            rockerHeight = if (isPlayer) 148.dp else 156.dp,
            dpadDiameter = minOf(targetDpad, maxFitDpad),
            centerOkSize = if (isPlayer) 48.dp else 52.dp,
            satelliteSize = 38.dp,
            sideGap = if (isPlayer) 6.dp else 8.dp,
        )
    }

    val hPad = 16.dp
    val rockerW = 52.dp
    val maxFitDpad = (maxWidth - hPad * 2 - rockerW * 2 - 16.dp).coerceAtLeast(166.dp)
    return RemoteCockpitMetrics(
        horizontalPadding = hPad,
        rockerWidth = rockerW,
        rockerHeight = 170.dp,
        dpadDiameter = minOf(186.dp, maxFitDpad),
        centerOkSize = 64.dp,
        satelliteSize = 42.dp,
        sideGap = 12.dp,
    )
}

private fun requestSheetData(bridge: NuvioBridgeStateHolder, target: PlayerSheetType) {
    when (target) {
        PlayerSheetType.SUBTITLES -> bridge.requestSubtitles()
        PlayerSheetType.AUDIO -> bridge.requestAudio()
        PlayerSheetType.SOURCES -> bridge.requestSources(force = false)
        PlayerSheetType.QUEUE -> bridge.requestQueue()
        PlayerSheetType.LYRICS -> bridge.phase3.requestLyrics()
        else -> Unit
    }
}

/**
 * Main Nuvio Mode Liquid Glass cockpit:
 * side volume and channel rockers, circular D-Pad with OK, 4 satellites, and dynamic context dock.
 */
@Composable
fun RemoteScreen(
    serverState: NuvioScreenState,
    bridge: NuvioBridgeStateHolder,
    textInputFocusTick: Long,
    onNav: (String) -> Unit,
    onKey: (String) -> Unit,
    onType: (String) -> Unit,
    onSelectState: (NuvioScreenState) -> Unit,
    onOpenAppsTab: () -> Unit,
    onOpenCinemaMode: () -> Unit = {},
    swipePadMode: Boolean = false,
    onSwipePadModeChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val accent = accentForScreen(serverState)
    val bridgeStatus by bridge.status.collectAsState()
    val nowPlaying by bridge.nowPlaying.collectAsState()
    val sources by bridge.sources.collectAsState()
    val subtitles by bridge.subtitles.collectAsState()
    val audioTracks by bridge.audioTracks.collectAsState()
    val musicQueue by bridge.musicQueue.collectAsState()
    val lyrics by bridge.phase3.lyrics.collectAsState()
    var isSheetExpanded by rememberSaveable { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf(PlayerSheetType.NONE) }

    BackHandler(enabled = activeSheet != PlayerSheetType.NONE) {
        activeSheet = PlayerSheetType.NONE
    }

    LaunchedEffect(serverState) {
        val shouldExpand = serverState == NuvioScreenState.SEARCH || serverState == NuvioScreenState.PLAYER
        isSheetExpanded = shouldExpand
        if (!shouldExpand && activeSheet != PlayerSheetType.SOURCES) activeSheet = PlayerSheetType.NONE
    }

    LaunchedEffect(sources?.title, sources?.sources?.size, sources != null) {
        if (sources != null && (sources!!.sources.isNotEmpty() || sources!!.loading)) {
            activeSheet = PlayerSheetType.SOURCES
        } else if (sources == null && activeSheet == PlayerSheetType.SOURCES) {
            activeSheet = PlayerSheetType.NONE
        }
    }

    val fontScale = LocalDensity.current.fontScale

    Box(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val metrics = computeCockpitMetrics(
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                isPlayer = serverState == NuvioScreenState.PLAYER,
                isSheetExpanded = isSheetExpanded,
                fontScale = fontScale,
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = metrics.horizontalPadding),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(metrics.sideGap),
                        ) {
                            LiquidCircleButton(
                                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                                label = l10n("Back", "Voltar"),
                                showLabel = false,
                                size = metrics.satelliteSize,
                                iconSize = 19.dp,
                                onClick = { onNav("back") },
                            )
                            VolumeGlassRocker(
                                rockerWidth = metrics.rockerWidth,
                                rockerHeight = metrics.rockerHeight,
                                onVolUp = { onKey("Up") },
                                onMute = { onKey("M") },
                                onVolDown = { onKey("Down") },
                            )
                            LiquidCircleButton(
                                icon = Icons.Rounded.Info,
                                label = l10n("Details", "Detalhes"),
                                showLabel = false,
                                size = metrics.satelliteSize,
                                iconSize = 19.dp,
                                onClick = { onKey("I") },
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(metrics.sideGap),
                        ) {
                            RemotePadModeToggle(
                                swipeMode = swipePadMode,
                                onSelectMode = onSwipePadModeChange,
                            )
                            if (swipePadMode) {
                                PrecisionSwipePad(
                                    diameter = metrics.dpadDiameter,
                                    accentColor = accent.primary,
                                    onDirection = onNav,
                                    onOk = { onNav("ok") },
                                    onBack = { onNav("back") },
                                )
                            } else {
                                PrecisionDPadRing(
                                    diameter = metrics.dpadDiameter,
                                    centerSize = metrics.centerOkSize,
                                    accentColor = accent.primary,
                                    accentSurface = accent.surfaceTint,
                                    onDirection = onNav,
                                    onOk = { onNav("ok") },
                                )
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(metrics.sideGap),
                        ) {
                            HomeSatelliteButton(
                                size = metrics.satelliteSize,
                                onHome = {
                                    if (hapticsEnabled) Haptics.action(context)
                                    onKey("H")
                                },
                                onLongPressHome = {
                                    if (hapticsEnabled) Haptics.toggle(context)
                                    onOpenAppsTab()
                                },
                            )
                            ChannelGlassRocker(
                                rockerWidth = metrics.rockerWidth,
                                rockerHeight = metrics.rockerHeight,
                                onChannelUp = { onKey("PageUp") },
                                onLiveTv = { onNav("live") },
                                onChannelDown = { onKey("PageDown") },
                            )
                            LiquidCircleButton(
                                icon = Icons.Rounded.Fullscreen,
                                label = l10n("Fullscreen", "Tela Cheia"),
                                showLabel = false,
                                size = metrics.satelliteSize,
                                iconSize = 20.dp,
                                onClick = { onKey("F11") },
                            )
                        }
                    }
                }

                DynamicContextDock(
                    activeContext = serverState,
                    expanded = isSheetExpanded,
                    onExpandedChange = { isSheetExpanded = it },
                    onNav = onNav,
                    onKey = { keyName ->
                        if (hapticsEnabled && keyName == "Space") {
                            Haptics.playPause(context)
                        }
                        onKey(keyName)
                    },
                    onSendType = onType,
                    onCloseSearchKeyboard = { onSelectState(NuvioScreenState.HOME) },
                    textInputFocusTick = textInputFocusTick,
                    nowPlaying = nowPlaying,
                    bridgeConnected = bridgeStatus == NuvioBridgeStatus.CONNECTED,
                    onSeekTo = { bridge.seekTo(it) },
                    onSetVolume = { bridge.setVolume(it) },
                    onControl = { action, value -> bridge.sendControl(action, value) },
                    onOpenSheet = { target ->
                        if (target == PlayerSheetType.SOURCES) {
                            bridge.requestSources(force = true)
                            activeSheet = PlayerSheetType.SOURCES
                        } else {
                            requestSheetData(bridge, target)
                            activeSheet = target
                        }
                    },
                    onOpenCinemaMode = onOpenCinemaMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                )
            }
        }

        PlayerOverlaySheetHost(
            sheet = activeSheet,
            nowPlaying = nowPlaying,
            subtitles = subtitles,
            audioTracks = audioTracks,
            sources = sources,
            musicQueue = musicQueue,
            lyrics = lyrics,
            onDismiss = { activeSheet = PlayerSheetType.NONE },
            onPickSubtitle = { bridge.pickSubtitle(it) },
            onPickAudio = { bridge.pickAudio(it) },
            onRequestSources = { bridge.requestSources(force = true) },
            onPickSource = { bridge.pickSource(it) },
            onQueueAction = { action, videoId -> bridge.queueAction(action, videoId) },
            onRefreshLyrics = { bridge.phase3.requestLyrics() },
            onSeekTo = { bridge.seekTo(it) },
            onControl = { action, value -> bridge.sendControl(action, value) },
            onKey = onKey,
        )
    }
}

@Composable
private fun VolumeGlassRocker(
    rockerWidth: Dp,
    rockerHeight: Dp,
    onVolUp: () -> Unit,
    onMute: () -> Unit,
    onVolDown: () -> Unit,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current

    Column(
        modifier = Modifier
            .width(rockerWidth)
            .height(rockerHeight)
            .nuvioGlass(shape = RoundedCornerShape(rockerWidth / 2), variant = GlassVariant.REGULAR),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassRockerStepButton(
            icon = Icons.Rounded.Add,
            description = l10n("Volume up", "Aumentar volume"),
            repeatable = true,
            onStep = {
                if (hapticsEnabled) Haptics.volume(context)
                onVolUp()
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Box(
            Modifier
                .fillMaxWidth(0.62f)
                .height(1.dp)
                .background(scheme.contentBorder),
        )
        GlassRockerStepButton(
            icon = Icons.AutoMirrored.Rounded.VolumeOff,
            description = l10n("Mute audio", "Silenciar áudio"),
            repeatable = false,
            iconSize = 19.dp,
            onStep = {
                if (hapticsEnabled) Haptics.toggle(context)
                onMute()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        )
        Box(
            Modifier
                .fillMaxWidth(0.62f)
                .height(1.dp)
                .background(scheme.contentBorder),
        )
        GlassRockerStepButton(
            icon = Icons.Rounded.Remove,
            description = l10n("Volume down", "Diminuir volume"),
            repeatable = true,
            onStep = {
                if (hapticsEnabled) Haptics.volume(context)
                onVolDown()
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun ChannelGlassRocker(
    rockerWidth: Dp,
    rockerHeight: Dp,
    onChannelUp: () -> Unit,
    onLiveTv: () -> Unit,
    onChannelDown: () -> Unit,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current

    Column(
        modifier = Modifier
            .width(rockerWidth)
            .height(rockerHeight)
            .nuvioGlass(shape = RoundedCornerShape(rockerWidth / 2), variant = GlassVariant.REGULAR),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassRockerStepButton(
            icon = Icons.Rounded.KeyboardArrowUp,
            description = l10n("Next channel", "Próximo canal"),
            repeatable = true,
            onStep = {
                if (hapticsEnabled) Haptics.volume(context)
                onChannelUp()
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Box(
            Modifier
                .fillMaxWidth(0.62f)
                .height(1.dp)
                .background(scheme.contentBorder),
        )
        GlassRockerTextButton(
            label = "CH",
            description = l10n("Open Live TV", "Abrir Live TV"),
            onClick = {
                if (hapticsEnabled) Haptics.action(context)
                onLiveTv()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        )
        Box(
            Modifier
                .fillMaxWidth(0.62f)
                .height(1.dp)
                .background(scheme.contentBorder),
        )
        GlassRockerStepButton(
            icon = Icons.Rounded.KeyboardArrowDown,
            description = l10n("Previous channel", "Canal anterior"),
            repeatable = true,
            onStep = {
                if (hapticsEnabled) Haptics.volume(context)
                onChannelDown()
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun GlassRockerStepButton(
    icon: ImageVector,
    description: String,
    repeatable: Boolean,
    onStep: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
) {
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) 0.90f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "rockerStepScale",
    )
    val iconColor by animateColorAsState(
        targetValue = if (isPressed) scheme.textPrimary else scheme.textSecondary,
        animationSpec = NuvioMotion.smoothSpring(reducedMotion),
        label = "rockerStepTint",
    )

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .pointerInput(repeatable, onStep) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onStep()
                        val job = if (repeatable) {
                            scope.launch {
                                delay(REPEAT_INITIAL_DELAY_MS)
                                while (true) {
                                    onStep()
                                    delay(REPEAT_INTERVAL_MS)
                                }
                            }
                        } else {
                            null
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            job?.cancel()
                            isPressed = false
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        NuvioIcon(
            imageVector = icon,
            contentDescription = description,
            tint = iconColor,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

@Composable
private fun GlassRockerTextButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) 0.90f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "rockerLabelScale",
    )

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .pointerInput(onClick) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onClick()
                        try {
                            tryAwaitRelease()
                        } finally {
                            isPressed = false
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        NuvioText(
            text = label,
            style = NuvioTypography.SectionMicroTitle,
            color = if (isPressed) scheme.textPrimary else scheme.textMuted,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

@Composable
private fun PrecisionDPadRing(
    diameter: Dp,
    centerSize: Dp,
    accentColor: Color,
    accentSurface: Color,
    onDirection: (String) -> Unit,
    onOk: () -> Unit,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current

    Box(
        modifier = Modifier
            .size(diameter)
            .nuvioGlass(
                shape = CircleShape,
                variant = GlassVariant.REGULAR,
                accentTint = accentSurface,
            )
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(accentColor.copy(alpha = 0.08f), Color.Transparent),
                    ),
                ),
        )

        val majorSpan = if (diameter <= 166.dp) 48.dp else 54.dp

        DPadDirectionButton(
            icon = Icons.Rounded.KeyboardArrowUp,
            description = l10n("Move up", "Mover para cima"),
            alignment = Alignment.TopCenter,
            width = majorSpan,
            height = 48.dp,
            onStep = {
                if (hapticsEnabled) Haptics.dpad(context)
                onDirection("up")
            },
        )
        DPadDirectionButton(
            icon = Icons.Rounded.KeyboardArrowDown,
            description = l10n("Move down", "Mover para baixo"),
            alignment = Alignment.BottomCenter,
            width = majorSpan,
            height = 48.dp,
            onStep = {
                if (hapticsEnabled) Haptics.dpad(context)
                onDirection("down")
            },
        )
        DPadDirectionButton(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            description = l10n("Move left", "Mover para a esquerda"),
            alignment = Alignment.CenterStart,
            width = 48.dp,
            height = majorSpan,
            onStep = {
                if (hapticsEnabled) Haptics.dpad(context)
                onDirection("left")
            },
        )
        DPadDirectionButton(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            description = l10n("Move right", "Mover para a direita"),
            alignment = Alignment.CenterEnd,
            width = 48.dp,
            height = majorSpan,
            onStep = {
                if (hapticsEnabled) Haptics.dpad(context)
                onDirection("right")
            },
        )

        CenterOkButton(
            size = centerSize,
            accentColor = accentColor,
            textColor = scheme.textPrimary,
            reducedMotion = reducedMotion,
            onOk = {
                if (hapticsEnabled) Haptics.ok(context)
                onOk()
            },
        )
    }
}

@Composable
private fun BoxScope.DPadDirectionButton(
    icon: ImageVector,
    description: String,
    alignment: Alignment,
    width: Dp,
    height: Dp,
    onStep: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) 0.88f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "dpadDirScale",
    )

    Box(
        modifier = Modifier
            .align(alignment)
            .size(width = width.coerceAtLeast(48.dp), height = height.coerceAtLeast(48.dp))
            .clip(RoundedCornerShape(22.dp))
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .pointerInput(onStep) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onStep()
                        val job = scope.launch {
                            delay(REPEAT_INITIAL_DELAY_MS)
                            while (true) {
                                onStep()
                                delay(REPEAT_INTERVAL_MS)
                            }
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            job.cancel()
                            isPressed = false
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        NuvioIcon(
            imageVector = icon,
            contentDescription = description,
            tint = if (isPressed) scheme.textPrimary else scheme.textSecondary,
            modifier = Modifier
                .padding(4.dp)
                .size(28.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

@Composable
private fun CenterOkButton(size: Dp, accentColor: Color, textColor: Color, reducedMotion: Boolean, onOk: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) 0.92f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "centerOkScale",
    )

    val okDesc = l10n("Select OK", "Selecionar OK")
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(size.coerceAtLeast(48.dp))
            .nuvioGlass(
                shape = CircleShape,
                variant = GlassVariant.REGULAR,
                elevated = true,
                pressed = isPressed,
                accentTint = accentColor,
            )
            .border(1.dp, accentColor.copy(alpha = 0.28f), CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = okDesc
            }
            .pointerInput(onOk) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        val released = tryAwaitRelease()
                        isPressed = false
                        if (released) onOk()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        NuvioText(
            text = "OK",
            style = NuvioTypography.DisplayKey,
            color = textColor,
        )
    }
}

@Composable
private fun HomeSatelliteButton(size: Dp, onHome: () -> Unit, onLongPressHome: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) 0.92f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "homeSatScale",
    )

    val homeDesc = l10n("Nuvio Home", "Início do Nuvio")
    Box(
        modifier = Modifier
            .size(size.coerceAtLeast(48.dp))
            .semantics {
                role = Role.Button
                contentDescription = homeDesc
            }
            .pointerInput(onHome, onLongPressHome) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            isPressed = false
                        }
                    },
                    onTap = { onHome() },
                    onLongPress = { onLongPressHome() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .size(size)
                .nuvioGlass(shape = CircleShape, variant = GlassVariant.REGULAR, pressed = isPressed),
            contentAlignment = Alignment.Center,
        ) {
            NuvioIcon(
                imageVector = Icons.Rounded.Home,
                contentDescription = null,
                tint = scheme.textPrimary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}
