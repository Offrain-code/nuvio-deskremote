package com.nuvio.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.ConnState
import com.nuvio.remote.connection.NuvioConnection
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.components.StatusLed
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.ClearContentShadow
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass
import org.webrtc.SurfaceViewRenderer

@Composable
internal fun PortraitScreenShareContent(
    conn: NuvioConnection,
    connState: ConnState,
    isTunnelActive: Boolean,
    videoAspect: Float,
    showFallbackCursor: Boolean,
    directTouch: Boolean,
    activeMonitor: Int,
    screenAudioEnabled: Boolean,
    curtainActive: Boolean,
    isBackdropLight: Boolean,
    moreCallbacks: MorePcMenuCallbacks,
    onSurfaceReady: (SurfaceViewRenderer) -> Unit,
    onSurfaceRelease: (SurfaceViewRenderer) -> Unit = {},
    hideFallbackFrame: Boolean = false,
    onEnterFullscreen: () -> Unit,
    onRotateLandscape: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(videoAspect.coerceIn(1.2f, 2.4f))
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(18.dp)),
            ) {
                VideoStreamViewport(
                    conn = conn,
                    showFallbackCursor = showFallbackCursor,
                    directTouch = directTouch,
                    activeMonitor = activeMonitor,
                    hideFallbackFrame = hideFallbackFrame,
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceRelease = onSurfaceRelease,
                )

                PortraitCornerGlassPill(
                    label = l10n("Fullscreen", "Tela Cheia"),
                    icon = Icons.Rounded.Fullscreen,
                    isBackdropLight = isBackdropLight,
                    onClick = onEnterFullscreen,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 4.dp, top = 2.dp),
                )

                PortraitCornerGlassPill(
                    label = l10n("Landscape", "Paisagem"),
                    icon = Icons.Rounded.ScreenRotation,
                    isBackdropLight = isBackdropLight,
                    onClick = onRotateLandscape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 4.dp, top = 2.dp),
                )
            }
        }

        item {
            PortraitStatusControlCard(
                connState = connState,
                isTunnelActive = isTunnelActive,
                activeMonitor = activeMonitor,
                screenAudioEnabled = screenAudioEnabled,
                curtainActive = curtainActive,
                moreCallbacks = moreCallbacks,
                onReconnect = onReconnect,
                onEnterFullscreen = onEnterFullscreen,
            )
        }
    }
}

@Composable
private fun PortraitCornerGlassPill(
    label: String,
    icon: ImageVector,
    isBackdropLight: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .nuvioGlass(
                    shape = CircleShape,
                    variant = GlassVariant.CLEAR,
                    isBackdropLight = isBackdropLight,
                    captureStageBackdrop = false,
                    resting = false,
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.clearOverlayText,
                modifier = Modifier.size(14.dp),
            )
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.clearOverlayText,
                shadow = ClearContentShadow,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun PortraitStatusControlCard(
    connState: ConnState,
    isTunnelActive: Boolean,
    activeMonitor: Int,
    screenAudioEnabled: Boolean,
    curtainActive: Boolean,
    moreCallbacks: MorePcMenuCallbacks,
    onReconnect: () -> Unit,
    onEnterFullscreen: () -> Unit,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val monitorName = if (activeMonitor == 1) {
        l10n("PC Monitor 1", "Monitor PC 1")
    } else {
        l10n("TV Monitor 2", "Monitor TV 2")
    }
    val originName = if (isTunnelActive) l10n("Remote", "Remoto") else l10n("Home", "Casa")
    val statusText = when (connState) {
        is ConnState.Connected -> "${l10n("Live", "Ao vivo")} • $monitorName • $originName"
        is ConnState.Connecting -> l10n("Connecting PC screen…", "Conectando tela do PC…")
        is ConnState.Failed -> l10n("Connection failed", "Falha ao conectar")
        is ConnState.Disconnected -> l10n("Waiting for connection", "Aguardando conexão")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(22.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusLed(connState = connState)
                NuvioText(
                    text = statusText,
                    style = NuvioTypography.KeyLabelPrimary,
                    color = scheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PortraitCompactPill(
                label = l10n("Reconnect", "Reconectar"),
                icon = Icons.Rounded.Refresh,
                highlighted = false,
                visualHeight = 34.dp,
                onClick = {
                    if (hapticsEnabled) Haptics.action(context)
                    onReconnect()
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PortraitCompactPill(
                label = l10n("Open Fullscreen", "Abrir Tela Cheia"),
                icon = Icons.Rounded.Fullscreen,
                highlighted = true,
                visualHeight = 38.dp,
                onClick = {
                    if (hapticsEnabled) Haptics.action(context)
                    onEnterFullscreen()
                },
                modifier = Modifier.weight(1f),
            )
            PortraitCompactPill(
                label = if (screenAudioEnabled) {
                    l10n("PC Audio: On", "Som PC: Ativo")
                } else {
                    l10n("PC Audio: Muted", "Som PC: Mudo")
                },
                icon = if (screenAudioEnabled) {
                    Icons.AutoMirrored.Rounded.VolumeUp
                } else {
                    Icons.AutoMirrored.Rounded.VolumeOff
                },
                highlighted = false,
                visualHeight = 38.dp,
                onClick = {
                    if (hapticsEnabled) Haptics.toggle(context)
                    moreCallbacks.onToggleAudio()
                },
                modifier = Modifier.weight(1f),
            )
        }

        NuvioText(
            text = l10n("PC SHORTCUTS", "ATALHOS DO COMPUTADOR"),
            style = NuvioTypography.SectionMicroTitle,
            color = scheme.textMuted,
        )

        PortraitShortcutsGrid(
            curtainActive = curtainActive,
            moreCallbacks = moreCallbacks,
        )
    }
}

@Composable
private fun PortraitShortcutsGrid(curtainActive: Boolean, moreCallbacks: MorePcMenuCallbacks) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PortraitCompactPill(
                label = l10n("Paste", "Colar"),
                icon = Icons.Rounded.ContentPaste,
                highlighted = false,
                visualHeight = 38.dp,
                onClick = {
                    if (hapticsEnabled) Haptics.action(context)
                    moreCallbacks.onPasteToPc()
                },
                modifier = Modifier.weight(1f),
            )
            PortraitCompactPill(
                label = l10n("Copy", "Copiar"),
                icon = Icons.Rounded.ContentCopy,
                highlighted = false,
                visualHeight = 38.dp,
                onClick = {
                    if (hapticsEnabled) Haptics.action(context)
                    moreCallbacks.onCopyFromPc()
                },
                modifier = Modifier.weight(1f),
            )
            PortraitCompactPill(
                label = "Alt+Tab",
                icon = Icons.Rounded.SwapHoriz,
                highlighted = false,
                visualHeight = 38.dp,
                onClick = {
                    if (hapticsEnabled) Haptics.action(context)
                    moreCallbacks.onAltTab()
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PortraitCompactPill(
                label = l10n("Overview", "Foco PC"),
                icon = Icons.Rounded.CenterFocusStrong,
                highlighted = false,
                visualHeight = 38.dp,
                onClick = {
                    if (hapticsEnabled) Haptics.action(context)
                    moreCallbacks.onWindowsOverview()
                },
                modifier = Modifier.weight(1f),
            )
            PortraitCompactPill(
                label = l10n("Curtain", "Cortina"),
                icon = Icons.Rounded.VisibilityOff,
                highlighted = curtainActive,
                visualHeight = 38.dp,
                onClick = {
                    if (hapticsEnabled) Haptics.toggle(context)
                    moreCallbacks.onToggleCurtain()
                },
                modifier = Modifier.weight(1f),
            )
            PortraitCompactPill(
                label = l10n("Mute PC", "Mudo PC"),
                icon = Icons.AutoMirrored.Rounded.VolumeOff,
                highlighted = false,
                visualHeight = 38.dp,
                onClick = {
                    if (hapticsEnabled) Haptics.action(context)
                    moreCallbacks.onMutePc()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PortraitCompactPill(
    label: String,
    icon: ImageVector,
    highlighted: Boolean,
    visualHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val fg = if (highlighted) scheme.pillActiveFg else scheme.textPrimary
    val bgModifier = if (highlighted) {
        Modifier.background(
            Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)),
        )
    } else {
        Modifier
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(13.dp))
    }

    Box(
        modifier = modifier
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
                .fillMaxWidth()
                .height(visualHeight)
                .clip(RoundedCornerShape(13.dp))
                .then(bgModifier)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(15.dp),
            )
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = fg,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
