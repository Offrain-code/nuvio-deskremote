package com.nuvio.remote.ui.components

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.ClearContentShadow
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass

/**
 * Floating real-time remote keyboard bar in Liquid Glass (zero Material 3, >= 48dp targets).
 * Streams each typed character directly to the PC as the user types on the phone keyboard.
 */
@Composable
fun RemoteTextInputBar(
    visible: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSendKey: (String, Int) -> Unit = { _, _ -> },
    focusTick: Long = 0L,
    placeholder: String? = null,
    showSpecialKeys: Boolean = true,
    glassVariant: GlassVariant = GlassVariant.REGULAR,
    captureStageBackdrop: Boolean = glassVariant != GlassVariant.CLEAR,
    isBackdropLight: Boolean = false,
) {
    val resolvedPlaceholder = placeholder ?: l10n("Type on PC in real time…", "Digitar no PC em tempo real…")
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val reducedMotion = LocalReducedMotion.current
    val scheme = LocalNuvioColors.current
    val isClear = glassVariant == GlassVariant.CLEAR
    val focusRequester = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }

    LaunchedEffect(visible, focusTick) {
        if (visible) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = NuvioMotion.morphEnter(reducedMotion),
        exit = NuvioMotion.morphExit(reducedMotion),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .nuvioGlass(
                    shape = RoundedCornerShape(20.dp),
                    variant = glassVariant,
                    elevated = !isClear,
                    isBackdropLight = isBackdropLight,
                    captureStageBackdrop = captureStageBackdrop,
                )
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (showSpecialKeys) {
                SpecialKeysSection(
                    isClear = isClear,
                    onSpecialKey = { keyName ->
                        if (hapticsEnabled) Haptics.action(context)
                        onSendKey(keyName, 1)
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InputFieldBox(
                    text = text,
                    placeholder = resolvedPlaceholder,
                    focusRequester = focusRequester,
                    isClear = isClear,
                    onValueChange = { next ->
                        syncTextDelta(
                            previous = text,
                            next = next,
                            onSendText = onSendText,
                            onSendKey = onSendKey,
                        )
                        text = next
                    },
                    onImeSend = {
                        if (hapticsEnabled) Haptics.ok(context)
                        onSendKey("Enter", 1)
                        text = ""
                    },
                    modifier = Modifier.weight(1f),
                )
                BarIconButton(
                    icon = Icons.AutoMirrored.Rounded.Backspace,
                    description = l10n("Backspace on PC", "Apagar no PC"),
                    isClear = isClear,
                    onClick = {
                        if (hapticsEnabled) Haptics.action(context)
                        text = ""
                        onSendKey("Backspace", 1)
                    },
                )
                BarSendButton(
                    color = if (isClear) scheme.clearPillActiveBgTop else scheme.pillActiveBgTop,
                    fgColor = if (isClear) scheme.clearPillActiveFg else scheme.pillActiveFg,
                    onClick = {
                        if (hapticsEnabled) Haptics.ok(context)
                        onSendKey("Enter", 1)
                        text = ""
                    },
                )
                BarIconButton(
                    icon = Icons.Rounded.Close,
                    description = l10n("Close keyboard", "Fechar teclado"),
                    isClear = isClear,
                    onClick = {
                        keyboard?.hide()
                        onVisibilityChange(false)
                    },
                )
            }
        }
    }
}

private fun syncTextDelta(
    previous: String,
    next: String,
    onSendText: (String) -> Unit,
    onSendKey: (String, Int) -> Unit,
) {
    when {
        next.length > previous.length && next.startsWith(previous) -> {
            onSendText(next.substring(previous.length))
        }
        next.length < previous.length && previous.startsWith(next) -> {
            onSendKey("Backspace", previous.length - next.length)
        }
        next != previous -> {
            if (previous.isNotEmpty()) onSendKey("Backspace", previous.length)
            if (next.isNotEmpty()) onSendText(next)
        }
    }
}

private val SPECIAL_KEYS_SCROLL_THRESHOLD = 352.dp

@Composable
private fun SpecialKeysSection(isClear: Boolean, onSpecialKey: (String) -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    var showExtraKeys by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val useHorizontalScroll = maxWidth < SPECIAL_KEYS_SCROLL_THRESHOLD
            val rowModifier = if (useHorizontalScroll) {
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            }
            Row(
                modifier = rowModifier,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val keyMod = if (useHorizontalScroll) Modifier else Modifier.weight(1f)
                SpecialKeyPill("ESC", keyMod, isClear) { onSpecialKey("Escape") }
                SpecialKeyPill("TAB", keyMod, isClear) { onSpecialKey("Tab") }
                SpecialKeyPill("ALT+TAB", keyMod, isClear) { onSpecialKey("AltTab") }
                SpecialKeyPill("WIN", keyMod, isClear) { onSpecialKey("LWin") }
                SpecialKeyPill("CTRL+C", keyMod, isClear) { onSpecialKey("CtrlC") }
                SpecialKeyPill("CTRL+V", keyMod, isClear) { onSpecialKey("CtrlV") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SpecialKeyPill("Del", Modifier.weight(1f), isClear) { onSpecialKey("Delete") }
            SpecialKeyPill("▲", Modifier.weight(0.85f), isClear) { onSpecialKey("Up") }
            SpecialKeyPill("▼", Modifier.weight(0.85f), isClear) { onSpecialKey("Down") }
            SpecialKeyPill(l10n("Space", "Espaço"), Modifier.weight(1.5f), isClear) { onSpecialKey("Space") }
            SpecialKeyPill("Enter", Modifier.weight(1.1f), isClear) { onSpecialKey("Enter") }
            SpecialKeyPill(
                label = if (showExtraKeys) l10n("Less", "Menos") else l10n("More", "Mais"),
                modifier = Modifier.weight(1f),
                isClear = isClear,
                highlighted = showExtraKeys,
            ) {
                showExtraKeys = !showExtraKeys
            }
        }
        AnimatedVisibility(
            visible = showExtraKeys,
            enter = NuvioMotion.morphEnter(reducedMotion),
            exit = NuvioMotion.morphExit(reducedMotion),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SpecialKeyPill("Win+D", Modifier.weight(1f), isClear) { onSpecialKey("WinD") }
                    SpecialKeyPill("Win+Tab", Modifier.weight(1f), isClear) { onSpecialKey("WinTab") }
                    SpecialKeyPill("Ctrl+Z", Modifier.weight(1f), isClear) { onSpecialKey("CtrlZ") }
                    SpecialKeyPill("Ctrl+W", Modifier.weight(1f), isClear) { onSpecialKey("CtrlW") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SpecialKeyPill("F5", Modifier.weight(0.85f), isClear) { onSpecialKey("F5") }
                    SpecialKeyPill("Ctrl+Shift+Esc", Modifier.weight(1.45f), isClear) { onSpecialKey("CtrlShiftEsc") }
                    SpecialKeyPill(
                        label = "Alt+F4",
                        modifier = Modifier.weight(0.90f),
                        isClear = isClear,
                        danger = true,
                    ) {
                        onSpecialKey("AltF4")
                    }
                }
            }
        }
    }
}

private data class SpecialKeyVisuals(
    val bgColor: Color,
    val borderColor: Color,
    val fgColor: Color,
    val shadow: Shadow?,
)

private fun resolveSpecialKeyVisuals(
    scheme: com.nuvio.remote.ui.theme.NuvioColorScheme,
    isClear: Boolean,
    highlighted: Boolean,
    danger: Boolean,
    pressed: Boolean,
): SpecialKeyVisuals {
    if (highlighted) {
        return SpecialKeyVisuals(
            bgColor = if (isClear) scheme.clearPillActiveBgTop else scheme.pillActiveBgTop,
            borderColor = Color.Transparent,
            fgColor = if (isClear) scheme.clearPillActiveFg else scheme.pillActiveFg,
            shadow = null,
        )
    }
    if (danger) {
        return SpecialKeyVisuals(
            bgColor = scheme.statusError.copy(alpha = if (pressed) 0.24f else 0.14f),
            borderColor = scheme.statusError.copy(alpha = 0.65f),
            fgColor = scheme.statusError,
            shadow = null,
        )
    }
    if (isClear) {
        return SpecialKeyVisuals(
            bgColor = if (pressed) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.28f),
            borderColor = Color.White.copy(alpha = 0.22f),
            fgColor = scheme.clearOverlayText,
            shadow = ClearContentShadow,
        )
    }
    return SpecialKeyVisuals(
        bgColor = if (pressed) scheme.contentCellPressed else scheme.contentCellSurface,
        borderColor = scheme.contentBorder,
        fgColor = scheme.textPrimary,
        shadow = null,
    )
}

@Composable
private fun SpecialKeyPill(
    label: String,
    modifier: Modifier = Modifier,
    isClear: Boolean = false,
    highlighted: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.94f else 1f,
        animationSpec = NuvioMotion.snappyPressSpring(reducedMotion),
        label = "special_key_scale",
    )
    val visuals = resolveSpecialKeyVisuals(scheme, isClear, highlighted, danger, pressed)

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(10.dp))
                .background(visuals.bgColor)
                .border(1.dp, visuals.borderColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            NuvioText(
                text = label,
                style = NuvioTypography.KeyShortcutBadge.copy(fontSize = 10.sp),
                color = visuals.fgColor,
                shadow = visuals.shadow,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InputFieldBox(
    text: String,
    placeholder: String,
    focusRequester: FocusRequester,
    isClear: Boolean,
    onValueChange: (String) -> Unit,
    onImeSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val fgColor = if (isClear) scheme.clearOverlayText else scheme.textPrimary
    val bgColor = if (isClear) Color.Black.copy(alpha = 0.30f) else scheme.contentCellSurface
    val borderColor = if (isClear) Color.White.copy(alpha = 0.24f) else scheme.contentBorder
    val hintColor = if (isClear) Color.White.copy(alpha = 0.72f) else scheme.textMuted

    Box(
        modifier = modifier
            .height(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                runCatching { focusRequester.requestFocus() }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = text,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onImeSend() }),
            textStyle = NuvioTypography.BodyRegular.copy(color = fgColor),
            cursorBrush = SolidColor(fgColor),
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .focusRequester(focusRequester)
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        NuvioText(
                            text = placeholder,
                            style = NuvioTypography.KeyShortcutBadge,
                            color = hintColor,
                            shadow = if (isClear) ClearContentShadow else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun BarIconButton(icon: ImageVector, description: String, isClear: Boolean, onClick: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val bgColor = if (isClear) Color.Black.copy(alpha = 0.28f) else scheme.contentCellSurface
    val borderColor = if (isClear) Color.White.copy(alpha = 0.22f) else scheme.contentBorder
    val fgColor = if (isClear) scheme.clearOverlayText else scheme.textPrimary

    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = description,
                tint = fgColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun BarSendButton(color: Color, fgColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(1.dp))
                NuvioIcon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = l10n("Send Enter", "Enviar Enter"),
                    tint = fgColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
