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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.remote.connection.ConnState
import com.nuvio.remote.connection.NuvioWakeInfo
import com.nuvio.remote.connection.NuvioWakeOnLan
import com.nuvio.remote.pairing.SavedPcProfile
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass
import kotlinx.coroutines.launch

/**
 * Floating Liquid Glass sheet ("My Computers") opened from the top connection pill (DESIGN-SPEC §5.14.3).
 * Outer shell uses GlassVariant.REGULAR; all profile cards inside are 100% opaque.
 */
@Composable
fun MultiPcSheetDialog(
    profiles: List<SavedPcProfile>,
    activeProfileId: String?,
    connState: ConnState,
    pingMs: Int,
    onSelectProfile: (SavedPcProfile) -> Unit,
    onAddPc: () -> Unit,
    onManageProfiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scope = rememberCoroutineScope()
    var wakeFeedback by remember { mutableStateOf<String?>(null) }

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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MultiPcSheetHeader(onDismiss = onDismiss)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    profiles.forEach { profile ->
                        val isActive = profile.id == activeProfileId
                        val isOnline = isActive && connState is ConnState.Connected
                        MultiPcProfileRow(
                            profile = profile,
                            isActive = isActive,
                            isOnline = isOnline,
                            pingMs = pingMs,
                            onSelect = {
                                if (hapticsEnabled) Haptics.action(context)
                                onSelectProfile(profile)
                            },
                            onWake = {
                                if (hapticsEnabled) Haptics.action(context)
                                val info = NuvioWakeInfo(profile.wakeMacs, profile.wakeBroadcasts)
                                scope.launch {
                                    val sent = NuvioWakeOnLan.sendWake(info)
                                    wakeFeedback = if (sent) {
                                        "WoL -> ${profile.name}"
                                    } else {
                                        "WoL"
                                    }
                                }
                            },
                        )
                    }
                }

                wakeFeedback?.let { msg ->
                    NuvioText(
                        text = l10n("Wake signal sent: $msg", "Sinal de despertar enviado: $msg"),
                        style = NuvioTypography.KeyLabelCompact,
                        color = scheme.statusOnline,
                    )
                }

                MultiPcSheetFooter(
                    onAddPc = {
                        if (hapticsEnabled) Haptics.action(context)
                        onAddPc()
                    },
                    onManageProfiles = {
                        if (hapticsEnabled) Haptics.tab(context)
                        onManageProfiles()
                    },
                )
            }
        }
    }
}

@Composable
private fun MultiPcSheetHeader(onDismiss: () -> Unit) {
    val scheme = LocalNuvioColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NuvioText(
                text = l10n("MY COMPUTERS", "MEUS COMPUTADORES"),
                style = NuvioTypography.SectionMicroTitle.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
                color = scheme.ctxPlayer,
                maxLines = 1,
            )
            NuvioText(
                text = l10n("Switch active computer", "Trocar computador ativo"),
                style = NuvioTypography.DisplayKey.copy(fontWeight = FontWeight.Bold),
                color = scheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
private fun MultiPcProfileRow(
    profile: SavedPcProfile,
    isActive: Boolean,
    isOnline: Boolean,
    pingMs: Int,
    onSelect: () -> Unit,
    onWake: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (isOnline) scheme.statusOnline.copy(alpha = 0.72f) else scheme.contentBorder
    val statusLine = when {
        isOnline && pingMs > 0 -> l10n("Active • $pingMs ms • Wi-Fi", "Ativo • $pingMs ms • Wi-Fi")
        isOnline -> l10n("Active • Wi-Fi", "Ativo • Wi-Fi")
        isActive -> l10n("Selected • Offline", "Selecionado • Offline")
        else -> l10n("Tap to switch", "Toque para alternar")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .clip(shape)
            .background(scheme.contentCardSurface)
            .border(if (isOnline) 1.5.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NuvioText(
                text = profile.name,
                style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.Bold),
                color = scheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NuvioText(
                text = "${profile.host} • $statusLine",
                style = NuvioTypography.KeyLabelCompact,
                color = if (isOnline) scheme.statusOnline else scheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (isOnline) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.statusOnline.copy(alpha = 0.14f))
                    .border(1.dp, scheme.statusOnline.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                NuvioText(
                    text = l10n("Connected", "Conectado"),
                    style = NuvioTypography.SectionMicroTitle,
                    color = scheme.statusOnline,
                    maxLines = 1,
                )
            }
        } else if (!isOnline && profile.wakeMacs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(onClick = onWake),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    NuvioIcon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = scheme.pillActiveFg,
                        modifier = Modifier.size(14.dp),
                    )
                    NuvioText(
                        text = l10n("Wake", "Acordar"),
                        style = NuvioTypography.SectionMicroTitle.copy(fontWeight = FontWeight.Bold),
                        color = scheme.pillActiveFg,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(onClick = onSelect),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.contentCellSurface)
                        .border(1.dp, scheme.contentBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    NuvioText(
                        text = l10n("Switch", "Alternar"),
                        style = NuvioTypography.SectionMicroTitle,
                        color = scheme.textPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MultiPcSheetFooter(onAddPc: () -> Unit, onManageProfiles: () -> Unit) {
    val scheme = LocalNuvioColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
                .clickable(onClick = onAddPc)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioIcon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = scheme.pillActiveFg,
                modifier = Modifier.size(16.dp),
            )
            NuvioText(
                text = l10n("Add PC", "Adicionar PC"),
                style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.Bold),
                color = scheme.pillActiveFg,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                .clickable(onClick = onManageProfiles)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            NuvioText(
                text = l10n("Manage", "Gerenciar"),
                style = NuvioTypography.KeyLabelPrimary,
                color = scheme.textPrimary,
                maxLines = 1,
            )
        }
    }
}
