package com.nuvio.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nuvio.remote.pairing.SavedPcProfile
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass

/**
 * Opaque card in Settings for managing multiple paired PCs (DESIGN-SPEC §5.14.3):
 * connect, rename, and remove with 2-tap confirmation.
 */
@Composable
internal fun SavedPcsSettingsCard(
    profiles: List<SavedPcProfile>,
    activeProfileId: String?,
    onSelectProfile: (SavedPcProfile) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onRemoveProfile: (String) -> Unit,
) {
    if (profiles.isEmpty()) return
    val scheme = LocalNuvioColors.current
    var pendingRemoveId by remember { mutableStateOf<String?>(null) }
    var renamingProfile by remember { mutableStateOf<SavedPcProfile?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioText(
                text = l10n("SAVED COMPUTERS", "COMPUTADORES SALVOS"),
                style = NuvioTypography.SectionMicroTitle.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
                color = scheme.ctxPlayer,
                maxLines = 1,
            )
            NuvioText(
                text = "${profiles.size}",
                style = NuvioTypography.KeyLabelCompact,
                color = scheme.textSecondary,
                maxLines = 1,
            )
        }

        profiles.forEach { profile ->
            val isActive = profile.id == activeProfileId
            val isConfirmingRemove = pendingRemoveId == profile.id
            SavedPcSettingsRow(
                profile = profile,
                isActive = isActive,
                isConfirmingRemove = isConfirmingRemove,
                onConnect = {
                    pendingRemoveId = null
                    onSelectProfile(profile)
                },
                onRename = {
                    pendingRemoveId = null
                    renamingProfile = profile
                },
                onRemoveClick = {
                    if (isConfirmingRemove) {
                        pendingRemoveId = null
                        onRemoveProfile(profile.id)
                    } else {
                        pendingRemoveId = profile.id
                    }
                },
            )
        }
    }

    renamingProfile?.let { target ->
        RenamePcDialog(
            initialName = target.name,
            onDismiss = { renamingProfile = null },
            onSave = { nextName ->
                onRenameProfile(target.id, nextName)
                renamingProfile = null
            },
        )
    }
}

@Composable
private fun SavedPcSettingsRow(
    profile: SavedPcProfile,
    isActive: Boolean,
    isConfirmingRemove: Boolean,
    onConnect: () -> Unit,
    onRename: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val shape = RoundedCornerShape(14.dp)
    val border = when {
        isConfirmingRemove -> scheme.statusError.copy(alpha = 0.65f)
        isActive -> scheme.statusOnline.copy(alpha = 0.55f)
        else -> scheme.contentBorder
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.contentCellSurface)
            .border(1.dp, border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NuvioText(
                    text = if (isActive) {
                        "${profile.name} (${l10n("Active", "Ativo")})"
                    } else {
                        profile.name
                    },
                    style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.Bold),
                    color = scheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                NuvioText(
                    text = "${profile.host}:${profile.port}",
                    style = NuvioTypography.KeyLabelCompact,
                    color = scheme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!isActive) {
                SettingsProfileActionChip(
                    label = l10n("Connect", "Conectar"),
                    primary = true,
                    danger = false,
                    onClick = onConnect,
                    modifier = Modifier.weight(1f),
                )
            }
            SettingsProfileActionChip(
                label = l10n("Rename", "Renomear"),
                primary = false,
                danger = false,
                onClick = onRename,
                modifier = Modifier.weight(1f),
            )
            SettingsProfileActionChip(
                label = if (isConfirmingRemove) {
                    l10n("Confirm?", "Confirmar?")
                } else {
                    l10n("Remove", "Remover")
                },
                primary = false,
                danger = isConfirmingRemove,
                onClick = onRemoveClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SettingsProfileActionChip(
    label: String,
    primary: Boolean,
    danger: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val shape = RoundedCornerShape(11.dp)
    val bgModifier = when {
        primary -> Modifier.background(
            Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)),
            shape,
        )
        danger ->
            Modifier
                .background(scheme.statusError.copy(alpha = 0.16f), shape)
                .border(1.dp, scheme.statusError.copy(alpha = 0.65f), shape)
        else ->
            Modifier
                .background(scheme.contentCardSurface, shape)
                .border(1.dp, scheme.contentBorder, shape)
    }
    val textColor = when {
        primary -> scheme.pillActiveFg
        danger -> scheme.statusError
        else -> scheme.textPrimary
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .then(bgModifier)
                .padding(horizontal = 8.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            NuvioText(
                text = label,
                style = NuvioTypography.SectionMicroTitle.copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RenamePcDialog(initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val scheme = LocalNuvioColors.current
    var text by remember(initialName) { mutableStateOf(initialName) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .nuvioGlass(shape = RoundedCornerShape(22.dp), variant = GlassVariant.REGULAR, elevated = true)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NuvioText(
                text = l10n("Rename computer", "Renomear computador"),
                style = NuvioTypography.DisplayKey.copy(fontWeight = FontWeight.Bold),
                color = scheme.textPrimary,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.contentCardSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(32) },
                    singleLine = true,
                    textStyle = NuvioTypography.BodyRegular.copy(color = scheme.textPrimary),
                    cursorBrush = SolidColor(scheme.ctxPlayer),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsProfileActionChip(
                    label = l10n("Cancel", "Cancelar"),
                    primary = false,
                    danger = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                SettingsProfileActionChip(
                    label = l10n("Save", "Salvar"),
                    primary = true,
                    danger = false,
                    onClick = {
                        if (text.trim().isNotEmpty()) onSave(text.trim())
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
