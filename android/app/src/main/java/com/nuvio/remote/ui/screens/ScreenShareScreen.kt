package com.nuvio.remote.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nuvio.remote.connection.MouseButtons
import com.nuvio.remote.connection.NuvioConnection
import com.nuvio.remote.connection.ScreenProfile
import com.nuvio.remote.data.AppPrefs
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.components.RemoteTextInputBar
import com.nuvio.remote.ui.components.TapSnapState
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.i18n.l10nCurrent
import com.nuvio.remote.ui.theme.ClearContentShadow
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.isBottomFrameRegionLight
import com.nuvio.remote.ui.theme.nuvioGlass
import com.nuvio.remote.ui.theme.resolveClearBackdropLight
import com.nuvio.remote.webrtc.ScreenStreamController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.webrtc.EglRenderer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import kotlin.math.abs

private const val MAX_VIDEO_ZOOM = 3.0f
private const val RTC_LUMA_SAMPLE_INTERVAL_MS = 1500L
private const val RTC_LUMA_SAMPLE_SCALE = 0.08f

/**
 * Screen tab exclusive to PC Mode:
 * clean portrait view (16:9 video + 2 Clear overlay pills + opaque status card)
 * and immersive fullscreen/landscape view with 6-pill Clear toolbar, 3x4 More PC menu, and radial orb.
 */
@Composable
fun ScreenShareScreen(
    conn: NuvioConnection,
    controller: ScreenStreamController,
    defaultProfile: ScreenProfile,
    onDefaultProfileChange: (ScreenProfile) -> Unit,
    activeMonitor: Int,
    onMonitorChange: (Int) -> Unit,
    onImmersiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val appPrefs = remember(context) { AppPrefs(context) }
    val hapticsEnabled = LocalHapticsEnabled.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val connState by conn.connState.collectAsState()
    val isTunnelActive by conn.isTunnelActive.collectAsState()
    val hasScreenFrame by conn.hasScreenFrame.collectAsState()
    val isFrameBottomLight by conn.isFrameBottomLight.collectAsState()
    val answerSdp by conn.answerSdp.collectAsState()
    val videoAspect by conn.videoAspect.collectAsState()
    val needFallbackCursor by conn.needFallbackCursor.collectAsState()
    val screenAudioEnabled by conn.screenAudioEnabled.collectAsState()
    val remoteClipboard by conn.remoteClipboard.collectAsState()
    val clipboardTick by conn.clipboardUpdatedTick.collectAsState()
    val textInputFocusTick by conn.textInputFocusTick.collectAsState()
    val streamStatus by controller.status.collectAsState()
    val hasRtcFirstFrame by controller.hasFirstFrame.collectAsState()
    val hideFallbackFrame = hasRtcFirstFrame && streamStatus == "live"

    var manualFullscreen by rememberSaveable { mutableStateOf(false) }
    val uiState = remember {
        ScreenShareUiState(initialDirectTouch = appPrefs.screenDirectTouch)
    }
    val isImmersive = isLandscape || manualFullscreen
    val surfaceRef = remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val rtcSampledLight = rememberRtcBackdropLight(
        surface = surfaceRef.value,
        streamStatus = streamStatus,
        hasFallbackFrame = hasScreenFrame,
    )
    val isBackdropLight = remember(hasScreenFrame, isFrameBottomLight, streamStatus, rtcSampledLight) {
        resolveClearBackdropLight(
            hasFallbackBitmap = hasScreenFrame,
            fallbackBitmapLight = isFrameBottomLight,
            streamStatus = streamStatus,
            rtcSampledLight = rtcSampledLight,
        )
    }

    ScreenShareStreamSyncEffects(
        context = context,
        controller = controller,
        surface = surfaceRef.value,
        answerSdp = answerSdp,
        defaultProfile = defaultProfile,
        activeMonitor = activeMonitor,
        reconnectTick = uiState.reconnectTick,
        clipboardTick = clipboardTick,
        remoteClipboard = remoteClipboard,
        uiState = uiState,
    )

    ScreenShareLifecycleEffects(
        activity = activity,
        view = view,
        controller = controller,
        conn = conn,
        dragHoldActive = uiState.dragHoldActive,
        isImmersive = isImmersive,
        onImmersiveChange = onImmersiveChange,
    )

    ScreenShareBackHandler(
        hasOverlayOrFullscreen = uiState.hasOverlayOpen || isImmersive,
        showMorePopover = uiState.showMorePopover,
        showQualityPopover = uiState.showQualityPopover,
        keyboardVisible = uiState.keyboardVisible,
        onCloseMore = { uiState.showMorePopover = false },
        onCloseQuality = { uiState.showQualityPopover = false },
        onCloseKeyboard = { uiState.keyboardVisible = false },
        onExitFullscreen = {
            uiState.exitFullscreen(activity) { manualFullscreen = false }
        },
    )

    val moreCallbacks = buildMorePcCallbacks(
        conn = conn,
        appPrefs = appPrefs,
        curtainActive = uiState.curtainActive,
        dragHoldActive = uiState.dragHoldActive,
        screenAudioEnabled = screenAudioEnabled,
        onCurtainChange = { uiState.curtainActive = it },
        onDragHoldChange = { uiState.dragHoldActive = it },
        onFeedback = { uiState.feedbackMessage = it },
        onPasteToPc = { uiState.pasteClipboardToPc(context, conn) },
    )

    val radialCallbacks = buildRadialDockCallbacks(
        conn = conn,
        onToggleKeyboard = { uiState.keyboardVisible = !uiState.keyboardVisible },
        onPasteToPc = { uiState.pasteClipboardToPc(context, conn) },
        onToggleAudio = moreCallbacks.onToggleAudio,
    )

    if (isImmersive) {
        FullscreenScreenShareContent(
            conn = conn,
            videoAspect = videoAspect,
            showFallbackCursor = needFallbackCursor,
            directTouch = uiState.directTouch,
            activeMonitor = activeMonitor,
            defaultProfile = defaultProfile,
            isLandscape = isLandscape,
            isBackdropLight = isBackdropLight,
            barHidden = uiState.barHidden,
            isBarAwake = uiState.isBarAwake,
            showQualityPopover = uiState.showQualityPopover,
            showMorePopover = uiState.showMorePopover,
            keyboardVisible = uiState.keyboardVisible,
            textInputFocusTick = textInputFocusTick,
            moreMenuState = MorePcMenuState(uiState.curtainActive, uiState.dragHoldActive, screenAudioEnabled),
            moreCallbacks = moreCallbacks,
            radialCallbacks = radialCallbacks,
            feedbackMessage = uiState.feedbackMessage,
            hideFallbackFrame = hideFallbackFrame,
            onSurfaceReady = { surfaceRef.value = it },
            onSurfaceRelease = { surf ->
                if (surfaceRef.value === surf) surfaceRef.value = null
                controller.detachRenderer(surf)
            },
            onExitFullscreen = { uiState.exitFullscreen(activity) { manualFullscreen = false } },
            onWakeBar = { uiState.isBarAwake = true },
            onHideBar = uiState::hideBar,
            onRestoreBar = uiState::restoreBar,
            onToggleQuality = uiState::toggleQuality,
            onToggleTouchMode = {
                uiState.toggleTouchMode(context, appPrefs, hapticsEnabled)
            },
            onToggleKeyboard = uiState::toggleKeyboard,
            onKeyboardVisibilityChange = { uiState.keyboardVisible = it },
            onToggleOrientation = {
                uiState.toggleOrientation(activity, isLandscape) { manualFullscreen = false }
            },
            onToggleMorePc = uiState::toggleMore,
            onSelectMonitor = onMonitorChange,
            onSelectProfile = onDefaultProfileChange,
            onDismissPopovers = uiState::dismissPopovers,
            modifier = modifier,
        )
    } else {
        PortraitScreenShareContent(
            conn = conn,
            connState = connState,
            isTunnelActive = isTunnelActive,
            videoAspect = videoAspect,
            showFallbackCursor = needFallbackCursor,
            directTouch = uiState.directTouch,
            activeMonitor = activeMonitor,
            screenAudioEnabled = screenAudioEnabled,
            curtainActive = uiState.curtainActive,
            isBackdropLight = isBackdropLight,
            moreCallbacks = moreCallbacks,
            onSurfaceReady = { surfaceRef.value = it },
            onSurfaceRelease = { surf ->
                if (surfaceRef.value === surf) surfaceRef.value = null
                controller.detachRenderer(surf)
            },
            hideFallbackFrame = hideFallbackFrame,
            onEnterFullscreen = { manualFullscreen = true },
            onRotateLandscape = {
                uiState.toggleOrientation(activity, isLandscape) { manualFullscreen = false }
            },
            onReconnect = { uiState.reconnectTick++ },
            modifier = modifier,
        )
    }
}

@Composable
private fun FullscreenScreenShareContent(
    conn: NuvioConnection,
    videoAspect: Float,
    showFallbackCursor: Boolean,
    directTouch: Boolean,
    activeMonitor: Int,
    defaultProfile: ScreenProfile,
    isLandscape: Boolean,
    isBackdropLight: Boolean,
    barHidden: Boolean,
    isBarAwake: Boolean,
    showQualityPopover: Boolean,
    showMorePopover: Boolean,
    keyboardVisible: Boolean,
    textInputFocusTick: Long,
    moreMenuState: MorePcMenuState,
    moreCallbacks: MorePcMenuCallbacks,
    radialCallbacks: RadialDockCallbacks,
    feedbackMessage: String?,
    hideFallbackFrame: Boolean,
    onSurfaceReady: (SurfaceViewRenderer) -> Unit,
    onSurfaceRelease: (SurfaceViewRenderer) -> Unit,
    onExitFullscreen: () -> Unit,
    onWakeBar: () -> Unit,
    onHideBar: () -> Unit,
    onRestoreBar: () -> Unit,
    onToggleQuality: () -> Unit,
    onToggleTouchMode: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onKeyboardVisibilityChange: (Boolean) -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleMorePc: () -> Unit,
    onSelectMonitor: (Int) -> Unit,
    onSelectProfile: (ScreenProfile) -> Unit,
    onDismissPopovers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VideoStreamViewport(
            conn = conn,
            showFallbackCursor = showFallbackCursor,
            directTouch = directTouch,
            activeMonitor = activeMonitor,
            videoAspect = videoAspect.coerceIn(1.2f, 2.5f),
            matchHeightFirst = isLandscape,
            isBackdropLight = isBackdropLight,
            hideFallbackFrame = hideFallbackFrame,
            onSurfaceReady = onSurfaceReady,
            onSurfaceRelease = onSurfaceRelease,
            onInteraction = onWakeBar,
        )

        DiscreetExitFullscreenButton(
            isBackdropLight = isBackdropLight,
            onExitFullscreen = onExitFullscreen,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .displayCutoutPadding()
                .padding(top = 6.dp, end = 10.dp),
        )

        ScreenShareRadialDock(
            isBackdropLight = isBackdropLight,
            screenAudioEnabled = moreMenuState.screenAudioEnabled,
            callbacks = radialCallbacks,
            modifier = Modifier
                .fillMaxSize()
                .displayCutoutPadding(),
        )

        AnimatedVisibility(
            visible = feedbackMessage != null,
            enter = NuvioMotion.morphEnter(reducedMotion),
            exit = NuvioMotion.morphExit(reducedMotion),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .nuvioGlass(
                        shape = RoundedCornerShape(999.dp),
                        variant = GlassVariant.CLEAR,
                        isBackdropLight = isBackdropLight,
                        captureStageBackdrop = false,
                        resting = false,
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                NuvioText(
                    text = feedbackMessage.orEmpty(),
                    style = NuvioTypography.KeyLabelCompact,
                    color = scheme.clearOverlayText,
                    shadow = ClearContentShadow,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .displayCutoutPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QualityAndMonitorPopover(
                visible = showQualityPopover,
                isBackdropLight = isBackdropLight,
                activeMonitor = activeMonitor,
                activeProfile = defaultProfile,
                onSelectMonitor = onSelectMonitor,
                onSelectProfile = onSelectProfile,
                onDismiss = onDismissPopovers,
            )

            MorePcPopoverMenu(
                visible = showMorePopover,
                isBackdropLight = isBackdropLight,
                state = moreMenuState,
                callbacks = moreCallbacks,
                onDismiss = onDismissPopovers,
            )

            RemoteTextInputBar(
                visible = keyboardVisible,
                onVisibilityChange = onKeyboardVisibilityChange,
                onSendText = { conn.sendType(it) },
                onSendKey = { key, repeat -> conn.sendKey(key, repeat) },
                focusTick = textInputFocusTick,
                placeholder = l10n("Type on PC screen…", "Digitar na tela do PC…"),
                showSpecialKeys = true,
                glassVariant = GlassVariant.CLEAR,
                captureStageBackdrop = false,
                isBackdropLight = isBackdropLight,
                modifier = Modifier.widthIn(max = 520.dp),
            )

            if (barHidden) {
                RestoreFullscreenBarButton(
                    isBackdropLight = isBackdropLight,
                    onRestoreBar = onRestoreBar,
                )
            } else {
                FullscreenBottomToolBar(
                    isBackdropLight = isBackdropLight,
                    isBarAwake = isBarAwake,
                    activeMonitor = activeMonitor,
                    activeProfile = defaultProfile,
                    showQualityPopover = showQualityPopover,
                    directTouch = directTouch,
                    keyboardVisible = keyboardVisible,
                    isLandscape = isLandscape,
                    showMorePopover = showMorePopover,
                    onHideBar = onHideBar,
                    onToggleQuality = onToggleQuality,
                    onToggleTouchMode = onToggleTouchMode,
                    onToggleKeyboard = onToggleKeyboard,
                    onToggleOrientation = onToggleOrientation,
                    onToggleMorePc = onToggleMorePc,
                )
            }
        }
    }
}

@Composable
internal fun VideoStreamViewport(
    conn: NuvioConnection,
    showFallbackCursor: Boolean,
    directTouch: Boolean,
    activeMonitor: Int,
    onSurfaceReady: (SurfaceViewRenderer) -> Unit,
    videoAspect: Float? = null,
    matchHeightFirst: Boolean = false,
    isBackdropLight: Boolean = false,
    hideFallbackFrame: Boolean = false,
    onSurfaceRelease: (SurfaceViewRenderer) -> Unit = {},
    onInteraction: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val hapticsEnabled = LocalHapticsEnabled.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize(1, 1)) }
    var videoOrigin by remember { mutableStateOf(Offset.Zero) }
    var videoSize by remember { mutableStateOf(IntSize(1, 1)) }
    val currentDirectTouch by rememberUpdatedState(directTouch)
    val currentMonitor by rememberUpdatedState(activeMonitor)
    val currentOnInteraction by rememberUpdatedState(onInteraction)
    val currentHaptics by rememberUpdatedState(hapticsEnabled)
    val decisionPx = with(density) { 20.dp.toPx() }
    val rightStripPx = with(density) { 30.dp.toPx() }
    val maxSnapPx = with(density) { 42.dp.toPx() }
    val dragEngagePx = with(density) { 8.dp.toPx() }
    val tapSlopPx = with(density) { 12.dp.toPx() }
    val gestureHelper = remember(conn, decisionPx, maxSnapPx, dragEngagePx, tapSlopPx) {
        ViewportGestureHelper(
            conn = conn,
            decisionDistancePx = decisionPx,
            maxDoubleTapDistancePx = maxSnapPx,
            dragEngageMovePx = dragEngagePx,
            maxTapMovePx = tapSlopPx,
        )
    }
    val tapSnap = remember { TapSnapState() }
    var gestureDragActive by remember { mutableStateOf(false) }

    DisposableEffect(gestureHelper) {
        onDispose {
            if (gestureHelper.cancelGestureDrag()) {
                gestureDragActive = false
            }
        }
    }

    val viewportAccess = remember {
        ViewportTransformAccess(
            getContainerWidth = { containerSize.width },
            getVideoOrigin = { videoOrigin },
            getVideoSize = { videoSize },
            getDirectTouch = { currentDirectTouch },
            getMonitor = { currentMonitor },
            getZoom = { zoom },
            getPan = { panOffset },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .clip(RoundedCornerShape(0.dp))
            .videoViewportDragGestures(
                gestureHelper = gestureHelper,
                rightStripPx = rightStripPx,
                access = viewportAccess,
                onInteraction = { currentOnInteraction() },
                onDragHoldVisualChange = { active ->
                    gestureDragActive = active
                    if (currentHaptics) {
                        if (active) Haptics.toggle(context) else Haptics.action(context)
                    }
                },
                onTransform = { nextZoom, nextPan ->
                    zoom = nextZoom
                    panOffset = nextPan
                },
            )
            .pointerInput(gestureHelper, tapSnap, maxSnapPx) {
                detectTapGestures(
                    onTap = { pos ->
                        gestureHelper.handleViewportTap(
                            pos = pos,
                            tapSnap = tapSnap,
                            maxSnapPx = maxSnapPx,
                            access = viewportAccess,
                            onAccepted = {
                                currentOnInteraction()
                                if (currentHaptics) Haptics.action(context)
                            },
                        )
                    },
                    onLongPress = { pos ->
                        gestureHelper.handleViewportLongPress(
                            pos = pos,
                            access = viewportAccess,
                            onAccepted = {
                                currentOnInteraction()
                                if (currentHaptics) Haptics.toggle(context)
                            },
                        )
                    },
                )
            },
    ) {
        val graphicsModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = panOffset.x
                translationY = panOffset.y
            }

        val innerVideoModifier = Modifier
            .fillMaxSize()
            .then(
                if (videoAspect != null) {
                    Modifier.aspectRatio(videoAspect, matchHeightConstraintsFirst = matchHeightFirst)
                } else {
                    Modifier
                },
            )
            .align(Alignment.Center)
            .onGloballyPositioned { coords ->
                videoOrigin = coords.positionInParent()
                videoSize = coords.size
            }

        Box(modifier = innerVideoModifier) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        onSurfaceReady(this)
                    }
                },
                onRelease = { surf -> onSurfaceRelease(surf) },
                modifier = graphicsModifier,
            )

            if (!hideFallbackFrame) {
                ScreenShareFrameOverlay(
                    conn = conn,
                    modifier = graphicsModifier,
                )
            }

            if (showFallbackCursor) {
                ScreenShareFallbackCursorOverlay(
                    conn = conn,
                    dragHighlight = gestureDragActive,
                    modifier = graphicsModifier,
                )
            }
        }

        // Subtle indicator for the single-finger right-edge scroll strip
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 3.dp)
                .width(3.dp)
                .height(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.26f)),
        )

        if (zoom > 1.01f) {
            ZoomResetPill(
                isBackdropLight = isBackdropLight,
                onReset = {
                    if (currentHaptics) Haptics.action(context)
                    zoom = 1f
                    panOffset = Offset.Zero
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .displayCutoutPadding()
                    .padding(top = 6.dp, start = 10.dp),
            )
        }
    }
}

@Composable
private fun ZoomResetPill(isBackdropLight: Boolean, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onReset),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .nuvioGlass(
                    shape = CircleShape,
                    variant = GlassVariant.CLEAR,
                    isBackdropLight = isBackdropLight,
                    captureStageBackdrop = false,
                    resting = false,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            NuvioText(
                text = "1x",
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.clearOverlayText,
                shadow = ClearContentShadow,
            )
        }
    }
}

private class ViewportTransformAccess(
    val getContainerWidth: () -> Int,
    val getVideoOrigin: () -> Offset,
    val getVideoSize: () -> IntSize,
    val getDirectTouch: () -> Boolean,
    val getMonitor: () -> Int,
    val getZoom: () -> Float,
    val getPan: () -> Offset,
)

private fun Modifier.videoViewportDragGestures(
    gestureHelper: ViewportGestureHelper,
    rightStripPx: Float,
    access: ViewportTransformAccess,
    onInteraction: () -> Unit,
    onDragHoldVisualChange: (Boolean) -> Unit,
    onTransform: (Float, Offset) -> Unit,
): Modifier = pointerInput(gestureHelper, rightStripPx) {
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        onInteraction()
        val session = gestureHelper.beginViewportGesture(firstDown.position, rightStripPx, access)
        try {
            var active = true
            while (active) {
                val event = awaitViewportEventWithHoldTimeout(
                    gestureHelper = gestureHelper,
                    session = session,
                    access = access,
                    onDragEngaged = { onDragHoldVisualChange(true) },
                )
                if (event != null) {
                    active = gestureHelper.processViewportPointerEvent(
                        event = event,
                        session = session,
                        access = access,
                        onDragHoldVisualChange = onDragHoldVisualChange,
                        onTransform = onTransform,
                    )
                }
            }
        } finally {
            if (!session.finishedNormally && gestureHelper.cancelGestureDrag()) {
                onDragHoldVisualChange(false)
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitViewportEventWithHoldTimeout(
    gestureHelper: ViewportGestureHelper,
    session: ViewportGestureSession,
    access: ViewportTransformAccess,
    onDragEngaged: () -> Unit,
): androidx.compose.ui.input.pointer.PointerEvent? {
    val shouldTimeout = session.maxPointers == 1 &&
        !session.startedInScrollStrip &&
        gestureHelper.isDoubleTapDragArmed
    if (!shouldTimeout) return awaitPointerEvent()
    val waitMs = gestureHelper.remainingDoubleTapHoldMs(SystemClock.elapsedRealtime())
    val awaited = withTimeoutOrNull(waitMs) { awaitPointerEvent() }
    if (awaited == null &&
        gestureHelper.tryEngageHoldTimeout(
            nowMs = SystemClock.elapsedRealtime(),
            directTouch = session.useDirectForGesture,
            access = access,
        )
    ) {
        onDragEngaged()
    }
    return awaited
}

private class ViewportGestureSession(
    val startedInScrollStrip: Boolean,
    val useDirectForGesture: Boolean,
    var lastSinglePos: Offset,
    var maxPointers: Int = 1,
    var finishedNormally: Boolean = false,
)

@Composable
private fun ScreenShareFrameOverlay(conn: NuvioConnection, modifier: Modifier = Modifier) {
    val currentFrame by conn.screenFrame.collectAsState()
    currentFrame?.let { bmp ->
        if (!bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = l10n("PC screen", "Tela do computador"),
                contentScale = ContentScale.Fit,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ScreenShareFallbackCursorOverlay(
    conn: NuvioConnection,
    dragHighlight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val remoteCursor by conn.remoteCursor.collectAsState()
    val scheme = LocalNuvioColors.current
    val pointerPath = remember { Path() }
    if (remoteCursor.third) {
        Canvas(modifier = modifier) {
            val cx = remoteCursor.first * size.width
            val cy = remoteCursor.second * size.height
            val u = 1.15.dp.toPx()
            if (dragHighlight) {
                drawCircle(
                    color = scheme.statusWarning.copy(alpha = 0.24f),
                    radius = 15.dp.toPx(),
                    center = Offset(cx, cy),
                )
                drawCircle(
                    color = scheme.statusWarning.copy(alpha = 0.92f),
                    radius = 15.dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            pointerPath.reset()
            pointerPath.moveTo(cx, cy)
            pointerPath.lineTo(cx, cy + 15f * u)
            pointerPath.lineTo(cx + 3.8f * u, cy + 11.6f * u)
            pointerPath.lineTo(cx + 6.6f * u, cy + 17.5f * u)
            pointerPath.lineTo(cx + 8.8f * u, cy + 16.4f * u)
            pointerPath.lineTo(cx + 6.0f * u, cy + 10.6f * u)
            pointerPath.lineTo(cx + 10.8f * u, cy + 10.6f * u)
            pointerPath.close()

            drawPath(
                path = pointerPath,
                color = Color.Black.copy(alpha = 0.88f),
                style = Stroke(width = 2.2.dp.toPx(), join = StrokeJoin.Round),
            )
            drawPath(
                path = pointerPath,
                color = Color.White,
            )
        }
    }
}

private class ViewportGestureHelper(
    private val conn: NuvioConnection,
    decisionDistancePx: Float,
    maxDoubleTapDistancePx: Float,
    dragEngageMovePx: Float,
    maxTapMovePx: Float,
) {
    private val classifier = MultiTouchGestureClassifier(decisionDistancePx = decisionDistancePx)
    private val doubleTapDrag = DoubleTapDragTracker(
        maxDoubleTapDistancePx = maxDoubleTapDistancePx,
        engageMovePx = dragEngageMovePx,
        maxTapMovePx = maxTapMovePx,
    )
    private var totalMovedPx = 0f
    private var suppressNextTap = false

    val isDoubleTapDragArmed: Boolean
        get() = doubleTapDrag.isArmed

    val isGestureDragActive: Boolean
        get() = doubleTapDrag.isDragging

    fun beginViewportGesture(
        downPos: Offset,
        rightStripPx: Float,
        access: ViewportTransformAccess,
    ): ViewportGestureSession {
        resetDrag()
        val startedInScrollStrip = isPointInRightScrollStrip(downPos, access.getContainerWidth(), rightStripPx)
        val startedInsideVideo = isPointInsideVideoRect(downPos, access.getVideoOrigin(), access.getVideoSize())
        if (!startedInScrollStrip) {
            doubleTapDrag.onPointerDown(downPos, SystemClock.elapsedRealtime())
        }
        return ViewportGestureSession(
            startedInScrollStrip = startedInScrollStrip,
            useDirectForGesture = access.getDirectTouch() && startedInsideVideo,
            lastSinglePos = downPos,
        )
    }

    fun processViewportPointerEvent(
        event: androidx.compose.ui.input.pointer.PointerEvent,
        session: ViewportGestureSession,
        access: ViewportTransformAccess,
        onDragHoldVisualChange: (Boolean) -> Unit,
        onTransform: (Float, Offset) -> Unit,
    ): Boolean {
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) {
            session.finishedNormally = true
            val upPos = event.changes.firstOrNull()?.position ?: session.lastSinglePos
            if (session.maxPointers == 1 && !session.startedInScrollStrip &&
                onSinglePointerUp(upPos, SystemClock.elapsedRealtime())
            ) {
                event.changes.forEach { it.consume() }
                onDragHoldVisualChange(false)
            }
            return false
        }
        if (pressed.size > session.maxPointers) session.maxPointers = pressed.size
        if (pressed.size >= 2) {
            if (cancelGestureDrag()) onDragHoldVisualChange(false)
            val (nextZoom, nextPan) = handleMultiTouch(
                zoomChange = event.calculateZoom(),
                panChange = event.calculatePan(),
                currentZoom = access.getZoom(),
                currentPan = access.getPan(),
                viewSize = access.getVideoSize(),
            )
            onTransform(nextZoom, nextPan)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } else if (session.maxPointers == 1) {
            val change = pressed[0]
            session.lastSinglePos = change.position
            if (session.startedInScrollStrip) {
                handleRightEdgeScroll(change)
            } else {
                val engaged = handleSingleFingerMove(
                    change = change,
                    nowMs = SystemClock.elapsedRealtime(),
                    directTouch = session.useDirectForGesture,
                    access = access,
                )
                if (engaged) onDragHoldVisualChange(true)
            }
        }
        return true
    }

    fun handleViewportTap(
        pos: Offset,
        tapSnap: TapSnapState,
        maxSnapPx: Float,
        access: ViewportTransformAccess,
        onAccepted: () -> Unit,
    ) {
        if (consumeSuppressNextTap()) return
        onAccepted()
        val videoOrigin = access.getVideoOrigin()
        val videoSize = access.getVideoSize()
        val useDirect = access.getDirectTouch() && isPointInsideVideoRect(pos, videoOrigin, videoSize)
        val localPos = pos - videoOrigin
        val targetPos = if (useDirect) {
            tapSnap.resolveTap(localPos, SystemClock.elapsedRealtime(), maxSnapPx)
        } else {
            localPos
        }
        dispatchTap(targetPos, useDirect, access.getMonitor(), videoSize, access.getZoom(), access.getPan())
    }

    fun handleViewportLongPress(pos: Offset, access: ViewportTransformAccess, onAccepted: () -> Unit) {
        if (isGestureDragActive) return
        onAccepted()
        val videoOrigin = access.getVideoOrigin()
        val videoSize = access.getVideoSize()
        val useDirect = access.getDirectTouch() && isPointInsideVideoRect(pos, videoOrigin, videoSize)
        dispatchRightClick(
            pos = pos - videoOrigin,
            directTouch = useDirect,
            monitor = access.getMonitor(),
            viewSize = videoSize,
            zoom = access.getZoom(),
            panOffset = access.getPan(),
        )
    }

    fun resetDrag() {
        classifier.reset()
        totalMovedPx = 0f
    }

    fun remainingDoubleTapHoldMs(nowMs: Long): Long = doubleTapDrag.remainingHoldMs(nowMs)

    fun tryEngageHoldTimeout(nowMs: Long, directTouch: Boolean, access: ViewportTransformAccess): Boolean {
        if (!doubleTapDrag.onHoldTimeout(nowMs)) return false
        engageLeftHoldAtAnchor(directTouch = directTouch, access = access)
        return true
    }

    fun onSinglePointerUp(pos: Offset, nowMs: Long): Boolean {
        if (!doubleTapDrag.onPointerUp(pos, nowMs)) return false
        conn.sendMouseHold(MouseButtons.LEFT, false)
        return true
    }

    fun cancelGestureDrag(): Boolean {
        if (!doubleTapDrag.onCancel()) return false
        conn.sendMouseHold(MouseButtons.LEFT, false)
        return true
    }

    fun consumeSuppressNextTap(): Boolean {
        if (!suppressNextTap) return false
        suppressNextTap = false
        return true
    }

    private fun engageLeftHoldAtAnchor(directTouch: Boolean, access: ViewportTransformAccess) {
        suppressNextTap = true
        if (directTouch) {
            val localAnchor = doubleTapDrag.anchorPosition - access.getVideoOrigin()
            val (nx, ny) = mapViewportToNormalized(
                pos = localAnchor,
                size = access.getVideoSize(),
                zoom = access.getZoom(),
                pan = access.getPan(),
            )
            conn.sendMouseAbs(nx, ny, access.getMonitor(), down = MouseButtons.LEFT)
        } else {
            conn.sendMouseHold(MouseButtons.LEFT, true)
        }
    }

    fun dispatchTap(
        pos: Offset,
        directTouch: Boolean,
        monitor: Int,
        viewSize: IntSize,
        zoom: Float,
        panOffset: Offset,
    ) {
        if (directTouch) {
            val (nx, ny) = mapViewportToNormalized(pos, viewSize, zoom, panOffset)
            conn.sendMouseAbs(nx, ny, monitor, click = MouseButtons.LEFT)
        } else {
            val cur = conn.remoteCursor.value
            conn.sendMouseAbs(cur.first, cur.second, monitor, click = MouseButtons.LEFT)
        }
    }

    fun dispatchRightClick(
        pos: Offset,
        directTouch: Boolean,
        monitor: Int,
        viewSize: IntSize,
        zoom: Float,
        panOffset: Offset,
    ) {
        if (directTouch) {
            val (nx, ny) = mapViewportToNormalized(pos, viewSize, zoom, panOffset)
            conn.sendMouseAbs(nx, ny, monitor, click = MouseButtons.RIGHT)
        } else {
            val cur = conn.remoteCursor.value
            conn.sendMouseAbs(cur.first, cur.second, monitor, click = MouseButtons.RIGHT)
        }
    }

    fun handleMultiTouch(
        zoomChange: Float,
        panChange: Offset,
        currentZoom: Float,
        currentPan: Offset,
        viewSize: IntSize,
    ): Pair<Float, Offset> {
        val decision = classifier.process(zoomChange, panChange, currentZoom)
        return when (decision.mode) {
            MultiTouchMode.PINCH_ZOOM -> {
                val nextZoom = (currentZoom * decision.zoomChange).coerceIn(1f, MAX_VIDEO_ZOOM)
                nextZoom to clampVideoPan(currentPan + decision.panChange, viewSize, nextZoom)
            }
            MultiTouchMode.SCROLL -> {
                if (decision.scrollSteps != 0) conn.sendMouseScroll(decision.scrollSteps)
                currentZoom to currentPan
            }
            MultiTouchMode.UNDECIDED -> currentZoom to currentPan
        }
    }

    fun handleRightEdgeScroll(change: androidx.compose.ui.input.pointer.PointerInputChange) {
        val dy = change.position.y - change.previousPosition.y
        if (dy == 0f) return
        totalMovedPx += abs(dy)
        val step = classifier.consumeSingleFingerScroll(dy)
        if (step != 0) conn.sendMouseScroll(step)
        if (totalMovedPx > 2.5f) change.consume()
    }

    fun handleSingleFingerMove(
        change: androidx.compose.ui.input.pointer.PointerInputChange,
        nowMs: Long,
        directTouch: Boolean,
        access: ViewportTransformAccess,
    ): Boolean {
        val delta = change.position - change.previousPosition
        if (delta == Offset.Zero) {
            if (doubleTapDrag.isDragging) change.consume()
            return false
        }
        val deltaDist = delta.getDistance()
        totalMovedPx += deltaDist
        val engagedNow = doubleTapDrag.onPointerMove(change.position, deltaDist, nowMs)
        if (engagedNow) {
            engageLeftHoldAtAnchor(directTouch = directTouch, access = access)
        }
        val viewSize = access.getVideoSize()
        val zoom = access.getZoom()
        val panOffset = access.getPan()
        val monitor = access.getMonitor()
        if (directTouch) {
            val (nx, ny) = mapViewportToNormalized(
                change.position - access.getVideoOrigin(),
                viewSize,
                zoom,
                panOffset,
            )
            conn.sendMouseAbs(nx, ny, monitor)
        } else {
            val cur = conn.remoteCursor.value
            val vw = viewSize.width.coerceAtLeast(1).toFloat() * zoom.coerceAtLeast(1f)
            val vh = viewSize.height.coerceAtLeast(1).toFloat() * zoom.coerceAtLeast(1f)
            val nx = (cur.first + (delta.x / vw) * 1.35f).coerceIn(0f, 1f)
            val ny = (cur.second + (delta.y / vh) * 1.35f).coerceIn(0f, 1f)
            conn.sendMouseAbs(nx, ny, monitor)
        }
        if (totalMovedPx > 2.5f || doubleTapDrag.isDragging) {
            change.consume()
        }
        return engagedNow
    }
}

private fun clampVideoPan(pan: Offset, size: IntSize, zoom: Float): Offset {
    if (zoom <= 1.01f) return Offset.Zero
    val maxX = ((size.width * zoom) - size.width).coerceAtLeast(0f) * 0.5f
    val maxY = ((size.height * zoom) - size.height).coerceAtLeast(0f) * 0.5f
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

private fun mapViewportToNormalized(pos: Offset, size: IntSize, zoom: Float, pan: Offset): Pair<Float, Float> {
    val w = size.width.coerceAtLeast(1).toFloat()
    val h = size.height.coerceAtLeast(1).toFloat()
    val cx = w * 0.5f
    val cy = h * 0.5f
    val unzoomedX = ((pos.x - cx - pan.x) / zoom.coerceAtLeast(1f)) + cx
    val unzoomedY = ((pos.y - cy - pan.y) / zoom.coerceAtLeast(1f)) + cy
    return Pair((unzoomedX / w).coerceIn(0f, 1f), (unzoomedY / h).coerceIn(0f, 1f))
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun copyTextToAndroidClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Nuvio PC", text))
    }
}

private fun readTextFromAndroidClipboard(context: Context): String = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val item = cm.primaryClip?.getItemAt(0) ?: return ""
    item.coerceToText(context)?.toString().orEmpty()
}.getOrDefault("")

private class ScreenShareUiState(initialDirectTouch: Boolean) {
    var directTouch by mutableStateOf(initialDirectTouch)
    var barHidden by mutableStateOf(false)
    var isBarAwake by mutableStateOf(true)
    var showQualityPopover by mutableStateOf(false)
    var showMorePopover by mutableStateOf(false)
    var keyboardVisible by mutableStateOf(false)
    var curtainActive by mutableStateOf(false)
    var dragHoldActive by mutableStateOf(false)
    var reconnectTick by mutableIntStateOf(0)
    var feedbackMessage by mutableStateOf<String?>(null)

    val hasOverlayOpen: Boolean
        get() = showQualityPopover || showMorePopover || keyboardVisible

    fun hideBar() {
        barHidden = true
        showQualityPopover = false
        showMorePopover = false
    }

    fun restoreBar() {
        barHidden = false
        isBarAwake = true
    }

    fun dismissPopovers() {
        showQualityPopover = false
        showMorePopover = false
    }

    fun toggleQuality() {
        isBarAwake = true
        showQualityPopover = !showQualityPopover
        if (showQualityPopover) showMorePopover = false
    }

    fun toggleMore() {
        isBarAwake = true
        showMorePopover = !showMorePopover
        if (showMorePopover) showQualityPopover = false
    }

    fun toggleKeyboard() {
        isBarAwake = true
        keyboardVisible = !keyboardVisible
        dismissPopovers()
    }

    fun toggleTouchMode(context: Context, appPrefs: AppPrefs, hapticsEnabled: Boolean) {
        isBarAwake = true
        val next = !directTouch
        directTouch = next
        appPrefs.screenDirectTouch = next
        if (hapticsEnabled) Haptics.toggle(context)
    }

    fun exitFullscreen(activity: Activity?, clearManualFullscreen: () -> Unit) {
        dismissPopovers()
        keyboardVisible = false
        clearManualFullscreen()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    fun toggleOrientation(activity: Activity?, isLandscape: Boolean, clearManualFullscreen: () -> Unit) {
        if (isLandscape) {
            clearManualFullscreen()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun pasteClipboardToPc(context: Context, conn: NuvioConnection) {
        val clip = readTextFromAndroidClipboard(context)
        if (clip.isBlank()) {
            feedbackMessage = l10nCurrent("Phone clipboard is empty", "Área de transferência do celular vazia")
            return
        }
        conn.sendClipboardSet(clip)
        feedbackMessage = l10nCurrent("Text sent to PC", "Texto enviado ao PC")
    }
}

@Composable
private fun ScreenShareStreamSyncEffects(
    context: Context,
    controller: ScreenStreamController,
    surface: SurfaceViewRenderer?,
    answerSdp: String?,
    defaultProfile: ScreenProfile,
    activeMonitor: Int,
    reconnectTick: Int,
    clipboardTick: Long,
    remoteClipboard: String,
    uiState: ScreenShareUiState,
) {
    LaunchedEffect(answerSdp) {
        answerSdp?.let { controller.applyAnswer(it) }
    }
    LaunchedEffect(defaultProfile, activeMonitor, reconnectTick, surface) {
        if (surface != null) {
            controller.attachOrStart(context, defaultProfile, surface, activeMonitor, reconnectTick)
        }
    }
    LaunchedEffect(uiState.isBarAwake, uiState.hasOverlayOpen) {
        if (uiState.isBarAwake && !uiState.hasOverlayOpen) {
            delay(3600L)
            uiState.isBarAwake = false
        }
    }
    LaunchedEffect(clipboardTick) {
        if (clipboardTick > 0L && remoteClipboard.isNotEmpty()) {
            copyTextToAndroidClipboard(context, remoteClipboard)
            uiState.feedbackMessage = l10nCurrent("Copied from PC clipboard", "Copiado da área de transferência do PC")
        }
    }
    LaunchedEffect(uiState.feedbackMessage) {
        if (uiState.feedbackMessage != null) {
            delay(2200L)
            uiState.feedbackMessage = null
        }
    }
}

@Composable
private fun rememberRtcBackdropLight(
    surface: SurfaceViewRenderer?,
    streamStatus: String,
    hasFallbackFrame: Boolean,
): Boolean? {
    var sampledLight by remember(surface) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(surface, streamStatus, hasFallbackFrame) {
        if (surface != null && !hasFallbackFrame && streamStatus == "live") {
            while (isActive) {
                val listener = createOneShotLumaListener(surface) { isLight ->
                    sampledLight = isLight
                }
                try {
                    runCatching { surface.addFrameListener(listener, RTC_LUMA_SAMPLE_SCALE) }
                    delay(RTC_LUMA_SAMPLE_INTERVAL_MS)
                } finally {
                    runCatching { surface.removeFrameListener(listener) }
                }
            }
        } else if (streamStatus == "idle" || streamStatus == "closed") {
            sampledLight = null
        }
    }
    return sampledLight
}

private fun createOneShotLumaListener(
    surface: SurfaceViewRenderer,
    onSample: (Boolean) -> Unit,
): EglRenderer.FrameListener = object : EglRenderer.FrameListener {
    override fun onFrame(bitmap: Bitmap?) {
        runCatching { surface.removeFrameListener(this) }
        val isLight = isBottomFrameRegionLight(bitmap)
        surface.post { onSample(isLight) }
    }
}

@Composable
private fun ScreenShareLifecycleEffects(
    activity: Activity?,
    view: android.view.View,
    controller: ScreenStreamController,
    conn: NuvioConnection,
    dragHoldActive: Boolean,
    isImmersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
) {
    DisposableEffect(isImmersive, activity) {
        onImmersiveChange(isImmersive)
        val window = activity?.window
        if (window != null) {
            val controllerInsets = WindowInsetsControllerCompat(window, view)
            controllerInsets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isImmersive) {
                controllerInsets.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controllerInsets.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    val currentDragHold by rememberUpdatedState(dragHoldActive)

    DisposableEffect(Unit) {
        onDispose {
            if (currentDragHold) conn.sendMouseHold(MouseButtons.LEFT, false)
            controller.stop()
            conn.screenStop()
            onImmersiveChange(false)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let {
                WindowInsetsControllerCompat(it, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
private fun ScreenShareBackHandler(
    hasOverlayOrFullscreen: Boolean,
    showMorePopover: Boolean,
    showQualityPopover: Boolean,
    keyboardVisible: Boolean,
    onCloseMore: () -> Unit,
    onCloseQuality: () -> Unit,
    onCloseKeyboard: () -> Unit,
    onExitFullscreen: () -> Unit,
) {
    BackHandler(enabled = hasOverlayOrFullscreen) {
        when {
            showMorePopover -> onCloseMore()
            showQualityPopover -> onCloseQuality()
            keyboardVisible -> onCloseKeyboard()
            else -> onExitFullscreen()
        }
    }
}

private fun buildMorePcCallbacks(
    conn: NuvioConnection,
    appPrefs: AppPrefs,
    curtainActive: Boolean,
    dragHoldActive: Boolean,
    screenAudioEnabled: Boolean,
    onCurtainChange: (Boolean) -> Unit,
    onDragHoldChange: (Boolean) -> Unit,
    onFeedback: (String) -> Unit,
    onPasteToPc: () -> Unit,
): MorePcMenuCallbacks = MorePcMenuCallbacks(
    onPasteToPc = onPasteToPc,
    onCopyFromPc = { conn.requestClipboardGet() },
    onPrintScreen = {
        conn.sendKey("PrintScreen")
        onFeedback(l10nCurrent("Screenshot triggered", "Captura de tela enviada"))
    },
    onToggleCurtain = {
        val next = !curtainActive
        onCurtainChange(next)
        conn.sendWindow(if (next) "curtain_on" else "curtain_off")
    },
    onToggleDragHold = {
        val next = !dragHoldActive
        onDragHoldChange(next)
        conn.sendMouseHold(MouseButtons.LEFT, next)
    },
    onToggleAudio = {
        val next = !screenAudioEnabled
        appPrefs.screenAudioEnabled = next
        conn.setScreenAudioEnabled(next)
    },
    onAltTab = { conn.sendKey("AltTab") },
    onWindowsOverview = { conn.sendKey("WinTab") },
    onMutePc = { conn.sendWindow("mute_toggle") },
    onToggleWindowFullscreen = { conn.sendKey("F11") },
    onSendEscape = { conn.sendKey("Escape") },
    onSuspendDisplay = { conn.sendWindow("suspend_display") },
)

private fun buildRadialDockCallbacks(
    conn: NuvioConnection,
    onToggleKeyboard: () -> Unit,
    onPasteToPc: () -> Unit,
    onToggleAudio: () -> Unit,
): RadialDockCallbacks = RadialDockCallbacks(
    onRightClick = { conn.sendMouseClick(MouseButtons.RIGHT) },
    onToggleKeyboard = onToggleKeyboard,
    onAltTab = { conn.sendKey("AltTab") },
    onCopyFromPc = { conn.requestClipboardGet() },
    onPasteToPc = onPasteToPc,
    onSendEscape = { conn.sendKey("Escape") },
    onToggleAudio = onToggleAudio,
    onToggleWindowFullscreen = { conn.sendKey("F11") },
)
