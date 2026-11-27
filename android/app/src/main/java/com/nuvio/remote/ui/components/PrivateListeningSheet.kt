package com.nuvio.remote.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.remote.connection.PrivateListeningState
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass

/**
 * Floating Liquid Glass sheet for Private Listening (PC audio without video, screen-off capable).
 * Exclusive to PC Mode (DESIGN-SPEC §5.14.1). Outer shell uses GlassVariant.REGULAR; inner cards are 100% opaque.
 */
@Composable
fun PrivateListeningSheetDialog(
    state: PrivateListeningState,
    pcName: String,
    pingMs: Int,
    onMutePc: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .nuvioGlass(shape = RoundedCornerShape(24.dp), variant = GlassVariant.REGULAR, elevated = true)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrivateListeningHeader(onDismiss = onDismiss)

            PrivateListeningStatusRow(state = state, pcName = pcName, pingMs = pingMs)

            // 100% opaque content card (zero glass inside)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.contentCardSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(scheme.contentCellSurface)
                            .border(1.dp, scheme.statusOnline.copy(alpha = 0.4f), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioIcon(
                            imageVector = Icons.Rounded.Headphones,
                            contentDescription = null,
                            tint = scheme.statusOnline,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        NuvioText(
                            text = l10n("Headphones / Speaker • 24 kHz Stereo", "Fone / Alto-falante • 24 kHz Estéreo"),
                            style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.Bold),
                            color = scheme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        NuvioText(
                            text = l10n("PC audio only • Video capture off", "Somente áudio do PC • Vídeo desligado"),
                            style = NuvioTypography.KeyLabelCompact,
                            color = scheme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StaticAudioBars(active = state == PrivateListeningState.ACTIVE)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(scheme.contentCellSurface)
                        .border(1.dp, scheme.contentBorder, RoundedCornerShape(11.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NuvioIcon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = scheme.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    NuvioText(
                        text = l10n("Keeps playing with screen off", "Continua tocando com a tela apagada"),
                        style = NuvioTypography.KeyLabelCompact.copy(fontWeight = FontWeight.Medium),
                        color = scheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrivateAudioControlChip(
                        label = l10n("Mute PC", "Mudo PC"),
                        icon = Icons.AutoMirrored.Rounded.VolumeOff,
                        onClick = {
                            if (hapticsEnabled) Haptics.action(context)
                            onMutePc()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PrivateAudioControlChip(
                        label = "Vol −",
                        icon = Icons.Rounded.Remove,
                        onClick = {
                            if (hapticsEnabled) Haptics.action(context)
                            onVolumeDown()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PrivateAudioControlChip(
                        label = "Vol +",
                        icon = Icons.Rounded.Add,
                        onClick = {
                            if (hapticsEnabled) Haptics.action(context)
                            onVolumeUp()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.statusError.copy(alpha = 0.16f))
                    .border(1.dp, scheme.statusError.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                    .clickable {
                        if (hapticsEnabled) Haptics.action(context)
                        onStop()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                NuvioText(
                    text = l10n("Stop Private Listening", "Encerrar Escuta Privada"),
                    style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.Bold),
                    color = scheme.statusError,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Compact opaque status banner displayed at the top of PC Mode tabs (Trackpad / Screen)
 * while Private Listening is active. Never rendered in Nuvio Mode (Remote / Apps).
 */
@Composable
fun PrivateListeningBanner(
    state: PrivateListeningState,
    pcName: String,
    pingMs: Int,
    onOpenSheet: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == PrivateListeningState.IDLE) return
    val scheme = LocalNuvioColors.current
    val statusText = when (state) {
        PrivateListeningState.CONNECTING -> l10n("Connecting Private Audio…", "Conectando Escuta Privada…")
        PrivateListeningState.ACTIVE -> if (pingMs > 0) {
            l10n("Private Audio • $pcName • $pingMs ms", "Escuta Privada • $pcName • $pingMs ms")
        } else {
            l10n("Private Audio • $pcName", "Escuta Privada • $pcName")
        }
        PrivateListeningState.SILENT -> l10n("Private Audio • No signal", "Escuta Privada • Sem áudio")
        PrivateListeningState.IDLE -> ""
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.statusOnline.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenSheet)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NuvioIcon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = null,
            tint = scheme.statusOnline,
            modifier = Modifier.size(18.dp),
        )
        NuvioText(
            text = statusText,
            style = NuvioTypography.SectionMicroTitle.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.statusError.copy(alpha = 0.14f))
                    .border(1.dp, scheme.statusError.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                NuvioText(
                    text = l10n("Stop", "Parar"),
                    style = NuvioTypography.SectionMicroTitle.copy(fontWeight = FontWeight.Bold),
                    color = scheme.statusError,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PrivateListeningHeader(onDismiss: () -> Unit) {
    val scheme = LocalNuvioColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NuvioText(
                text = l10n("PC MODE • AUDIO ONLY", "MODO PC • ÁUDIO SEM VÍDEO"),
                style = NuvioTypography.SectionMicroTitle.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
                color = scheme.ctxPlayer,
                maxLines = 1,
            )
            NuvioText(
                text = l10n("Private Listening", "Escuta Privada"),
                style = NuvioTypography.DisplayKey.copy(fontWeight = FontWeight.Bold),
                color = scheme.textPrimary,
                maxLines = 1,
            )
        }
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
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NuvioIcon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = l10n("Close", "Fechar"),
                    tint = scheme.textPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun PrivateListeningStatusRow(state: PrivateListeningState, pcName: String, pingMs: Int) {
    val scheme = LocalNuvioColors.current
    val (label, color) = when (state) {
        PrivateListeningState.CONNECTING -> l10n("Connecting audio…", "Conectando áudio…") to scheme.statusWarning
        PrivateListeningState.ACTIVE -> {
            val suffix = if (pingMs > 0) " • $pingMs ms" else ""
            l10n("Active • $pcName$suffix", "Ativa • $pcName$suffix") to scheme.statusOnline
        }
        PrivateListeningState.SILENT -> l10n("No audio signal", "Sem áudio") to scheme.textSecondary
        PrivateListeningState.IDLE -> l10n("Inactive", "Inativa") to scheme.textSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        NuvioText(
            text = label,
            style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StaticAudioBars(active: Boolean) {
    val scheme = LocalNuvioColors.current
    val barColor: Color = if (active) scheme.statusOnline else scheme.textMuted
    val heights = listOf(8.dp, 16.dp, 11.dp, 18.dp, 10.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        heights.forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun PrivateAudioControlChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, shape)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioIcon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.textPrimary,
                modifier = Modifier.size(14.dp),
            )
            NuvioText(
                text = label,
                style = NuvioTypography.SectionMicroTitle.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
