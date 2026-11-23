package com.nuvio.remote.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.remote.ui.i18n.LocalAppLanguage
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.LocalReducedMotion
import com.nuvio.remote.ui.theme.NuvioMotion
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass
import com.nuvio.remote.update.AppUpdateRelease
import com.nuvio.remote.update.AppUpdateUiState
import com.nuvio.remote.update.UpdateErrorReason
import com.nuvio.remote.update.formatFileSize

private val NuvioBrandCyan = Color(0xFF22D3EE)
private val NuvioBrandIndigo = Color(0xFF818CF8)
private val NuvioBrandViolet = Color(0xFF9333EA)
private val ScrimDark = Color(0x9405070B)
private val ScrimLight = Color(0x6B0F172A)

/**
 * Liquid Glass automatic update popup following DESIGN-SPEC.md Section 5.11.
 * Applies [GlassVariant.REGULAR] strictly to the outer modal container while keeping
 * release notes, progress, and error cards 100% opaque for WCAG AA/AAA legibility.
 */
@Composable
fun AutoUpdateDialog(
    state: AppUpdateUiState,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallNow: () -> Unit,
    onRetry: () -> Unit,
    onCloseError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state !is AppUpdateUiState.Hidden
    val reducedMotion = LocalReducedMotion.current
    val scheme = LocalNuvioColors.current
    val scrimColor = if (scheme.isDark) ScrimDark else ScrimLight
    val dismissOnOutsideTap = state is AppUpdateUiState.Available || state is AppUpdateUiState.Error

    BackHandler(enabled = visible) {
        when (state) {
            is AppUpdateUiState.Available -> onLater()
            is AppUpdateUiState.Error -> onCloseError()
            is AppUpdateUiState.ReadyToInstall -> onCloseError()
            is AppUpdateUiState.Downloading -> Unit
            AppUpdateUiState.Hidden -> Unit
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = NuvioMotion.morphEnter(reducedMotion),
        exit = NuvioMotion.morphExit(reducedMotion),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (dismissOnOutsideTap) {
                        if (state is AppUpdateUiState.Available) onLater() else onCloseError()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 344.dp)
                    .fillMaxWidth()
                    .nuvioGlass(
                        shape = RoundedCornerShape(24.dp),
                        variant = GlassVariant.REGULAR,
                        elevated = true,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* Consume taps inside the modal container. */ }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    is AppUpdateUiState.Available -> UpdateAvailableContent(
                        currentVersion = state.currentVersion,
                        release = state.release,
                        onUpdateNow = onUpdateNow,
                        onLater = onLater,
                    )
                    is AppUpdateUiState.Downloading -> UpdateProgressContent(
                        release = state.release,
                        downloadedBytes = state.downloadedBytes,
                        totalBytes = state.totalBytes,
                        readyToInstall = false,
                        sha256Verified = false,
                        onCancel = onCancelDownload,
                        onInstall = onInstallNow,
                    )
                    is AppUpdateUiState.ReadyToInstall -> UpdateProgressContent(
                        release = state.release,
                        downloadedBytes = state.release.apkSizeBytes,
                        totalBytes = state.release.apkSizeBytes,
                        readyToInstall = true,
                        sha256Verified = state.sha256Verified,
                        onCancel = onCloseError,
                        onInstall = onInstallNow,
                    )
                    is AppUpdateUiState.Error -> UpdateErrorContent(
                        reason = state.reason,
                        onClose = onCloseError,
                        onRetry = onRetry,
                    )
                    AppUpdateUiState.Hidden -> Unit
                }
            }
        }
    }
}

@Composable
private fun UpdateAvailableContent(
    currentVersion: String,
    release: AppUpdateRelease,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    val notes = release.notesFor(lang)
    val sizeLabel = if (release.apkSizeBytes > 0L) formatFileSize(release.apkSizeBytes, lang) else "APK"

    UpdateModalHeader(
        kicker = l10n("GITHUB RELEASE • AUTO-UPDATE", "GITHUB RELEASE • ATUALIZAÇÃO"),
        title = l10n("Update available", "Atualização disponível"),
        badgeText = "v$currentVersion → v${release.version}",
        isError = false,
    )

    NuvioText(
        text = l10n(
            "A new version of Nuvio DeskRemote is ready to install.",
            "Uma nova versão do Nuvio DeskRemote está pronta para instalar.",
        ),
        style = NuvioTypography.BodyRegular,
        color = scheme.textSecondary,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioText(
            text = l10n("Official release", "Versão oficial"),
            style = NuvioTypography.KeyShortcutBadge,
            color = scheme.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        NuvioText(
            text = "APK • $sizeLabel",
            style = NuvioTypography.KeyShortcutBadge.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
            ),
            color = scheme.statusOnline,
            maxLines = 1,
        )
    }

    ReleaseNotesOpaqueBox(
        version = release.version,
        notes = notes,
    )

    NuvioText(
        text = l10n(
            "Your paired computers and local settings are kept automatically.",
            "Seus computadores pareados e ajustes locais são mantidos automaticamente.",
        ),
        style = NuvioTypography.KeyShortcutBadge,
        color = scheme.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UpdateModalButton(
            label = l10n("Later", "Mais tarde"),
            primary = false,
            icon = null,
            onClick = onLater,
            modifier = Modifier.weight(1f),
        )
        UpdateModalButton(
            label = l10n("Update now", "Atualizar agora"),
            primary = true,
            icon = Icons.Rounded.Bolt,
            onClick = onUpdateNow,
            modifier = Modifier.weight(1.25f),
        )
    }
}

@Composable
private fun ReleaseNotesOpaqueBox(version: String, notes: List<String>) {
    val scheme = LocalNuvioColors.current
    val scrollState = rememberScrollState()
    val displayNotes = notes.ifEmpty {
        listOf(
            l10n(
                "Stability improvements and refinements for Nuvio DeskRemote.",
                "Melhorias de estabilidade e ajustes gerais para o Nuvio DeskRemote.",
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioText(
                text = l10n("WHAT'S NEW IN v$version", "NOVIDADES DA VERSÃO v$version"),
                style = NuvioTypography.SectionMicroTitle,
                color = scheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 148.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            displayNotes.forEach { bullet ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.contentCellSurface)
                        .border(1.dp, scheme.contentBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    NuvioText(
                        text = "• $bullet",
                        style = NuvioTypography.BodyRegular.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        ),
                        color = scheme.textPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateProgressContent(
    release: AppUpdateRelease,
    downloadedBytes: Long,
    totalBytes: Long,
    readyToInstall: Boolean,
    sha256Verified: Boolean,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val lang = LocalAppLanguage.current
    val effectiveTotal = totalBytes.coerceAtLeast(release.apkSizeBytes).coerceAtLeast(1L)
    val fraction = if (readyToInstall) {
        1f
    } else {
        (downloadedBytes.toFloat() / effectiveTotal.toFloat()).coerceIn(0.02f, 0.99f)
    }
    val percent = if (readyToInstall) 100 else (fraction * 100f).toInt().coerceIn(0, 99)
    val progressLabel = buildString {
        append("$percent%")
        if (effectiveTotal > 1L) {
            append(" • ")
            append(formatFileSize(if (readyToInstall) effectiveTotal else downloadedBytes, lang))
            append(" / ")
            append(formatFileSize(effectiveTotal, lang))
        }
    }

    UpdateModalHeader(
        kicker = l10n("DOWNLOAD PROGRESS", "PROGRESSO DO DOWNLOAD"),
        title = if (readyToInstall) {
            l10n("Ready to install", "Pronto para instalar")
        } else {
            l10n("Downloading APK update…", "Baixando atualização do app…")
        },
        badgeText = "v${release.version}",
        isError = false,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioText(
                text = release.apkName,
                style = NuvioTypography.KeyLabelCompact.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            NuvioText(
                text = progressLabel,
                style = NuvioTypography.KeyShortcutBadge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
                color = scheme.ctxPlayer,
                maxLines = 1,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(scheme.contentCardSurface)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(NuvioBrandCyan, NuvioBrandIndigo))),
            )
        }

        if (readyToInstall) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioText(
                    text = l10n(
                        "Ready to install • Tap below to confirm",
                        "Pronto para instalar • Toque abaixo para concluir",
                    ),
                    style = NuvioTypography.KeyShortcutBadge,
                    color = scheme.statusOnline,
                    modifier = Modifier.weight(1f),
                )
                if (sha256Verified) {
                    Spacer(Modifier.width(6.dp))
                    NuvioText(
                        text = "SHA-256 OK",
                        style = NuvioTypography.KeyShortcutBadge.copy(fontWeight = FontWeight.Bold),
                        color = scheme.statusOnline,
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UpdateModalButton(
            label = l10n("Cancel", "Cancelar"),
            primary = false,
            icon = null,
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
        if (readyToInstall) {
            UpdateModalButton(
                label = l10n("Install update", "Instalar agora"),
                primary = true,
                icon = Icons.Rounded.Check,
                onClick = onInstall,
                modifier = Modifier.weight(1.25f),
            )
        }
    }
}

@Composable
private fun UpdateErrorContent(reason: UpdateErrorReason, onClose: () -> Unit, onRetry: () -> Unit) {
    val scheme = LocalNuvioColors.current
    val message = when (reason) {
        UpdateErrorReason.NETWORK -> l10n(
            "Could not download the package from GitHub Releases. Check your connection and try again.",
            "Não foi possível baixar o pacote do GitHub Releases. Verifique sua conexão e tente novamente.",
        )
        UpdateErrorReason.INTEGRITY -> l10n(
            "The downloaded package failed SHA-256 verification and was discarded. Please try again.",
            "O arquivo baixado falhou na verificação SHA-256 e foi descartado. Tente novamente.",
        )
        UpdateErrorReason.STORAGE -> l10n(
            "Not enough free storage on this phone to save the update APK.",
            "Espaço livre insuficiente no celular para salvar o pacote de atualização.",
        )
        UpdateErrorReason.INSTALL_PERMISSION -> l10n(
            "Allow installing apps from Nuvio DeskRemote in Android Settings, then tap Retry to install.",
            "Permita instalar apps pelo Nuvio DeskRemote nos Ajustes do Android e toque em Tentar novamente.",
        )
    }

    UpdateModalHeader(
        kicker = l10n("UPDATE ERROR", "ERRO DE ATUALIZAÇÃO"),
        title = l10n("Update download failed", "Falha ao baixar atualização"),
        badgeText = l10n("Retry", "Erro"),
        isError = true,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.statusError.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        NuvioText(
            text = message,
            style = NuvioTypography.BodyRegular.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
            color = scheme.statusError,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UpdateModalButton(
            label = l10n("Close", "Fechar"),
            primary = false,
            icon = null,
            onClick = onClose,
            modifier = Modifier.weight(1f),
        )
        UpdateModalButton(
            label = l10n("Retry", "Tentar novamente"),
            primary = true,
            icon = Icons.Rounded.Refresh,
            onClick = onRetry,
            modifier = Modifier.weight(1.25f),
        )
    }
}

@Composable
private fun UpdateModalHeader(kicker: String, title: String, badgeText: String, isError: Boolean) {
    val scheme = LocalNuvioColors.current
    val badgeBorder = if (isError) scheme.statusError else scheme.ctxPlayer
    val badgeFg = if (isError) scheme.statusError else scheme.ctxPlayer

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(NuvioBrandCyan, NuvioBrandIndigo, NuvioBrandViolet))),
            contentAlignment = Alignment.Center,
        ) {
            NuvioIcon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NuvioText(
                text = kicker,
                style = NuvioTypography.SectionMicroTitle.copy(fontSize = 10.sp),
                color = if (isError) scheme.statusError else scheme.ctxPlayer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NuvioText(
                text = title,
                style = NuvioTypography.DisplayKey.copy(fontWeight = FontWeight.Bold),
                color = scheme.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, badgeBorder.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            NuvioText(
                text = badgeText,
                style = NuvioTypography.KeyShortcutBadge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
                color = badgeFg,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun UpdateModalButton(
    label: String,
    primary: Boolean,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val scheme = LocalNuvioColors.current
    val fg = if (primary) scheme.pillActiveFg else scheme.textPrimary
    val bgModifier = if (primary) {
        Modifier.background(Brush.verticalGradient(listOf(scheme.pillActiveBgTop, scheme.pillActiveBgBottom)))
    } else {
        Modifier
            .background(scheme.contentCellSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(13.dp))
    }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { role = Role.Button }
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
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .then(bgModifier)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                NuvioIcon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact.copy(
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = fg,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
