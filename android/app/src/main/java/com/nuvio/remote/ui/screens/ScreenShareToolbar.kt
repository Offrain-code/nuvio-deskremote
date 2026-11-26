package com.nuvio.remote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.ScreenProfile
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.ClearContentShadow
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass

internal data class MorePcMenuState(
    val curtainActive: Boolean,
    val dragHoldActive: Boolean,
    val screenAudioEnabled: Boolean,
)

internal data class MorePcMenuCallbacks(
    val onPasteToPc: () -> Unit,
    val onCopyFromPc: () -> Unit,
    val onPrintScreen: () -> Unit,
    val onToggleCurtain: () -> Unit,
    val onToggleDragHold: () -> Unit,
    val onToggleAudio: () -> Unit,
    val onAltTab: () -> Unit,
    val onWindowsOverview: () -> Unit,
    val onMutePc: () -> Unit,
    val onToggleWindowFullscreen: () -> Unit,
    val onSendEscape: () -> Unit,
    val onSuspendDisplay: () -> Unit,
)

private data class PcGridAction(
    val label: String,
    val icon: ImageVector,
    val highlighted: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
internal fun DiscreetExitFullscreenButton(
    isBackdropLight: Boolean,
    onExitFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current

    val exitLabel = l10n("Exit fullscreen", "Sair da tela cheia")

    Box(
        modifier = modifier
            .size(48.dp)
            .semantics {
                role = Role.Button
                contentDescription = exitLabel
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                onExitFullscreen()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .nuvioGlass(
                    shape = CircleShape,
                    variant = GlassVariant.CLEAR,
                    isBackdropLight = isBackdropLight,
                    captureStageBackdrop = false,
                    resting = true,
                ),
            contentAlignment = Alignment.Center,
        ) {
            NuvioIcon(
                imageVector = Icons.Rounded.FullscreenExit,
                contentDescription = exitLabel,
                tint = scheme.clearOverlayText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun RestoreFullscreenBarButton(
    isBackdropLight: Boolean,
    onRestoreBar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val showBarDesc = l10n("Show bar", "Mostrar barra")

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 96.dp, minHeight = 48.dp)
            .semantics {
                role = Role.Button
                contentDescription = showBarDesc
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.tab(context)
                onRestoreBar()
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .padding(bottom = 2.dp)
                .defaultMinSize(minWidth = 86.dp)
                .height(28.dp)
                .nuvioGlass(
                    shape = RoundedCornerShape(999.dp),
                    variant = GlassVariant.CLEAR,
                    isBackdropLight = isBackdropLight,
                    captureStageBackdrop = false,
                    resting = true,
                )
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioIcon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = null,
                tint = scheme.clearOverlayText,
                modifier = Modifier.size(16.dp),
            )
            NuvioText(
                text = l10n("Bar", "Barra"),
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.clearOverlayText,
                shadow = ClearContentShadow,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Single Liquid Glass Clear capsule for landscape/fullscreen mode:
 * 6 inline pills (32dp visual height inside a 40dp capsule with 48dp hitboxes).
 */
@Composable
internal fun FullscreenBottomToolBar(
    isBackdropLight: Boolean,
    isBarAwake: Boolean,
    activeMonitor: Int,
    activeProfile: ScreenProfile,
    showQualityPopover: Boolean,
    directTouch: Boolean,
    keyboardVisible: Boolean,
    isLandscape: Boolean,
    showMorePopover: Boolean,
    onHideBar: () -> Unit,
    onToggleQuality: () -> Unit,
    onToggleTouchMode: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleMorePc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val isResting = !(isBarAwake || showQualityPopover || showMorePopover)
    val fgColor = scheme.clearOverlayText
    val monitorTag = if (activeMonitor == 1) "PC 1" else "TV 2"
    val qualityLabel = "${activeProfile.shortLabel.substringBefore(" ")} • $monitorTag"

    Box(
        modifier = modifier
            .wrapContentWidth()
            .widthIn(max = 620.dp)
            .height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 4.dp)
                .nuvioGlass(
                    shape = RoundedCornerShape(999.dp),
                    variant = GlassVariant.CLEAR,
                    isBackdropLight = isBackdropLight,
                    captureStageBackdrop = false,
                    resting = isResting,
                ),
        )
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LandscapeCapsuleItem(
                label = "",
                contentDescription = l10n("Hide bar", "Recolher barra"),
                icon = Icons.Rounded.KeyboardArrowDown,
                selected = false,
                fgColor = fgColor,
                iconOnly = true,
                onClick = onHideBar,
            )
            LandscapeCapsuleItem(
                label = qualityLabel,
                contentDescription = "${l10n("Quality and monitor", "Qualidade e monitor")}: $qualityLabel",
                icon = null,
                leadingDotColor = scheme.statusOnline,
                selected = showQualityPopover,
                fgColor = fgColor,
                onClick = onToggleQuality,
            )
            LandscapeCapsuleItem(
                label = if (directTouch) l10n("Touch", "Toque") else "Trackpad",
                contentDescription = if (directTouch) {
                    l10n("Direct Touch mode", "Modo Toque")
                } else {
                    l10n("Trackpad mode", "Modo Trackpad")
                },
                icon = if (directTouch) Icons.Rounded.TouchApp else Icons.Rounded.Mouse,
                selected = !directTouch,
                fgColor = fgColor,
                onClick = onToggleTouchMode,
            )
            LandscapeCapsuleItem(
                label = l10n("Keyboard", "Teclado"),
                contentDescription = l10n("Keyboard", "Teclado"),
                icon = Icons.Rounded.Keyboard,
                selected = keyboardVisible,
                fgColor = fgColor,
                onClick = onToggleKeyboard,
            )
            LandscapeCapsuleItem(
                label = if (isLandscape) l10n("Portrait", "Retrato") else l10n("Rotate", "Girar"),
                contentDescription = if (isLandscape) {
                    l10n("Switch to portrait", "Voltar para retrato")
                } else {
                    l10n("Rotate screen", "Girar tela")
                },
                icon = Icons.Rounded.ScreenRotation,
                selected = false,
                fgColor = fgColor,
                onClick = onToggleOrientation,
            )
            LandscapeCapsuleItem(
                label = l10n("More PC", "Mais PC"),
                contentDescription = l10n("More PC actions", "Mais ações do PC"),
                icon = Icons.Rounded.MoreHoriz,
                selected = showMorePopover,
                fgColor = fgColor,
                onClick = onToggleMorePc,
            )
        }
    }
}

@Composable
private fun LandscapeCapsuleItem(
    label: String,
    contentDescription: String,
    icon: ImageVector?,
    selected: Boolean,
    fgColor: Color,
    onClick: () -> Unit,
    leadingDotColor: Color = Color.Unspecified,
    iconOnly: Boolean = false,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.92f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "capsuleItemScale",
    )

    val itemFg = if (selected) scheme.clearPillActiveFg else fgColor
    val bgModifier = if (selected) {
        Modifier.background(
            Brush.verticalGradient(listOf(scheme.clearPillActiveBgTop, scheme.clearPillActiveBgBottom)),
        )
    } else if (pressed) {
        Modifier.background(Color.White.copy(alpha = 0.22f))
    } else {
        Modifier
            .background(Color.Black.copy(alpha = 0.20f))
            .border(0.75.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
    }

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .then(if (iconOnly) Modifier.size(32.dp) else Modifier.height(32.dp))
                .clip(RoundedCornerShape(999.dp))
                .then(bgModifier)
                .padding(horizontal = if (iconOnly) 0.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingDotColor != Color.Unspecified) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(leadingDotColor),
                )
            }
            icon?.let {
                NuvioIcon(
                    imageVector = it,
                    contentDescription = null,
                    tint = itemFg,
                    modifier = Modifier.size(15.dp),
                )
            }
            if (!iconOnly && label.isNotBlank()) {
                NuvioText(
                    text = label,
                    style = NuvioTypography.KeyLabelCompact,
                    color = itemFg,
                    shadow = if (selected) null else ClearContentShadow,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun QualityAndMonitorPopover(
    visible: Boolean,
    isBackdropLight: Boolean,
    activeMonitor: Int,
    activeProfile: ScreenProfile,
    onSelectMonitor: (Int) -> Unit,
    onSelectProfile: (ScreenProfile) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current

    AnimatedVisibility(
        visible = visible,
        enter = NuvioMotion.morphEnter(reducedMotion),
        exit = NuvioMotion.morphExit(reducedMotion),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .nuvioGlass(
                    shape = RoundedCornerShape(22.dp),
                    variant = GlassVariant.CLEAR,
                    isBackdropLight = isBackdropLight,
                    captureStageBackdrop = false,
                    resting = false,
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioText(
                    text = l10n("MONITOR & QUALITY", "MONITOR E QUALIDADE"),
                    style = NuvioTypography.SectionMicroTitle,
                    color = scheme.clearOverlayText,
                    shadow = ClearContentShadow,
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.28f))
                            .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioIcon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = l10n("Close", "Fechar"),
                            tint = scheme.clearOverlayText,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PopoverSelectPill(
                    label = "PC 1",
                    selected = activeMonitor == 1,
                    onClick = { onSelectMonitor(1) },
                    modifier = Modifier.weight(1f),
                )
                PopoverSelectPill(
                    label = "TV 2",
                    selected = activeMonitor == 2,
                    onClick = { onSelectMonitor(2) },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ScreenProfile.entries.forEach { profile ->
                    PopoverSelectPill(
                        label = profile.shortLabel.replace(" ", " • "),
                        selected = profile == activeProfile,
                        onClick = { onSelectProfile(profile) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun MorePcPopoverMenu(
    visible: Boolean,
    isBackdropLight: Boolean,
    state: MorePcMenuState,
    callbacks: MorePcMenuCallbacks,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val actions = listOf(
        PcGridAction(l10n("Paste PC", "Colar no PC"), Icons.Rounded.ContentPaste) {
            callbacks.onPasteToPc()
            onDismiss()
        },
        PcGridAction(l10n("Copy PC", "Copiar do PC"), Icons.Rounded.ContentCopy) {
            callbacks.onCopyFromPc()
            onDismiss()
        },
        PcGridAction("Print", Icons.Rounded.CameraAlt) {
            callbacks.onPrintScreen()
            onDismiss()
        },
        PcGridAction(l10n("Curtain", "Cortina"), Icons.Rounded.VisibilityOff, state.curtainActive) {
            callbacks.onToggleCurtain()
        },
        PcGridAction(l10n("Drag", "Arrastar"), Icons.Rounded.OpenWith, state.dragHoldActive) {
            callbacks.onToggleDragHold()
        },
        PcGridAction(
            if (state.screenAudioEnabled) l10n("PC Audio", "Som PC") else l10n("Muted", "Som Mudo"),
            if (state.screenAudioEnabled) {
                Icons.AutoMirrored.Rounded.VolumeUp
            } else {
                Icons.AutoMirrored.Rounded.VolumeOff
            },
            state.screenAudioEnabled,
        ) { callbacks.onToggleAudio() },
        PcGridAction("Alt+Tab", Icons.Rounded.SwapHoriz) {
            callbacks.onAltTab()
            onDismiss()
        },
        PcGridAction(l10n("Overview", "Foco PC"), Icons.Rounded.CenterFocusStrong) {
            callbacks.onWindowsOverview()
            onDismiss()
        },
        PcGridAction(l10n("Mute PC", "Mudo PC"), Icons.AutoMirrored.Rounded.VolumeOff) {
            callbacks.onMutePc()
            onDismiss()
        },
        PcGridAction(l10n("Full (F11)", "Tela (F11)"), Icons.Rounded.Fullscreen) {
            callbacks.onToggleWindowFullscreen()
            onDismiss()
        },
        PcGridAction("ESC", Icons.Rounded.Close) {
            callbacks.onSendEscape()
            onDismiss()
        },
        PcGridAction(l10n("Power", "Energia"), Icons.Rounded.PowerSettingsNew) {
            callbacks.onSuspendDisplay()
            onDismiss()
        },
    )

    AnimatedVisibility(
        visible = visible,
        enter = NuvioMotion.morphEnter(reducedMotion),
        exit = NuvioMotion.morphExit(reducedMotion),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .nuvioGlass(
                    shape = RoundedCornerShape(22.dp),
                    variant = GlassVariant.CLEAR,
                    isBackdropLight = isBackdropLight,
                    captureStageBackdrop = false,
                    resting = false,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioText(
                    text = l10n("PC ACTIONS", "AÇÕES DO COMPUTADOR"),
                    style = NuvioTypography.SectionMicroTitle,
                    color = scheme.clearOverlayText,
                    shadow = ClearContentShadow,
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.28f))
                            .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioIcon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = l10n("Close", "Fechar"),
                            tint = scheme.clearOverlayText,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            actions.chunked(4).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems.forEach { action ->
                        MorePcGridTile(
                            action = action,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PopoverSelectPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = LocalNuvioColors.current
    val fg = if (selected) scheme.clearPillActiveFg else scheme.clearOverlayText
    val bgModifier = if (selected) {
        Modifier.background(
            Brush.verticalGradient(listOf(scheme.clearPillActiveBgTop, scheme.clearPillActiveBgBottom)),
        )
    } else {
        Modifier
            .background(Color.Black.copy(alpha = 0.28f))
            .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(bgModifier)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = fg,
                shadow = if (selected) null else ClearContentShadow,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MorePcGridTile(action: PcGridAction, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val fg = if (action.highlighted) scheme.clearPillActiveFg else scheme.clearOverlayText
    val bgModifier = if (action.highlighted) {
        Modifier.background(
            Brush.verticalGradient(listOf(scheme.clearPillActiveBgTop, scheme.clearPillActiveBgBottom)),
        )
    } else {
        Modifier
            .background(Color.Black.copy(alpha = 0.28f))
            .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                action.onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(bgModifier)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioIcon(
                imageVector = action.icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(15.dp),
            )
            NuvioText(
                text = action.label,
                style = NuvioTypography.KeyShortcutBadge,
                color = fg,
                shadow = if (action.highlighted) null else ClearContentShadow,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
