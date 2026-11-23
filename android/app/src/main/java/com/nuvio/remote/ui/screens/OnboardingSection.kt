package com.nuvio.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.discovery.PcService
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.accentForScreen

/**
 * First-run onboarding flow (DESIGN-SPEC.md section 5.7):
 * shown when no PC is saved or paired — no hardcoded IP, automatic UDP LAN discovery,
 * primary QR code pairing button, and discreet manual IP fallback. All cards are opaque.
 */
@Composable
fun OnboardingConnectionSection(
    discovered: PcService?,
    hostInput: String,
    qrStatusMessage: String?,
    onHostInputChange: (String) -> Unit,
    onUseDiscovered: (PcService) -> Unit,
    onRefreshDiscovery: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onUseManualHost: () -> Unit,
    onOpenPinDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OnboardingIntroCard(qrStatusMessage = qrStatusMessage)
        OnboardingDiscoveryCard(
            discovered = discovered,
            onUseDiscovered = onUseDiscovered,
            onRefreshDiscovery = onRefreshDiscovery,
        )
        OnboardingQrHighlightCard(onOpenQrScanner = onOpenQrScanner)
        OnboardingManualFallbackCard(
            hostInput = hostInput,
            onHostInputChange = onHostInputChange,
            onUseManualHost = onUseManualHost,
            onOpenPinDialog = onOpenPinDialog,
        )
    }
}

@Composable
private fun OnboardingIntroCard(qrStatusMessage: String?) {
    val scheme = LocalNuvioColors.current
    val accent = accentForScreen(NuvioScreenState.HOME)
    OnboardingCardContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioText(
                text = l10n("FIRST RUN • NUVIO DESKREMOTE", "PRIMEIRO ACESSO • NUVIO DESKREMOTE"),
                style = NuvioTypography.SectionMicroTitle,
                color = accent.primary,
            )
            OnboardingBadge(text = l10n("No hardcoded IP", "Sem IP fixo"))
        }
        NuvioText(
            text = l10n("Connect to your PC", "Conecte ao seu Computador"),
            style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.textPrimary,
        )
        NuvioText(
            text = l10n(
                "Control Nuvio HTPC, mouse/trackpad, and PC screen share over your local Wi-Fi network.",
                "Controle o Nuvio HTPC, mouse/trackpad e tela do PC pela sua rede Wi-Fi local.",
            ),
            style = NuvioTypography.KeyShortcutBadge,
            color = scheme.textSecondary,
        )
        if (!qrStatusMessage.isNullOrBlank()) {
            NuvioText(
                text = qrStatusMessage,
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.statusWarning,
            )
        }
    }
}

@Composable
private fun OnboardingDiscoveryCard(
    discovered: PcService?,
    onUseDiscovered: (PcService) -> Unit,
    onRefreshDiscovery: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    OnboardingCardContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioText(
                text = l10n("1. AUTOMATIC WI-FI DISCOVERY", "1. DESCOBERTA AUTOMÁTICA NO WI-FI"),
                style = NuvioTypography.SectionMicroTitle,
                color = scheme.textMuted,
            )
            OnboardingBadge(
                text = if (discovered != null) {
                    l10n("1 PC found", "1 PC na rede")
                } else {
                    l10n("Scanning…", "Buscando…")
                },
            )
        }

        if (discovered != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.statusOnline.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioIcon(
                        imageVector = Icons.Rounded.DesktopWindows,
                        contentDescription = l10n("Discovered computer", "Computador encontrado"),
                        tint = scheme.statusOnline,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        NuvioText(
                            text = discovered.hostname,
                            style = NuvioTypography.KeyLabelCompact.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        NuvioText(
                            text = "${discovered.ip} • ${l10n("Ready", "Pronto")}",
                            style = NuvioTypography.KeyShortcutBadge,
                            color = scheme.statusOnline,
                            maxLines = 1,
                        )
                    }
                }
                OnboardingActionButton(
                    label = l10n("Connect", "Conectar"),
                    highlighted = true,
                    onClick = { onUseDiscovered(discovered) },
                )
            }
        } else {
            NuvioText(
                text = l10n(
                    "Open Nuvio DeskRemote Companion on the PC connected to the same Wi-Fi.",
                    "Abra o Nuvio DeskRemote Companion no PC conectado ao mesmo Wi-Fi.",
                ),
                style = NuvioTypography.KeyShortcutBadge,
                color = scheme.textSecondary,
            )
        }

        OnboardingActionButton(
            label = l10n("Scan Wi-Fi for PCs again", "Buscar computadores novamente no Wi-Fi"),
            highlighted = false,
            icon = Icons.Rounded.Refresh,
            onClick = onRefreshDiscovery,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OnboardingQrHighlightCard(onOpenQrScanner: () -> Unit) {
    val scheme = LocalNuvioColors.current
    OnboardingCardContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioText(
                text = l10n("2. INSTANT CAMERA PAIRING", "2. PAREAMENTO INSTANTÂNEO POR CÂMERA"),
                style = NuvioTypography.SectionMicroTitle,
                color = scheme.textMuted,
            )
            OnboardingBadge(text = l10n("Recommended", "Recomendado"))
        }
        NuvioText(
            text = l10n(
                "Point the camera at the QR Code shown in the Nuvio DeskRemote Companion window on your PC:",
                "Aponte a câmera para o QR Code exibido na janela do Nuvio DeskRemote Companion no PC:",
            ),
            style = NuvioTypography.KeyShortcutBadge,
            color = scheme.textSecondary,
        )
        OnboardingActionButton(
            label = l10n("Scan PC QR Code", "Escanear QR Code do PC"),
            highlighted = true,
            icon = Icons.Rounded.QrCodeScanner,
            onClick = onOpenQrScanner,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OnboardingManualFallbackCard(
    hostInput: String,
    onHostInputChange: (String) -> Unit,
    onUseManualHost: () -> Unit,
    onOpenPinDialog: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    OnboardingCardContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioText(
                text = l10n("3. ENTER IP ADDRESS MANUALLY", "3. DIGITAR ENDEREÇO IP MANUALMENTE"),
                style = NuvioTypography.SectionMicroTitle,
                color = scheme.textMuted,
            )
            OnboardingBadge(text = "Fallback")
        }
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
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (hostInput.isEmpty()) {
                        NuvioText(
                            text = l10n("e.g. 192.168.0.50", "Ex.: 192.168.0.50"),
                            style = NuvioTypography.KeyLabelCompact,
                            color = scheme.textMuted,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = hostInput,
                        onValueChange = onHostInputChange,
                        singleLine = true,
                        textStyle = NuvioTypography.KeyLabelCompact.copy(color = scheme.textPrimary),
                        cursorBrush = SolidColor(scheme.textPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            OnboardingActionButton(
                label = l10n("Use IP", "Usar IP"),
                highlighted = false,
                onClick = onUseManualHost,
            )
        }
        OnboardingActionButton(
            label = l10n("Enter 6-digit PIN code", "Digitar código PIN (6 dígitos)"),
            highlighted = false,
            onClick = onOpenPinDialog,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OnboardingCardContainer(content: @Composable () -> Unit) {
    val scheme = LocalNuvioColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
private fun OnboardingBadge(text: String) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        NuvioText(
            text = text,
            style = NuvioTypography.KeyShortcutBadge,
            color = scheme.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun OnboardingActionButton(
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val fg = if (highlighted) scheme.pillActiveFg else scheme.textPrimary
    val bgModifier = if (highlighted) {
        Modifier.background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
    } else {
        Modifier
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
    }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(interactionSource = interactionSource, indication = null) {
                if (hapticsEnabled) Haptics.action(context)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .then(if (modifier == Modifier) Modifier else Modifier.fillMaxWidth())
                .height(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .then(bgModifier)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                NuvioIcon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = fg,
                    modifier = Modifier.size(18.dp),
                )
            }
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
