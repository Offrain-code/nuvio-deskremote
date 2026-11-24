package com.nuvio.remote.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.nuvio.remote.connection.QrPairParser
import com.nuvio.remote.connection.QrPairPayload
import com.nuvio.remote.ui.components.Haptics
import com.nuvio.remote.ui.components.NuvioIcon
import com.nuvio.remote.ui.components.NuvioText
import com.nuvio.remote.ui.i18n.LocalAppLanguage
import com.nuvio.remote.ui.i18n.l10n
import com.nuvio.remote.ui.theme.ClearContentShadow
import com.nuvio.remote.ui.theme.GlassVariant
import com.nuvio.remote.ui.theme.LocalHapticsEnabled
import com.nuvio.remote.ui.theme.LocalNuvioColors
import com.nuvio.remote.ui.theme.NuvioTypography
import com.nuvio.remote.ui.theme.nuvioGlass
import java.util.concurrent.Executors

/**
 * QR code scanner modal (DESIGN-SPEC.md section 5.6).
 * Uses offline CameraX + ZXing with floating `GlassVariant.CLEAR` capsules over camera preview.
 */
@Composable
fun QrScannerDialog(onScanned: (QrPairPayload) -> Unit, onManualFallback: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val lang = LocalAppLanguage.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var detectedLabel by remember { mutableStateOf<String?>(null) }
    var delivered by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (hasPermission) {
                QrCameraPreview(
                    onPayloadDetected = { payload ->
                        if (!delivered) {
                            delivered = true
                            val parts = listOfNotNull(
                                payload.host,
                                payload.pin?.let {
                                    "${l10n(lang, "PIN", "Código")} ${it.chunked(3).joinToString(" ")}"
                                },
                            )
                            detectedLabel = "${l10n(lang, "Detected", "Detectado")}: ${parts.joinToString(" • ")}"
                            if (hapticsEnabled) Haptics.ok(context)
                            onScanned(payload)
                        }
                    },
                )
            } else {
                CameraPermissionFallback(
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }

            QrScannerOverlayUi(
                detectedLabel = detectedLabel,
                onManualFallback = onManualFallback,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun QrCameraPreview(onPayloadDetected: (QrPairPayload) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProviderRef?.unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener(
                {
                    val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
                    cameraProviderRef = provider
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { ia ->
                            ia.setAnalyzer(executor) { imageProxy ->
                                decodeQrFromProxy(reader, imageProxy)?.let { payload ->
                                    ContextCompat.getMainExecutor(ctx).execute {
                                        onPayloadDetected(payload)
                                    }
                                }
                            }
                        }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

private fun decodeQrFromProxy(reader: MultiFormatReader, imageProxy: ImageProxy): QrPairPayload? {
    return try {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val source = PlanarYUVLuminanceSource(
            data,
            plane.rowStride,
            imageProxy.height,
            0,
            0,
            imageProxy.width.coerceAtMost(plane.rowStride),
            imageProxy.height,
            false,
        )
        val result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }.getOrNull()
        reader.reset()
        result?.text?.let(QrPairParser::parse)
    } finally {
        imageProxy.close()
    }
}

@Composable
private fun CameraPermissionFallback(onRequestPermission: () -> Unit) {
    val scheme = LocalNuvioColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NuvioText(
            text = l10n(
                "Camera permission is required to scan the QR Code.",
                "Permissão de câmera necessária para ler o QR Code.",
            ),
            style = NuvioTypography.BodyRegular,
            color = Color.White,
        )
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .clickable(onClick = onRequestPermission),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(scheme.clearPillActiveBgTop, scheme.clearPillActiveBgBottom),
                        ),
                    )
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                NuvioText(
                    text = l10n("Allow camera", "Autorizar câmera"),
                    style = NuvioTypography.KeyLabelCompact,
                    color = scheme.clearPillActiveFg,
                )
            }
        }
    }
}

@Composable
private fun QrScannerOverlayUi(detectedLabel: String?, onManualFallback: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .nuvioGlass(
                    shape = RoundedCornerShape(20.dp),
                    variant = GlassVariant.CLEAR,
                    elevated = true,
                    isBackdropLight = true,
                    captureStageBackdrop = false,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NuvioText(
                text = l10n("SCAN PAIRING QR CODE", "ESCANEAR QR CODE DO NUVIO"),
                style = NuvioTypography.SectionMicroTitle,
                color = Color.White,
                shadow = ClearContentShadow,
            )
            NuvioText(
                text = l10n(
                    "Point at the Nuvio DeskRemote Companion or Nuvio Settings QR on PC",
                    "Aponte para o Companion ou Ajustes > Controle pelo celular no PC",
                ),
                style = NuvioTypography.KeyShortcutBadge,
                color = Color.White.copy(alpha = 0.85f),
                shadow = ClearContentShadow,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(230.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.72f), RoundedCornerShape(26.dp)),
            )
            if (!detectedLabel.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .nuvioGlass(
                            shape = CircleShape,
                            variant = GlassVariant.CLEAR,
                            elevated = true,
                            isBackdropLight = true,
                            captureStageBackdrop = false,
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    NuvioText(
                        text = detectedLabel,
                        style = NuvioTypography.KeyLabelCompact,
                        color = Color.White,
                        shadow = ClearContentShadow,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QrBottomActionPill(
                label = l10n("Enter PIN", "Digitar código"),
                icon = Icons.Rounded.Keyboard,
                primary = false,
                onClick = onManualFallback,
                modifier = Modifier.weight(1f),
            )
            QrBottomActionPill(
                label = l10n("Cancel", "Cancelar"),
                icon = Icons.Rounded.Close,
                primary = true,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QrBottomActionPill(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalNuvioColors.current
    val fg = if (primary) scheme.clearPillActiveFg else Color.White
    val boxMod = if (primary) {
        Modifier
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(listOf(scheme.clearPillActiveBgTop, scheme.clearPillActiveBgBottom)),
            )
    } else {
        Modifier.nuvioGlass(
            shape = CircleShape,
            variant = GlassVariant.CLEAR,
            elevated = true,
            isBackdropLight = true,
            captureStageBackdrop = false,
        )
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .then(boxMod)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            NuvioIcon(imageVector = icon, contentDescription = label, tint = fg, modifier = Modifier.size(16.dp))
            NuvioText(
                text = label,
                style = NuvioTypography.KeyLabelCompact,
                color = fg,
                shadow = if (primary) null else ClearContentShadow,
                maxLines = 1,
            )
        }
    }
}
