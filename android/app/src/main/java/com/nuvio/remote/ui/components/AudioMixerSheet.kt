package com.nuvio.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.remote.connection.AudioAppSession
import com.nuvio.remote.connection.AudioMixerState
import com.nuvio.remote.connection.AudioOutputDevice
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass
import kotlin.math.roundToInt

/**
 * Floating Liquid Glass sheet for Windows per-app Audio Mixer & output switcher (DESIGN-SPEC §5.15.1).
 * Exclusive to PC Mode. Outer container uses GlassVariant.REGULAR; all device chips and app rows are 100% opaque.
 */
@Composable
fun AudioMixerSheetDialog(
    state: AudioMixerState,
    onRefresh: () -> Unit,
    onSelectOutput: (String) -> Unit,
    onSetAppVolume: (pid: Int, volume: Int?, muted: Boolean?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current

    LaunchedEffect(Unit) { onRefresh() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        SwipeToDismissSheetContainer(
            onDismiss = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .nuvioGlass(shape = RoundedCornerShape(24.dp), variant = GlassVariant.REGULAR, elevated = true)
                .padding(14.dp),
        ) {
            AudioMixerHeader(onRefresh = onRefresh, onDismiss = onDismiss)

            if (state.outputs.isNotEmpty()) {
                AudioOutputsCard(
                    outputs = state.outputs,
                    onSelectOutput = { id ->
                        if (hapticsEnabled) Haptics.action(context)
                        onSelectOutput(id)
                    },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .heightIn(max = 310.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.sessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(scheme.contentCardSurface)
                            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                            .padding(14.dp),
                    ) {
                        NuvioText(
                            text = l10n(
                                "No active audio apps detected on PC. Tap refresh while audio is playing.",
                                "Nenhum aplicativo com áudio ativo no PC. Toque em atualizar enquanto algo toca.",
                            ),
                            style = NuvioTypography.KeyLabelCompact,
                            color = scheme.textSecondary,
                        )
                    }
                } else {
                    state.sessions.forEach { session ->
                        AudioSessionRow(
                            session = session,
                            onToggleMute = {
                                if (hapticsEnabled) Haptics.toggle(context)
                                onSetAppVolume(session.pid, null, !session.muted)
                            },
                            onVolumeChangeFinished = { pct ->
                                onSetAppVolume(session.pid, pct, null)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioMixerHeader(onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val scheme = LocalNuvioColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NuvioText(
                text = l10n("PC MODE • WINDOWS AUDIO", "MODO PC • ÁUDIO DO WINDOWS"),
                style = NuvioTypography.SectionMicroTitle.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
                color = scheme.ctxPlayer,
                maxLines = 1,
            )
            NuvioText(
                text = l10n("Audio Mixer", "Mixer de Áudio"),
                style = NuvioTypography.DisplayKey.copy(fontWeight = FontWeight.Bold),
                color = scheme.textPrimary,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AudioHeaderCircleButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = l10n("Refresh", "Atualizar"),
                onClick = onRefresh,
            )
            AudioHeaderCircleButton(
                icon = Icons.Rounded.Close,
                contentDescription = l10n("Close", "Fechar"),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun AudioHeaderCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
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
                imageVector = icon,
                contentDescription = contentDescription,
                tint = scheme.textPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AudioOutputsCard(outputs: List<AudioOutputDevice>, onSelectOutput: (String) -> Unit) {
    val scheme = LocalNuvioColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NuvioText(
            text = l10n("OUTPUT DEVICE", "SAÍDA DE ÁUDIO"),
            style = NuvioTypography.SectionMicroTitle.copy(fontSize = 10.sp),
            color = scheme.textSecondary,
            maxLines = 1,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            outputs.forEach { device ->
                val shape = RoundedCornerShape(12.dp)
                val borderColor = if (device.isDefault) scheme.statusOnline.copy(alpha = 0.7f) else scheme.contentBorder
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable { onSelectOutput(device.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(shape)
                            .background(
                                if (device.isDefault) {
                                    scheme.statusOnline.copy(alpha = 0.14f)
                                } else {
                                    scheme.contentCellSurface
                                },
                            )
                            .border(1.dp, borderColor, shape)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        NuvioText(
                            text = if (device.isDefault) "• ${device.name}" else device.name,
                            style = NuvioTypography.KeyLabelCompact.copy(fontWeight = FontWeight.SemiBold),
                            color = if (device.isDefault) scheme.statusOnline else scheme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioSessionRow(
    session: AudioAppSession,
    onToggleMute: () -> Unit,
    onVolumeChangeFinished: (Int) -> Unit,
) {
    val scheme = LocalNuvioColors.current
    var sliderVal by remember(session.pid, session.volume) { mutableFloatStateOf(session.volume.toFloat()) }
    val shape = RoundedCornerShape(14.dp)
    val border = if (session.muted) scheme.statusWarning.copy(alpha = 0.6f) else scheme.contentBorder

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.contentCellSurface)
            .border(1.dp, border, shape)
            .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onToggleMute),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.contentCardSurface)
                    .border(1.dp, border, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                NuvioIcon(
                    imageVector = if (session.muted) {
                        Icons.AutoMirrored.Rounded.VolumeOff
                    } else {
                        Icons.AutoMirrored.Rounded.VolumeUp
                    },
                    contentDescription = l10n("Mute ${session.name}", "Silenciar ${session.name}"),
                    tint = if (session.muted) scheme.statusWarning else scheme.textPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioText(
                    text = session.name,
                    style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.Bold),
                    color = scheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                NuvioText(
                    text = if (session.muted) {
                        l10n("Muted", "Mudo")
                    } else {
                        "${sliderVal.roundToInt()}%"
                    },
                    style = NuvioTypography.KeyLabelCompact.copy(fontWeight = FontWeight.SemiBold),
                    color = if (session.muted) scheme.statusWarning else scheme.ctxPlayer,
                    maxLines = 1,
                )
            }
            NuvioGlassSlider(
                value = sliderVal,
                valueRange = 0f..100f,
                onValueChange = { sliderVal = it },
                onValueChangeFinished = { onVolumeChangeFinished(sliderVal.roundToInt().coerceIn(0, 100)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
