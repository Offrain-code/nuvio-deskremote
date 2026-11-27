package com.nuvio.remote.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nuvio.remote.ui.components.NuvioGlassSwitch
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography

/**
 * Opaque Settings card for optional auto-pause on incoming phone calls (DESIGN-SPEC §5.15.2).
 * Off by default; requests READ_PHONE_STATE at runtime only when enabled.
 */
@Composable
internal fun CallAutoPauseSettingsCard(
    pauseOnCall: Boolean,
    resumeAfterCall: Boolean,
    onPauseOnCallChange: (Boolean) -> Unit,
    onResumeAfterCallChange: (Boolean) -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            onPauseOnCallChange(true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.contentCardSurface)
            .border(1.dp, scheme.contentBorder, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NuvioText(
            text = l10n("PHONE CALLS", "CHAMADAS TELEFÔNICAS"),
            style = NuvioTypography.SectionMicroTitle.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
            color = scheme.ctxPlayer,
            maxLines = 1,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.contentCellSurface)
                .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NuvioText(
                    text = l10n("Pause Nuvio on phone calls", "Pausar Nuvio em chamadas telefônicas"),
                    style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.textPrimary,
                )
                NuvioText(
                    text = l10n(
                        "Requests phone state permission only if enabled",
                        "Só pede permissão de chamada se ativado",
                    ),
                    style = NuvioTypography.KeyLabelCompact,
                    color = scheme.textSecondary,
                )
            }
            NuvioGlassSwitch(
                checked = pauseOnCall,
                onCheckedChange = { enable ->
                    if (!enable) {
                        onPauseOnCallChange(false)
                    } else {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_PHONE_STATE,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            onPauseOnCallChange(true)
                        } else {
                            permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        }
                    }
                },
            )
        }

        if (pauseOnCall) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.contentCellSurface)
                    .border(1.dp, scheme.contentBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NuvioText(
                        text = l10n("Resume when call ends", "Retomar ao encerrar chamada"),
                        style = NuvioTypography.KeyLabelPrimary.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.textPrimary,
                    )
                    NuvioText(
                        text = l10n(
                            "Auto-resume playback after hanging up",
                            "Retoma a reprodução automaticamente ao desligar",
                        ),
                        style = NuvioTypography.KeyLabelCompact,
                        color = scheme.textSecondary,
                    )
                }
                NuvioGlassSwitch(
                    checked = resumeAfterCall,
                    onCheckedChange = onResumeAfterCallChange,
                )
            }
        }
    }
}
