package com.nuvio.remote.ui.theme

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.ui.i18n.LocalAppLanguage
import com.nuvio.remote.ui.i18n.ResolvedLanguage
import kotlin.math.max
import kotlin.math.min
import android.graphics.Color as AndroidColor
import android.graphics.RenderEffect as AndroidRenderEffect

private const val SCRIM_FOR_LIGHT_BACKDROP_ALPHA = 0.35f
private const val SCRIM_FOR_DARK_CLEAR_ALPHA = 0.18f
private const val LIGHT_LUMINANCE_THRESHOLD = 0.56f
private const val CLEAR_DARK_RESTING_TOP_ALPHA = 0.22f
private const val CLEAR_DARK_RESTING_BOTTOM_ALPHA = 0.26f
private const val CLEAR_DARK_ACTIVE_TOP_ALPHA = 0.34f
private const val CLEAR_DARK_ACTIVE_BOTTOM_ALPHA = 0.38f
private const val CLEAR_LIGHT_RESTING_TOP_ALPHA = 0.10f
private const val CLEAR_LIGHT_RESTING_BOTTOM_ALPHA = 0.13f
private const val CLEAR_LEGACY_RESTING_TOP_ALPHA = 0.44f
private const val CLEAR_LEGACY_RESTING_BOTTOM_ALPHA = 0.48f
private const val CLEAR_LEGACY_RESTING_LIGHT_TOP_ALPHA = 0.56f
private const val CLEAR_LEGACY_RESTING_LIGHT_BOTTOM_ALPHA = 0.60f
private const val CLEAR_LEGACY_ACTIVE_TOP_ALPHA = 0.66f
private const val CLEAR_LEGACY_ACTIVE_BOTTOM_ALPHA = 0.70f
private const val CLEAR_LEGACY_ACTIVE_LIGHT_TOP_ALPHA = 0.76f
private const val CLEAR_LEGACY_ACTIVE_LIGHT_BOTTOM_ALPHA = 0.80f

val ClearContentShadow = Shadow(
    color = Color(0x94000000),
    offset = Offset(0f, 1.5f),
    blurRadius = 4f,
)

@Stable
data class ClearSurfaceAlphas(
    val topAlpha: Float,
    val bottomAlpha: Float,
    val extraScrimAlpha: Float,
    val rimTopAlpha: Float,
    val rimBaseAlpha: Float,
    val specularAlpha: Float,
) {
    val effectiveBottomAlpha: Float
        get() = 1f - (1f - bottomAlpha) * (1f - extraScrimAlpha)

    fun interpolateTo(target: ClearSurfaceAlphas, weight: Float): ClearSurfaceAlphas {
        val t = weight.coerceIn(0f, 1f)
        return ClearSurfaceAlphas(
            topAlpha = topAlpha + (target.topAlpha - topAlpha) * t,
            bottomAlpha = bottomAlpha + (target.bottomAlpha - bottomAlpha) * t,
            extraScrimAlpha = extraScrimAlpha + (target.extraScrimAlpha - extraScrimAlpha) * t,
            rimTopAlpha = rimTopAlpha + (target.rimTopAlpha - rimTopAlpha) * t,
            rimBaseAlpha = rimBaseAlpha + (target.rimBaseAlpha - rimBaseAlpha) * t,
            specularAlpha = specularAlpha + (target.specularAlpha - specularAlpha) * t,
        )
    }
}

private const val LIQUID_LENS_AGSL = """
uniform shader content;
uniform float2 iResolution;
uniform float iCornerRadius;
uniform float iLensStrength;

float roundedRectSdf(float2 p, float2 halfSize, float r) {
    float2 d = abs(p) - halfSize + r;
    return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
}

half4 main(float2 fragCoord) {
    float2 safeRes = max(iResolution, float2(2.0));
    float2 halfSize = safeRes * 0.5;
    float r = clamp(iCornerRadius, 1.0, min(halfSize.x, halfSize.y));
    float2 p = fragCoord - halfSize;
    float dist = roundedRectSdf(p, halfSize, r);
    float edgeBand = smoothstep(-18.0, 0.0, dist);
    float2 dir = length(p) > 0.001 ? normalize(p) : float2(0.0);
    float2 offset = dir * (edgeBand * iLensStrength);
    float2 sampleCoord = clamp(fragCoord - offset, float2(0.5), safeRes - float2(0.5));
    return content.eval(sampleCoord);
}
"""

/** Official Liquid Glass surface variants. */
enum class GlassVariant {
    REGULAR,
    CLEAR,
}

/** Real optical rendering tier supported by the device Android API level. */
enum class GlassApiTier {
    LEGACY_PRE_31,
    BLUR_31_32,
    LENS_33_PLUS,
}

/** Resolves the optical rendering tier safely supported for [sdkInt]. */
fun resolveGlassApiTier(sdkInt: Int = Build.VERSION.SDK_INT): GlassApiTier = when {
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> GlassApiTier.LENS_33_PLUS
    sdkInt >= Build.VERSION_CODES.S -> GlassApiTier.BLUR_31_32
    else -> GlassApiTier.LEGACY_PRE_31
}

/** Computes whether a 35% dark scrim is required under CLEAR controls over bright content. */
fun computeClearScrimAlpha(isBackdropLight: Boolean, surfaceAlpha: Float = 1f): Float {
    val base = if (isBackdropLight) SCRIM_FOR_LIGHT_BACKDROP_ALPHA else SCRIM_FOR_DARK_CLEAR_ALPHA
    return (base * surfaceAlpha).coerceIn(0f, 1f)
}

/** Estimates average relative luminance in the bottom band of a captured frame. */
fun isBottomFrameRegionLight(bitmap: Bitmap?): Boolean {
    if (bitmap == null || bitmap.isRecycled) return false
    if (bitmap.width <= 0 || bitmap.height <= 0) return false
    val w = bitmap.width
    val h = bitmap.height
    val startY = (h * 0.80f).toInt().coerceIn(0, h - 1)
    val stepX = (w / 10).coerceAtLeast(1)
    val stepY = ((h - startY) / 4).coerceAtLeast(1)
    var sum = 0f
    var count = 0
    var y = startY
    while (y < h) {
        var x = 0
        while (x < w) {
            val px = bitmap.getPixel(x, y)
            val luma = (
                0.2126f * AndroidColor.red(px) +
                    0.7152f * AndroidColor.green(px) +
                    0.0722f * AndroidColor.blue(px)
                ) / 255f
            sum += luma
            count++
            x += stepX
        }
        y += stepY
    }
    return count > 0 && (sum / count) >= LIGHT_LUMINANCE_THRESHOLD
}

/** Decides whether CLEAR controls need the 35% scrim (including live WebRTC streams). */
fun resolveClearBackdropLight(
    hasFallbackBitmap: Boolean,
    fallbackBitmapLight: Boolean,
    streamStatus: String,
    rtcSampledLight: Boolean?,
): Boolean = when {
    hasFallbackBitmap -> fallbackBitmapLight
    streamStatus == "live" -> rtcSampledLight ?: true
    else -> false
}

@Stable
class NuvioGlassBackdropState(
    val layer: GraphicsLayer,
) {
    var rootOrigin by mutableStateOf(Offset.Zero)
}

val LocalGlassBackdrop = staticCompositionLocalOf<NuvioGlassBackdropState?> { null }

@Composable
fun rememberGlassBackdropState(): NuvioGlassBackdropState {
    val graphicsLayer = rememberGraphicsLayer()
    return remember(graphicsLayer) { NuvioGlassBackdropState(graphicsLayer) }
}

/**
 * Single centralized Liquid Glass theme (zero Material 3).
 * Provides light/dark tokens, reduced-motion state, display language, and shared backdrop layer.
 */
@Composable
fun NuvioGlassTheme(
    hapticsEnabled: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    backdropState: NuvioGlassBackdropState? = null,
    resolvedLanguage: ResolvedLanguage = ResolvedLanguage.EN,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkNuvioColors else LightNuvioColors
    val reducedMotion = rememberSystemReducedMotion()

    CompositionLocalProvider(
        LocalNuvioColors provides colorScheme,
        LocalNuvioIsDark provides darkTheme,
        LocalHapticsEnabled provides hapticsEnabled,
        LocalReducedMotion provides reducedMotion,
        LocalGlassBackdrop provides backdropState,
        LocalAppLanguage provides resolvedLanguage,
        content = content,
    )
}

/**
 * Ambient backdrop stage that records only the atmospheric background for live refraction
 * by floating controls (never capturing the glass layer itself, preventing feedback cycles).
 */
@Composable
fun NuvioAmbientBackdropStage(
    backdropState: NuvioGlassBackdropState,
    activeContext: NuvioScreenState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = LocalNuvioColors.current
    val accent = accentForScreen(activeContext)

    Box(
        modifier = modifier.onGloballyPositioned { coords ->
            backdropState.rootOrigin = coords.positionInRoot()
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawAmbientBackdropLayer(backdropState, scheme, accent),
        )
        content()
    }
}

private fun Modifier.drawAmbientBackdropLayer(
    backdropState: NuvioGlassBackdropState,
    scheme: NuvioColorScheme,
    accent: StateAccent,
): Modifier = drawWithCache {
    onDrawWithContent {
        backdropState.layer.record(
            size = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
        ) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        scheme.canvasGradientTop,
                        scheme.canvasBackground,
                        scheme.canvasGradientBottom,
                    ),
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    0.0f to scheme.ambientGlowPrimary,
                    0.46f to scheme.ambientGlowSecondary.copy(alpha = scheme.ambientGlowSecondary.alpha * 0.65f),
                    0.82f to scheme.ambientGlowSecondary.copy(alpha = scheme.ambientGlowSecondary.alpha * 0.14f),
                    1.0f to Color.Transparent,
                    center = Offset(size.width * 0.50f, size.height * 0.36f),
                    radius = size.maxDimension * 0.58f,
                ),
            )
            val bottomGlowColor = if (accent.surfaceTint.alpha > 0.01f) {
                accent.primary.copy(alpha = if (scheme.isDark) 0.10f else 0.12f)
            } else {
                scheme.ambientGlowTertiary
            }
            drawRect(
                brush = Brush.radialGradient(
                    0.0f to bottomGlowColor,
                    0.50f to bottomGlowColor.copy(alpha = bottomGlowColor.alpha * 0.30f),
                    1.0f to Color.Transparent,
                    center = Offset(size.width * 0.50f, size.height * 0.80f),
                    radius = size.maxDimension * 0.50f,
                ),
            )
        }
        drawLayer(backdropState.layer)
    }
}

/**
 * Single centralized Liquid Glass modifier:
 * - API 33+: live blur + saturation + peripheral AGSL lens over the captured backdrop;
 * - API 31-32: live blur + saturation without AGSL lens;
 * - API 26-30: high-contrast translucent fallback surface (no live blur).
 */
fun Modifier.nuvioGlass(
    shape: Shape = RoundedCornerShape(20.dp),
    variant: GlassVariant = GlassVariant.REGULAR,
    elevated: Boolean = false,
    pressed: Boolean = false,
    accentTint: Color = Color.Unspecified,
    isBackdropLight: Boolean = false,
    surfaceAlpha: Float = 1f,
    captureStageBackdrop: Boolean = variant != GlassVariant.CLEAR,
    resting: Boolean = false,
): Modifier = composed {
    val scheme = LocalNuvioColors.current
    val backdrop = if (captureStageBackdrop && variant != GlassVariant.CLEAR) LocalGlassBackdrop.current else null
    val underlayLayer = rememberGraphicsLayer()
    val tier = remember { resolveGlassApiTier() }
    val reducedMotion = LocalReducedMotion.current
    val restingWeight by animateFloatAsState(
        targetValue = if (resting) 1f else 0f,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else NuvioMotion.EXIT_DURATION_MS),
        label = "glassRestingWeight",
    )
    var globalPosition by remember { mutableStateOf(Offset.Zero) }
    var boundsSize by remember { mutableStateOf(IntSize.Zero) }

    val blurRadiusPx = if (elevated) 28f else 22f
    val saturation = if (variant == GlassVariant.CLEAR) 1.65f else 1.45f
    val lensStrength = if (variant == GlassVariant.CLEAR) 6.5f else 5.0f

    val lensShader = remember(tier, backdrop != null) {
        val useLens = backdrop != null &&
            tier == GlassApiTier.LENS_33_PLUS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (useLens) createLensShader() else null
    }
    val opticalRenderEffect = remember(tier, boundsSize, blurRadiusPx, saturation, lensShader, backdrop != null) {
        backdrop?.let {
            buildBackdropRenderEffect(tier, boundsSize, blurRadiusPx, saturation, lensStrength, lensShader)
        }
    }

    this
        .clip(shape)
        .onGloballyPositioned { coords ->
            globalPosition = coords.positionInRoot()
            boundsSize = coords.size
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val clipPath = outlineToPath(outline)
            onDrawBehind {
                resolveLiveBackdrop(backdrop, tier, boundsSize)?.let { liveBackdrop ->
                    val relX = liveBackdrop.rootOrigin.x - globalPosition.x
                    val relY = liveBackdrop.rootOrigin.y - globalPosition.y
                    underlayLayer.clip = true
                    underlayLayer.renderEffect = opticalRenderEffect
                    underlayLayer.record(size = boundsSize) {
                        translate(left = relX, top = relY) { drawLayer(liveBackdrop.layer) }
                    }
                    clipPath(clipPath) { drawLayer(underlayLayer) }
                }
                val alphaScale = surfaceAlpha.coerceIn(0.2f, 1f)
                val useStructuredFallback =
                    tier == GlassApiTier.LEGACY_PRE_31 || (variant != GlassVariant.CLEAR && backdrop == null)
                val clearAlphas = if (variant == GlassVariant.CLEAR) {
                    val active = resolveClearSurfaceAlphas(tier, isBackdropLight, resting = false, alphaScale)
                    val rest = resolveClearSurfaceAlphas(tier, isBackdropLight, resting = true, alphaScale)
                    active.interpolateTo(rest, restingWeight)
                } else {
                    null
                }
                val config = GlassSurfaceDrawConfig(
                    variant,
                    useStructuredFallback,
                    elevated,
                    pressed,
                    accentTint,
                    alphaScale,
                    clearAlphas,
                )
                drawGlassSurfaceLayers(scheme = scheme, config = config, outline = outline)
            }
        }
}

private fun resolveLiveBackdrop(
    backdrop: NuvioGlassBackdropState?,
    tier: GlassApiTier,
    boundsSize: IntSize,
): NuvioGlassBackdropState? {
    if (backdrop == null || tier == GlassApiTier.LEGACY_PRE_31) return null
    if (boundsSize.width <= 1 || boundsSize.height <= 1) return null
    return backdrop.takeIf { it.layer.size.width > 0 && it.layer.size.height > 0 }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createLensShader(): RuntimeShader = RuntimeShader(LIQUID_LENS_AGSL)

private fun buildBackdropRenderEffect(
    tier: GlassApiTier,
    size: IntSize,
    blurRadius: Float,
    saturation: Float,
    lensStrength: Float,
    lensShader: RuntimeShader?,
): RenderEffect? {
    if (tier == GlassApiTier.LEGACY_PRE_31 || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return null
    }
    if (size.width <= 1 || size.height <= 1) {
        return null
    }
    return buildApi31Effect(tier, size, blurRadius, saturation, lensStrength, lensShader)
        .asComposeRenderEffect()
}

@RequiresApi(Build.VERSION_CODES.S)
private fun buildApi31Effect(
    tier: GlassApiTier,
    size: IntSize,
    blurRadius: Float,
    saturation: Float,
    lensStrength: Float,
    lensShader: RuntimeShader?,
): AndroidRenderEffect {
    val satMatrix = ColorMatrix().apply { setSaturation(saturation) }
    val satEffect = AndroidRenderEffect.createColorFilterEffect(ColorMatrixColorFilter(satMatrix))
    val blurEffect = AndroidRenderEffect.createBlurEffect(
        blurRadius,
        blurRadius,
        Shader.TileMode.CLAMP,
    )
    val blurAndSat = AndroidRenderEffect.createChainEffect(blurEffect, satEffect)
    if (
        tier == GlassApiTier.LENS_33_PLUS &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        lensShader != null
    ) {
        return chainApi33LensEffect(size, lensStrength, lensShader, blurAndSat)
    }
    return blurAndSat
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun chainApi33LensEffect(
    size: IntSize,
    lensStrength: Float,
    lensShader: RuntimeShader,
    blurAndSat: AndroidRenderEffect,
): AndroidRenderEffect {
    val w = max(size.width.toFloat(), 2f)
    val h = max(size.height.toFloat(), 2f)
    lensShader.setFloatUniform("iResolution", w, h)
    lensShader.setFloatUniform("iCornerRadius", min(w, h) * 0.25f)
    lensShader.setFloatUniform("iLensStrength", lensStrength)
    val lensEffect = AndroidRenderEffect.createRuntimeShaderEffect(lensShader, "content")
    return AndroidRenderEffect.createChainEffect(lensEffect, blurAndSat)
}

private fun outlineToPath(outline: Outline): Path = when (outline) {
    is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
    is Outline.Generic -> outline.path
}

fun resolveClearSurfaceAlphas(
    tier: GlassApiTier,
    isBackdropLight: Boolean,
    resting: Boolean,
    surfaceAlpha: Float = 1f,
): ClearSurfaceAlphas {
    val scale = surfaceAlpha.coerceIn(0.2f, 1f)
    val base = when {
        tier == GlassApiTier.LEGACY_PRE_31 && resting && isBackdropLight -> ClearSurfaceAlphas(
            topAlpha = CLEAR_LEGACY_RESTING_LIGHT_TOP_ALPHA,
            bottomAlpha = CLEAR_LEGACY_RESTING_LIGHT_BOTTOM_ALPHA,
            extraScrimAlpha = 0f,
            rimTopAlpha = 0.28f,
            rimBaseAlpha = 0.18f,
            specularAlpha = 0.10f,
        )
        tier == GlassApiTier.LEGACY_PRE_31 && resting -> ClearSurfaceAlphas(
            topAlpha = CLEAR_LEGACY_RESTING_TOP_ALPHA,
            bottomAlpha = CLEAR_LEGACY_RESTING_BOTTOM_ALPHA,
            extraScrimAlpha = 0f,
            rimTopAlpha = 0.28f,
            rimBaseAlpha = 0.18f,
            specularAlpha = 0.10f,
        )
        tier == GlassApiTier.LEGACY_PRE_31 && isBackdropLight -> ClearSurfaceAlphas(
            topAlpha = CLEAR_LEGACY_ACTIVE_LIGHT_TOP_ALPHA,
            bottomAlpha = CLEAR_LEGACY_ACTIVE_LIGHT_BOTTOM_ALPHA,
            extraScrimAlpha = 0f,
            rimTopAlpha = 0.34f,
            rimBaseAlpha = 0.22f,
            specularAlpha = 0.14f,
        )
        tier == GlassApiTier.LEGACY_PRE_31 -> ClearSurfaceAlphas(
            topAlpha = CLEAR_LEGACY_ACTIVE_TOP_ALPHA,
            bottomAlpha = CLEAR_LEGACY_ACTIVE_BOTTOM_ALPHA,
            extraScrimAlpha = 0f,
            rimTopAlpha = 0.34f,
            rimBaseAlpha = 0.22f,
            specularAlpha = 0.14f,
        )
        resting && isBackdropLight -> ClearSurfaceAlphas(
            topAlpha = CLEAR_LIGHT_RESTING_TOP_ALPHA,
            bottomAlpha = CLEAR_LIGHT_RESTING_BOTTOM_ALPHA,
            extraScrimAlpha = computeClearScrimAlpha(isBackdropLight = true, surfaceAlpha = 1f),
            rimTopAlpha = 0.26f,
            rimBaseAlpha = 0.18f,
            specularAlpha = 0.09f,
        )
        resting -> ClearSurfaceAlphas(
            topAlpha = CLEAR_DARK_RESTING_TOP_ALPHA,
            bottomAlpha = CLEAR_DARK_RESTING_BOTTOM_ALPHA,
            extraScrimAlpha = 0f,
            rimTopAlpha = 0.26f,
            rimBaseAlpha = 0.18f,
            specularAlpha = 0.09f,
        )
        isBackdropLight -> ClearSurfaceAlphas(
            topAlpha = CLEAR_DARK_ACTIVE_TOP_ALPHA,
            bottomAlpha = CLEAR_DARK_ACTIVE_BOTTOM_ALPHA,
            extraScrimAlpha = computeClearScrimAlpha(isBackdropLight = true, surfaceAlpha = 1f),
            rimTopAlpha = 0.34f,
            rimBaseAlpha = 0.22f,
            specularAlpha = 0.14f,
        )
        else -> ClearSurfaceAlphas(
            topAlpha = CLEAR_DARK_ACTIVE_TOP_ALPHA,
            bottomAlpha = CLEAR_DARK_ACTIVE_BOTTOM_ALPHA,
            extraScrimAlpha = 0f,
            rimTopAlpha = 0.34f,
            rimBaseAlpha = 0.22f,
            specularAlpha = 0.14f,
        )
    }
    return ClearSurfaceAlphas(
        topAlpha = base.topAlpha * scale,
        bottomAlpha = base.bottomAlpha * scale,
        extraScrimAlpha = base.extraScrimAlpha * scale,
        rimTopAlpha = base.rimTopAlpha * scale,
        rimBaseAlpha = base.rimBaseAlpha * scale,
        specularAlpha = base.specularAlpha * scale,
    )
}

private data class GlassSurfaceDrawConfig(
    val variant: GlassVariant,
    val useStructuredFallback: Boolean,
    val elevated: Boolean,
    val pressed: Boolean,
    val accentTint: Color,
    val alphaScale: Float,
    val clearAlphas: ClearSurfaceAlphas?,
)

private fun DrawScope.drawGlassSurfaceLayers(
    scheme: NuvioColorScheme,
    config: GlassSurfaceDrawConfig,
    outline: Outline,
) {
    val path = outlineToPath(outline)
    val baseColors = resolveGlassBaseColors(scheme, config)
    drawPath(path = path, brush = Brush.verticalGradient(baseColors))
    drawGlassOverlays(path, scheme, config)
    drawGlassSpecularAndRim(path, scheme, config)
}

private fun resolveGlassBaseColors(scheme: NuvioColorScheme, config: GlassSurfaceDrawConfig): List<Color> = when {
    config.variant == GlassVariant.CLEAR -> {
        val clear = config.clearAlphas ?: ClearSurfaceAlphas(
            topAlpha = CLEAR_DARK_ACTIVE_TOP_ALPHA * config.alphaScale,
            bottomAlpha = CLEAR_DARK_ACTIVE_BOTTOM_ALPHA * config.alphaScale,
            extraScrimAlpha = 0f,
            rimTopAlpha = 0.34f * config.alphaScale,
            rimBaseAlpha = 0.22f * config.alphaScale,
            specularAlpha = 0.14f * config.alphaScale,
        )
        listOf(
            Color(0xFF141822).copy(alpha = clear.topAlpha),
            Color(0xFF0A0D12).copy(alpha = clear.bottomAlpha),
        )
    }
    config.useStructuredFallback -> listOf(
        scheme.glassFallbackTop.copy(alpha = scheme.glassFallbackTop.alpha * config.alphaScale),
        scheme.glassFallbackBottom.copy(alpha = scheme.glassFallbackBottom.alpha * config.alphaScale),
    )
    config.elevated -> listOf(
        scheme.glassRegularElevatedTop.copy(alpha = scheme.glassRegularElevatedTop.alpha * config.alphaScale),
        scheme.glassRegularElevatedBottom.copy(alpha = scheme.glassRegularElevatedBottom.alpha * config.alphaScale),
    )
    else -> listOf(
        scheme.glassRegularTop.copy(alpha = scheme.glassRegularTop.alpha * config.alphaScale),
        scheme.glassRegularBottom.copy(alpha = scheme.glassRegularBottom.alpha * config.alphaScale),
    )
}

private fun DrawScope.drawGlassOverlays(path: Path, scheme: NuvioColorScheme, config: GlassSurfaceDrawConfig) {
    val extraScrim = config.clearAlphas?.extraScrimAlpha ?: 0f
    if (config.variant == GlassVariant.CLEAR && extraScrim > 0f) {
        drawPath(path = path, color = Color.Black.copy(alpha = extraScrim))
    }
    if (config.pressed) {
        val pressColor = if (scheme.isDark || config.variant == GlassVariant.CLEAR) Color.White else Color.Black
        drawPath(path = path, color = pressColor.copy(alpha = 0.08f * config.alphaScale))
    }
    if (config.accentTint != Color.Unspecified) {
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(config.accentTint.copy(alpha = 0.14f * config.alphaScale), Color.Transparent),
                start = Offset.Zero,
                end = Offset(size.width * 0.7f, size.height * 0.7f),
            ),
        )
    }
}

private fun DrawScope.drawGlassSpecularAndRim(path: Path, scheme: NuvioColorScheme, config: GlassSurfaceDrawConfig) {
    val clear = config.clearAlphas
    val specularTop = if (config.variant == GlassVariant.CLEAR) {
        Color.White.copy(alpha = clear?.specularAlpha ?: (0.14f * config.alphaScale))
    } else {
        scheme.glassSpecularTop.copy(alpha = scheme.glassSpecularTop.alpha * config.alphaScale)
    }
    val specularMid = if (config.variant == GlassVariant.CLEAR) {
        Color.White.copy(alpha = (clear?.specularAlpha ?: (0.14f * config.alphaScale)) * 0.25f)
    } else {
        scheme.glassSpecularMid.copy(alpha = scheme.glassSpecularMid.alpha * config.alphaScale)
    }
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(specularTop, specularMid, Color.Transparent),
            start = Offset.Zero,
            end = Offset(size.width * 0.85f, size.height * 0.85f),
        ),
    )

    val rimTop = if (config.variant == GlassVariant.CLEAR) {
        Color.White.copy(alpha = clear?.rimTopAlpha ?: (0.34f * config.alphaScale))
    } else {
        scheme.glassRim.copy(alpha = scheme.glassRim.alpha * config.alphaScale)
    }
    val rimBase = if (config.variant == GlassVariant.CLEAR) {
        Color.White.copy(alpha = clear?.rimBaseAlpha ?: (0.20f * config.alphaScale))
    } else {
        scheme.glassBorder.copy(alpha = scheme.glassBorder.alpha * config.alphaScale)
    }
    val rimAccent = if (config.accentTint != Color.Unspecified) {
        config.accentTint.copy(alpha = 0.32f * config.alphaScale)
    } else {
        rimBase
    }
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(rimTop, rimBase, rimAccent),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        style = Stroke(width = 1.dp.toPx()),
    )
}
