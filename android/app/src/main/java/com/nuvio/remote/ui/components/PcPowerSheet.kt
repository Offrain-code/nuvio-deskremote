package com.nuvio.remote.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass

/**
 * PC Mode exclusive Power Control sheet in Liquid Glass (DESIGN-SPEC §5.13 Fase 1A):
 * GlassVariant.REGULAR outer shell, 2x2 opaque ContentCellSurface grid, instant Lock/Display-off,
 * and explicit 2nd-tap confirmation bar for Sleep, Restart, and Shut down.
 */
@Composable
fun PcPowerDialog(onAction: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val reducedMotion = LocalReducedMotion.current
    val scheme = LocalNuvioColors.current
    var armedOption by remember { mutableStateOf<PcPowerOption?>(null) }

    val lockOpt = PcPowerOption(
        action = "lock_pc",
        label = l10n("Lock", "Bloquear"),
        badge = l10n("1 tap", "1 toque"),
        subtitle = l10n("Win+L • Instant", "Win+L • Imediato"),
        confirmPrompt = "",
        confirmButton = "",
        icon = Icons.Rounded.Lock,
        requiresConfirm = false,
        danger = false,
    )
    val sleepOpt = PcPowerOption(
        action = "sleep_pc",
        label = l10n("Sleep", "Suspender"),
        badge = l10n("2nd tap", "2º toque"),
        subtitle = l10n("Confirm below", "Exige confirmação"),
        confirmPrompt = l10n("Put Windows to sleep?", "Suspender o Windows?"),
        confirmButton = l10n("Sleep now", "Suspender agora"),
        icon = Icons.Rounded.Bedtime,
        requiresConfirm = true,
        danger = false,
    )
    val restartOpt = PcPowerOption(
        action = "restart_pc",
        label = l10n("Restart", "Reiniciar"),
        badge = l10n("2nd tap", "2º toque"),
        subtitle = l10n("Reboots Windows", "Reinicia o PC"),
        confirmPrompt = l10n("Confirm Windows restart?", "Confirmar reinício do Windows?"),
        confirmButton = l10n("Restart now", "Reiniciar agora"),
        icon = Icons.Rounded.RestartAlt,
        requiresConfirm = true,
        danger = true,
    )
    val shutdownOpt = PcPowerOption(
        action = "shutdown_pc",
        label = l10n("Shut down", "Desligar"),
        badge = l10n("2nd tap", "2º toque"),
        subtitle = l10n("Powers off PC", "Desliga o PC"),
        confirmPrompt = l10n("Confirm Windows shut down?", "Confirmar desligamento do Windows?"),
        confirmButton = l10n("Shut down now", "Desligar agora"),
        icon = Icons.Rounded.PowerSettingsNew,
        requiresConfirm = true,
        danger = true,
    )

    fun handleOptionTap(option: PcPowerOption) {
        if (!option.requiresConfirm) {
            if (hapticsEnabled) Haptics.ok(context)
            onAction(option.action)
            onDismiss()
        } else {
            if (hapticsEnabled) Haptics.toggle(context)
            armedOption = if (armedOption?.action == option.action) null else option
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .nuvioGlass(
                    shape = RoundedCornerShape(24.dp),
                    variant = GlassVariant.REGULAR,
                    elevated = true,
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PcPowerHeader(onDismiss = onDismiss)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PcPowerGridCell(
                    option = lockOpt,
                    armed = false,
                    onClick = { handleOptionTap(lockOpt) },
                    modifier = Modifier.weight(1f),
                )
                PcPowerGridCell(
                    option = sleepOpt,
                    armed = armedOption?.action == sleepOpt.action,
                    onClick = { handleOptionTap(sleepOpt) },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PcPowerGridCell(
                    option = restartOpt,
                    armed = armedOption?.action == restartOpt.action,
                    onClick = { handleOptionTap(restartOpt) },
                    modifier = Modifier.weight(1f),
                )
                PcPowerGridCell(
                    option = shutdownOpt,
                    armed = armedOption?.action == shutdownOpt.action,
                    onClick = { handleOptionTap(shutdownOpt) },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                    .semantics { role = Role.Button }
                    .clickable {
                        if (hapticsEnabled) Haptics.action(context)
                        onAction("suspend_display")
                        onDismiss()
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.MonitorHeart,
                    contentDescription = null,
                    tint = scheme.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
                NuvioText(
                    text = l10n("Turn off monitor only", "Apagar apenas o monitor"),
                    style = NuvioTypography.KeyLabelCompact,
                    color = scheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                NuvioText(
                    text = l10n("1 tap", "1 toque"),
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.textMuted,
                )
            }

            AnimatedVisibility(
                visible = armedOption != null,
                enter = NuvioMotion.morphEnter(reducedMotion),
                exit = NuvioMotion.morphExit(reducedMotion),
            ) {
                armedOption?.let { active ->
                    PcPowerConfirmBar(
                        option = active,
                        onCancel = { armedOption = null },
                        onConfirm = {
                            if (hapticsEnabled) Haptics.ok(context)
                            onAction(active.action)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

private data class PcPowerOption(
    val action: String,
    val label: String,
    val badge: String,
    val subtitle: String,
    val confirmPrompt: String,
    val confirmButton: String,
    val icon: ImageVector,
    val requiresConfirm: Boolean,
    val danger: Boolean,
)

@Composable
private fun PcPowerHeader(onDismiss: () -> Unit) {
    val scheme = LocalNuvioColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            NuvioText(
                text = l10n("PC POWER", "ENERGIA DO PC"),
                style = NuvioTypography.SectionMicroTitle,
                color = scheme.textPrimary,
            )
            NuvioText(
                text = l10n(
                    "Destructive actions require a 2nd confirmation tap",
                    "Ações destrutivas exigem 2º toque de confirmação",
                ),
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.textMuted,
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics { role = Role.Button }
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = l10n("Close", "Fechar"),
                    tint = scheme.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun PcPowerGridCell(
    option: PcPowerOption,
    armed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val accentColor: Color = when {
        armed && option.danger -> scheme.statusError
        armed -> scheme.statusWarning
        option.danger -> scheme.statusError
        else -> scheme.textPrimary
    }
    val borderColor = if (armed) accentColor.copy(alpha = 0.80f) else scheme.contentBorder
    val bgColor = if (armed) accentColor.copy(alpha = 0.14f) else scheme.contentCellSurface

    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 64.dp)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                NuvioIcon(
                    imageVector = option.icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp),
                )
                NuvioText(
                    text = option.label,
                    style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.SemiBold),
                    color = if (armed) accentColor else scheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        NuvioText(
            text = if (armed) l10n("Confirm below", "Confirme abaixo") else option.subtitle,
            style = NuvioTypography.KeyShortcutBadge,
            color = if (armed) accentColor else scheme.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PcPowerConfirmBar(option: PcPowerOption, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val accent = if (option.danger) scheme.statusError else scheme.statusWarning

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NuvioText(
            text = option.confirmPrompt,
            style = NuvioTypography.KeyLabelCompact.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(12.dp))
                    .semantics { role = Role.Button }
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                NuvioText(
                    text = l10n("Cancel", "Cancelar"),
                    style = NuvioTypography.KeyLabelCompact,
                    color = scheme.textPrimary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1.25f)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.22f))
                    .border(1.dp, accent, RoundedCornerShape(12.dp))
                    .semantics { role = Role.Button }
                    .clickable(onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) {
                NuvioText(
                    text = option.confirmButton,
                    style = NuvioTypography.KeyLabelCompact.copy(fontWeight = FontWeight.Bold),
                    color = accent,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
