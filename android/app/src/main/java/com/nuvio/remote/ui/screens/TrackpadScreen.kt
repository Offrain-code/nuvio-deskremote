package com.nuvio.remote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.nuvio.remote.connection.MouseButtons
import com.nuvio.remote.connection.NuvioConnection
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioGlassSlider
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.components.RemoteTextInputBar
import com.nuvio.remote.ui.i18n.LocalAppLanguage
import com.nuvio.remote.ui.i18n.ResolvedLanguage
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SENSITIVITY_MIN = 0.6f
private const val SENSITIVITY_MAX = 4.0f
private val SPEED_PRESETS = listOf(1.0f, 1.8f, 2.5f, 3.5f)

/**
 * Trackpad tab exclusive to PC Mode:
 * opaque precision dot-grid touch surface, side scroll rail,
 * left/middle/right mouse buttons, PC shortcuts, and floating remote keyboard.
 */
@Composable
fun TrackpadScreen(
    conn: NuvioConnection,
    modifier: Modifier = Modifier,
    initialSensitivity: Float = 1.6f,
    onSensitivityChange: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val textInputFocusTick by conn.textInputFocusTick.collectAsState()

    var sensitivity by remember(initialSensitivity) {
        mutableFloatStateOf(initialSensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX))
    }
    var keyboardVisible by remember { mutableStateOf(false) }
    var dragLocked by remember { mutableStateOf(false) }
    var sensitivityExpanded by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            if (dragLocked) {
                conn.sendMouseHold(MouseButtons.LEFT, false)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrackpadQuickActions(
                keyboardVisible = keyboardVisible,
                dragLocked = dragLocked,
                onToggleKeyboard = { keyboardVisible = !keyboardVisible },
                onMutePc = { conn.sendWindow("mute_toggle") },
                onToggleDragLock = {
                    val next = !dragLocked
                    dragLocked = next
                    conn.sendMouseHold(MouseButtons.LEFT, next)
                    if (hapticsEnabled) Haptics.toggle(context)
                },
                onSuspendDisplay = { conn.sendWindow("suspend_display") },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrackpadTouchSurface(
                    sensitivity = sensitivity,
                    dragLocked = dragLocked,
                    onMove = { dx, dy -> conn.sendMouseMove(dx, dy) },
                    onTap = {
                        if (hapticsEnabled) Haptics.action(context)
                        conn.sendMouseClick(MouseButtons.LEFT)
                    },
                    onTwoFingerTap = {
                        if (hapticsEnabled) Haptics.action(context)
                        conn.sendMouseClick(MouseButtons.RIGHT)
                    },
                    onScroll = { delta ->
                        if (hapticsEnabled) Haptics.scroll(context)
                        conn.sendMouseScroll(delta)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )

                TrackpadScrollRail(
                    onScroll = { delta ->
                        if (hapticsEnabled) Haptics.scroll(context)
                        conn.sendMouseScroll(delta)
                    },
                    modifier = Modifier
                        .width(48.dp)
                        .fillMaxHeight(),
                )
            }

            MouseButtonsRow(
                dragLocked = dragLocked,
                onClickButton = { btn ->
                    if (hapticsEnabled) Haptics.action(context)
                    conn.sendMouseClick(btn)
                },
            )

            TrackpadSensitivityCard(
                sensitivity = sensitivity,
                expanded = sensitivityExpanded,
                onToggleExpanded = { sensitivityExpanded = !sensitivityExpanded },
                onSensitivityChange = { next ->
                    sensitivity = next
                    onSensitivityChange(next)
                },
            )
        }

        RemoteTextInputBar(
            visible = keyboardVisible,
            onVisibilityChange = { keyboardVisible = it },
            onSendText = { conn.sendType(it) },
            onSendKey = { key, repeat -> conn.sendKey(key, repeat) },
            focusTick = textInputFocusTick,
            placeholder = l10n("Type on PC via Trackpad…", "Digitar no PC via Trackpad…"),
            showSpecialKeys = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }
}

internal enum class TrackpadQuickActionsMode {
    STANDARD_4_COL,
    COMPACT_4_COL,
    ACCESSIBLE_GRID_2X2,
}

internal fun resolveTrackpadQuickActionsMode(maxWidth: Dp, fontScale: Float): TrackpadQuickActionsMode = when {
    maxWidth < 328.dp || fontScale >= 1.25f -> TrackpadQuickActionsMode.ACCESSIBLE_GRID_2X2
    maxWidth < 360.dp || fontScale >= 1.15f -> TrackpadQuickActionsMode.COMPACT_4_COL
    else -> TrackpadQuickActionsMode.STANDARD_4_COL
}

private data class TrackpadActionSpec(
    val label: String,
    val icon: ImageVector,
    val active: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun TrackpadQuickActions(
    keyboardVisible: Boolean,
    dragLocked: Boolean,
    onToggleKeyboard: () -> Unit,
    onMutePc: () -> Unit,
    onToggleDragLock: () -> Unit,
    onSuspendDisplay: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val mode = resolveTrackpadQuickActionsMode(maxWidth, fontScale)
        val compactLabels = mode == TrackpadQuickActionsMode.COMPACT_4_COL
        val kbLabel = l10n("Keyboard", "Teclado")
        val dragLabel = if (dragLocked) l10n("Release", "Soltar") else l10n("Drag", "Arrastar")
        val muteLabel = if (compactLabels) l10n("Mute", "Mudo") else l10n("Mute PC", "Mudo PC")
        val screenOffLabel = if (compactLabels) {
            l10n("Disp Off", "Tela Off")
        } else {
            l10n("Screen off", "Apagar tela")
        }
        val actions = listOf(
            TrackpadActionSpec(kbLabel, Icons.Rounded.Keyboard, keyboardVisible, onToggleKeyboard),
            TrackpadActionSpec(dragLabel, Icons.Rounded.OpenWith, dragLocked, onToggleDragLock),
            TrackpadActionSpec(muteLabel, Icons.AutoMirrored.Rounded.VolumeOff, false, onMutePc),
            TrackpadActionSpec(screenOffLabel, Icons.Rounded.MonitorHeart, false, onSuspendDisplay),
        )

        if (mode == TrackpadQuickActionsMode.ACCESSIBLE_GRID_2X2) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    actions.take(2).forEach { act ->
                        TrackpadActionChip(
                            label = act.label,
                            icon = act.icon,
                            active = act.active,
                            mode = mode,
                            onClick = act.onClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    actions.drop(2).forEach { act ->
                        TrackpadActionChip(
                            label = act.label,
                            icon = act.icon,
                            active = act.active,
                            mode = mode,
                            onClick = act.onClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else {
            val gap = if (compactLabels) 4.dp else 6.dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                actions.forEach { act ->
                    TrackpadActionChip(
                        label = act.label,
                        icon = act.icon,
                        active = act.active,
                        mode = mode,
                        onClick = act.onClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackpadActionChip(
    label: String,
    icon: ImageVector,
    active: Boolean,
    mode: TrackpadQuickActionsMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.96f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "trackpadChipScale",
    )

    val fg = if (active) scheme.pillActiveFg else scheme.textPrimary
    val bgModifier = if (active) {
        Modifier.background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
    } else {
        Modifier
            .background(if (pressed) scheme.contentCellPressed else scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
    }

    val chipModifier = Modifier
        .fillMaxWidth()
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(RoundedCornerShape(14.dp))
        .then(bgModifier)

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (mode == TrackpadQuickActionsMode.ACCESSIBLE_GRID_2X2) {
            Row(
                modifier = chipModifier
                    .height(38.dp)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioIcon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = fg,
                    modifier = Modifier.size(16.dp),
                )
                NuvioText(
                    text = label,
                    style = NuvioTypography.KeyLabelCompact.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            val isCompact = mode == TrackpadQuickActionsMode.COMPACT_4_COL
            val textStyle = if (isCompact) {
                NuvioTypography.KeyLabelCompact.copy(fontSize = 11.sp, letterSpacing = (-0.01).em)
            } else {
                NuvioTypography.KeyLabelCompact
            }
            Column(
                modifier = chipModifier
                    .height(if (isCompact) 42.dp else 44.dp)
                    .padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                NuvioIcon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = fg,
                    modifier = Modifier.size(if (isCompact) 15.dp else 16.dp),
                )
                NuvioText(
                    text = label,
                    style = textStyle,
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TrackpadTouchSurface(
    sensitivity: Float,
    dragLocked: Boolean,
    onMove: (Int, Int) -> Unit,
    onTap: () -> Unit,
    onTwoFingerTap: () -> Unit,
    onScroll: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(listOf(scheme.trackpadSurfaceTop, scheme.trackpadSurfaceBottom)),
            )
            .border(
                width = 1.dp,
                color = if (dragLocked) scheme.statusWarning else scheme.contentBorder,
                shape = RoundedCornerShape(22.dp),
            )
            .trackpadGestures(
                sensitivity = sensitivity,
                onMove = onMove,
                onTap = onTap,
                onTwoFingerTap = onTwoFingerTap,
                onScroll = onScroll,
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 16.dp.toPx()
            val dotRadius = 1.1.dp.toPx()
            var y = step
            while (y < size.height - step * 0.5f) {
                var x = step
                while (x < size.width - step * 0.5f) {
                    drawCircle(
                        color = scheme.trackpadDots,
                        radius = dotRadius,
                        center = Offset(x, y),
                    )
                    x += step
                }
                y += step
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(scheme.contentCellSurface)
                    .border(
                        width = 1.dp,
                        color = if (dragLocked) scheme.statusWarning else scheme.contentBorder,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.TouchApp,
                    contentDescription = null,
                    tint = if (dragLocked) scheme.statusWarning else scheme.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            NuvioText(
                text = if (dragLocked) {
                    l10n("Drag active • tap to release", "Arraste ativo • toque para soltar")
                } else {
                    l10n("PC Touch Surface", "Área tátil do PC")
                },
                style = NuvioTypography.KeyLabelPrimary,
                color = if (dragLocked) scheme.statusWarning else scheme.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            NuvioText(
                text = l10n(
                    "1 tap: left click • 2 fingers: scroll\n" +
                        "Double-tap & hold: drag • 2-finger tap: right click",
                    "1 toque: clique esquerdo • 2 dedos: rolar\n" +
                        "2 toques + segurar: arrastar • toque 2 dedos: direito",
                ),
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun TrackpadScrollRail(onScroll: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = LocalNuvioColors.current
    var scrollAccum by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(18.dp))
            .pointerInput(onScroll) {
                detectDragGestures(
                    onDragEnd = { scrollAccum = 0f },
                    onDragCancel = { scrollAccum = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    scrollAccum += dragAmount.y * 0.16f
                    val delta = scrollAccum.toInt()
                    if (delta != 0) {
                        scrollAccum -= delta
                        onScroll(delta)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            NuvioIcon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = l10n("Scroll up", "Rolar para cima"),
                tint = scheme.textMuted,
                modifier = Modifier.size(18.dp),
            )
            NuvioText(
                text = l10n("SCROLL", "ROLAR"),
                style = NuvioTypography.SectionMicroTitle,
                color = scheme.textSecondary,
            )
            NuvioIcon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = l10n("Scroll down", "Rolar para baixo"),
                tint = scheme.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun MouseButtonsRow(dragLocked: Boolean, onClickButton: (MouseButtons) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MouseButtonTile(
            label = if (dragLocked) l10n("Left (Hold)", "Esquerdo (Seg.)") else l10n("Left", "Esquerdo"),
            highlighted = dragLocked,
            onClick = { onClickButton(MouseButtons.LEFT) },
            modifier = Modifier.weight(1.35f),
        )
        MouseButtonTile(
            label = l10n("Middle", "Meio"),
            highlighted = false,
            onClick = { onClickButton(MouseButtons.MIDDLE) },
            modifier = Modifier.weight(0.90f),
        )
        MouseButtonTile(
            label = l10n("Right", "Direito"),
            highlighted = false,
            onClick = { onClickButton(MouseButtons.RIGHT) },
            modifier = Modifier.weight(1.35f),
        )
    }
}

@Composable
private fun MouseButtonTile(label: String, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.96f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "mouseBtnScale",
    )

    val fg = if (highlighted) scheme.pillActiveFg else scheme.textPrimary
    val bgModifier = if (highlighted) {
        Modifier.background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
    } else {
        Modifier
            .background(if (pressed) scheme.contentCellPressed else scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(16.dp))
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(16.dp))
                .then(bgModifier),
            contentAlignment = Alignment.Center,
        ) {
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelPrimary,
                color = fg,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrackpadSensitivityCard(
    sensitivity: Float,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSensitivityChange: (Float) -> Unit,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val lang = LocalAppLanguage.current
    val headerShape = RoundedCornerShape(18.dp)
    val stateDesc = if (expanded) l10n("Expanded", "Expandido") else l10n("Collapsed", "Recolhido")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(headerShape)
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, headerShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable(
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (hapticsEnabled) Haptics.tab(context)
                    onToggleExpanded()
                }
                .semantics {
                    stateDescription = stateDesc
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = scheme.textMuted,
                    modifier = Modifier.size(16.dp),
                )
                NuvioText(
                    text = l10n("Cursor speed", "Velocidade do cursor"),
                    style = NuvioTypography.KeyLabelCompact,
                    color = scheme.textSecondary,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val sensFormatted = "%.1fx".format(sensitivity).let {
                    if (lang == ResolvedLanguage.PT_BR) it.replace('.', ',') else it
                }
                NuvioText(
                    text = sensFormatted,
                    style = NuvioTypography.KeyLabelPrimary,
                    color = scheme.textPrimary,
                )
                NuvioText(
                    text = if (expanded) l10n("Hide", "Recolher") else l10n("Adjust", "Ajustar"),
                    style = NuvioTypography.KeyLabelCompact,
                    color = scheme.textMuted,
                )
                NuvioIcon(
                    imageVector = if (expanded) {
                        Icons.Rounded.KeyboardArrowUp
                    } else {
                        Icons.Rounded.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = scheme.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = NuvioMotion.morphEnter(reducedMotion),
            exit = NuvioMotion.morphExit(reducedMotion),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NuvioGlassSlider(
                    value = sensitivity,
                    onValueChange = onSensitivityChange,
                    valueRange = SENSITIVITY_MIN..SENSITIVITY_MAX,
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
                            .height(34.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(scheme.contentCellSurface)
                            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp)),
                    )
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SPEED_PRESETS.forEach { preset ->
                            val isSelected = abs(sensitivity - preset) < 0.15f
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        if (hapticsEnabled) Haptics.tab(context)
                                        onSensitivityChange(preset)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 3.dp)
                                            .height(28.dp)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom),
                                                ),
                                            ),
                                    )
                                }
                                val presetText = "%.1fx".format(preset).let {
                                    if (lang == ResolvedLanguage.PT_BR) it.replace('.', ',') else it
                                }
                                NuvioText(
                                    text = presetText,
                                    style = NuvioTypography.KeyLabelCompact,
                                    color = if (isSelected) scheme.pillActiveFg else scheme.textSecondary,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.trackpadGestures(
    sensitivity: Float,
    onMove: (Int, Int) -> Unit,
    onTap: () -> Unit,
    onTwoFingerTap: () -> Unit,
    onScroll: (Int) -> Unit,
): Modifier = this
    .pointerInput(onTap) {
        detectTapGestures(
            onTap = { onTap() },
        )
    }
    .pointerInput(sensitivity, onMove, onTwoFingerTap, onScroll) {
        val state = TrackpadMotionAccumulator()
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var maxPointers = 1
            var twoFingerMoved = false
            while (true) {
                val event = awaitPointerEvent()
                val activePointers = event.changes.count { it.pressed }
                if (activePointers > maxPointers) maxPointers = activePointers
                if (activePointers == 0) {
                    if (maxPointers >= 2 && !twoFingerMoved) onTwoFingerTap()
                    break
                }
                val pan = event.calculatePan()
                if (activePointers >= 2) {
                    twoFingerMoved = state.dispatchTwoFinger(pan, twoFingerMoved, onScroll)
                } else if (pan != Offset.Zero) {
                    state.dispatchSingleFinger(pan, sensitivity, onMove)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    }

private class TrackpadMotionAccumulator {
    private var accX = 0f
    private var accY = 0f
    private var scrollAcc = 0f

    fun dispatchTwoFinger(pan: Offset, alreadyMoved: Boolean, onScroll: (Int) -> Unit): Boolean {
        val moved = alreadyMoved || abs(pan.x) > 1f || abs(pan.y) > 1f
        scrollAcc += pan.y * 0.14f
        val step = scrollAcc.toInt()
        if (step != 0) {
            scrollAcc -= step
            onScroll(step)
        }
        return moved
    }

    fun dispatchSingleFinger(pan: Offset, sensitivity: Float, onMove: (Int, Int) -> Unit) {
        accX += pan.x * sensitivity
        accY += pan.y * sensitivity
        val dx = accX.roundToInt()
        val dy = accY.roundToInt()
        if (dx != 0 || dy != 0) {
            accX -= dx
            accY -= dy
            onMove(dx, dy)
        }
    }
}
