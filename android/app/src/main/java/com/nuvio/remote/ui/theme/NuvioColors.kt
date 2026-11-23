package com.nuvio.remote.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.remote.connection.NuvioScreenState
import com.nuvio.remote.ui.i18n.l10n

/** Global haptic feedback flag across the Compose tree. */
val LocalHapticsEnabled = compositionLocalOf { true }

/** Dynamically respects Android's "Remove animations" setting during the session. */
val LocalReducedMotion = compositionLocalOf { false }

/** Indicates whether the current optical theme is dark or light. */
val LocalNuvioIsDark = staticCompositionLocalOf { true }

/**
 * Observes `ANIMATOR_DURATION_SCALE` via ContentObserver and ON_RESUME
 * so animations turn off immediately when "Remove animations" is enabled.
 */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var reduced by remember(context) { mutableStateOf(isSystemReducedMotion(context)) }

    DisposableEffect(context, lifecycleOwner) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = isSystemReducedMotion(context)
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reduced = isSystemReducedMotion(context)
            }
        }
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        reduced = isSystemReducedMotion(context)
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }
    return reduced
}

fun isSystemReducedMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}.getOrDefault(false)

/** Full semantic palette for Dark (living-room default) and Light (daytime) modes. */
@Immutable
data class NuvioColorScheme(
    val isDark: Boolean,
    val canvasBackground: Color,
    val canvasGradientTop: Color,
    val canvasGradientBottom: Color,
    val ambientGlowPrimary: Color,
    val ambientGlowSecondary: Color,
    val ambientGlowTertiary: Color,
    val contentCardSurface: Color,
    val contentCellSurface: Color,
    val contentCellPressed: Color,
    val contentBorder: Color,
    val trackpadSurfaceTop: Color,
    val trackpadSurfaceBottom: Color,
    val trackpadDots: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val pillActiveBgTop: Color,
    val pillActiveBgBottom: Color,
    val pillActiveFg: Color,
    val clearOverlayText: Color = Color(0xFFFFFFFF),
    val clearPillActiveBgTop: Color = Color(0xFFFFFFFF),
    val clearPillActiveBgBottom: Color = Color(0xFFE4E4E7),
    val clearPillActiveFg: Color = Color(0xFF090A0D),
    val glassRegularTop: Color,
    val glassRegularBottom: Color,
    val glassRegularElevatedTop: Color,
    val glassRegularElevatedBottom: Color,
    val glassFallbackTop: Color,
    val glassFallbackBottom: Color,
    val glassBorder: Color,
    val glassRim: Color,
    val glassSpecularTop: Color,
    val glassSpecularMid: Color,
    val statusOnline: Color,
    val statusWarning: Color,
    val statusError: Color,
    val tvRed: Color,
    val tvGreen: Color,
    val tvYellow: Color,
    val tvBlue: Color,
    val ctxNav: Color,
    val ctxPlayer: Color,
    val ctxSearch: Color,
)

val DarkNuvioColors = NuvioColorScheme(
    isDark = true,
    canvasBackground = Color(0xFF090A0D),
    canvasGradientTop = Color(0xFF10131A),
    canvasGradientBottom = Color(0xFF060709),
    ambientGlowPrimary = Color(0x385C80C6),
    ambientGlowSecondary = Color(0x288B5CF6),
    ambientGlowTertiary = Color(0x2438BDF8),
    contentCardSurface = Color(0xFF13161E),
    contentCellSurface = Color(0xFF191D28),
    contentCellPressed = Color(0xFF212736),
    contentBorder = Color(0x1AFFFFFF),
    trackpadSurfaceTop = Color(0xFF171B25),
    trackpadSurfaceBottom = Color(0xFF10131A),
    trackpadDots = Color(0x1CFFFFFF),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFB4B8C2),
    textMuted = Color(0xFF8B92A0),
    pillActiveBgTop = Color(0xFFFFFFFF),
    pillActiveBgBottom = Color(0xFFE4E4E7),
    pillActiveFg = Color(0xFF090A0D),
    glassRegularTop = Color(0x8F2C303C),
    glassRegularBottom = Color(0x9E14171E),
    glassRegularElevatedTop = Color(0xA8383D4C),
    glassRegularElevatedBottom = Color(0xB8181C25),
    glassFallbackTop = Color(0xEB1F232D),
    glassFallbackBottom = Color(0xF013161D),
    glassBorder = Color(0x33FFFFFF),
    glassRim = Color(0x57FFFFFF),
    glassSpecularTop = Color(0x24FFFFFF),
    glassSpecularMid = Color(0x05FFFFFF),
    statusOnline = Color(0xFF34D399),
    statusWarning = Color(0xFFFBBF24),
    statusError = Color(0xFFF87171),
    tvRed = Color(0xFFF87171),
    tvGreen = Color(0xFF4ADE80),
    tvYellow = Color(0xFFFACC15),
    tvBlue = Color(0xFF60A5FA),
    ctxNav = Color(0xFFF5F5F7),
    ctxPlayer = Color(0xFFC4B5FD),
    ctxSearch = Color(0xFFFCD34D),
)

val LightNuvioColors = NuvioColorScheme(
    isDark = false,
    canvasBackground = Color(0xFFEEF2F7),
    canvasGradientTop = Color(0xFFF7F9FC),
    canvasGradientBottom = Color(0xFFDFE7F2),
    ambientGlowPrimary = Color(0x386366F1),
    ambientGlowSecondary = Color(0x380EA5E9),
    ambientGlowTertiary = Color(0x24EC4899),
    contentCardSurface = Color(0xFFFFFFFF),
    contentCellSurface = Color(0xFFF3F6FB),
    contentCellPressed = Color(0xFFE8EEF7),
    contentBorder = Color(0x1C0F172A),
    trackpadSurfaceTop = Color(0xFFFFFFFF),
    trackpadSurfaceBottom = Color(0xFFEFF3F9),
    trackpadDots = Color(0x1F0F172A),
    textPrimary = Color(0xFF0D1117),
    textSecondary = Color(0xFF334155),
    textMuted = Color(0xFF475569),
    pillActiveBgTop = Color(0xFF181C24),
    pillActiveBgBottom = Color(0xFF0B0D12),
    pillActiveFg = Color(0xFFFFFFFF),
    glassRegularTop = Color(0xADFFFFFF),
    glassRegularBottom = Color(0x94F1F5FA),
    glassRegularElevatedTop = Color(0xC7FFFFFF),
    glassRegularElevatedBottom = Color(0xADF4F7FC),
    glassFallbackTop = Color(0xEDFFFFFF),
    glassFallbackBottom = Color(0xF2F2F6FB),
    glassBorder = Color(0x240F172A),
    glassRim = Color(0xF2FFFFFF),
    glassSpecularTop = Color(0x85FFFFFF),
    glassSpecularMid = Color(0x1FFFFFFF),
    statusOnline = Color(0xFF059669),
    statusWarning = Color(0xFFB45309),
    statusError = Color(0xFFDC2626),
    tvRed = Color(0xFFDC2626),
    tvGreen = Color(0xFF16A34A),
    tvYellow = Color(0xFFCA8A04),
    tvBlue = Color(0xFF2563EB),
    ctxNav = Color(0xFF0D1117),
    ctxPlayer = Color(0xFF6D28D9),
    ctxSearch = Color(0xFFB45309),
)

val LocalNuvioColors = staticCompositionLocalOf { DarkNuvioColors }

@Immutable
data class StateAccent(
    val primary: Color,
    val surfaceTint: Color,
    val label: String,
)

@Composable
fun accentForScreen(state: NuvioScreenState): StateAccent {
    val scheme = LocalNuvioColors.current
    return when (state) {
        NuvioScreenState.PLAYER -> StateAccent(
            primary = scheme.ctxPlayer,
            surfaceTint = scheme.ctxPlayer.copy(alpha = 0.14f),
            label = "Player",
        )
        NuvioScreenState.SEARCH -> StateAccent(
            primary = scheme.ctxSearch,
            surfaceTint = scheme.ctxSearch.copy(alpha = 0.14f),
            label = l10n("Keyboard", "Teclado"),
        )
        NuvioScreenState.HOME,
        NuvioScreenState.DETAIL,
        NuvioScreenState.UNKNOWN,
        -> StateAccent(
            primary = scheme.ctxNav,
            surfaceTint = scheme.ctxNav.copy(alpha = 0.08f),
            label = l10n("Browse", "Navegar"),
        )
    }
}

/**
 * GPU-only motion tokens (250-400ms, scale 0.98f -> 1f + fade, smooth spring).
 * Respects LocalReducedMotion at runtime.
 */
object NuvioMotion {
    const val ENTER_DURATION_MS = 300
    const val EXIT_DURATION_MS = 260

    fun <T> snappyPressSpring(reducedMotion: Boolean): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else spring(dampingRatio = 0.65f, stiffness = 500f)

    fun <T> smoothSpring(reducedMotion: Boolean): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    fun <T> liquidLeadSpring(reducedMotion: Boolean): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else spring(dampingRatio = 0.78f, stiffness = 460f)

    fun <T> liquidTrailSpring(reducedMotion: Boolean): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else spring(dampingRatio = 0.84f, stiffness = 280f)

    fun morphEnter(reducedMotion: Boolean): EnterTransition = if (reducedMotion) {
        EnterTransition.None
    } else {
        fadeIn(tween(ENTER_DURATION_MS)) +
            scaleIn(
                animationSpec = spring(dampingRatio = 0.86f, stiffness = 360f),
                initialScale = 0.98f,
            )
    }

    fun morphExit(reducedMotion: Boolean): ExitTransition = if (reducedMotion) {
        ExitTransition.None
    } else {
        fadeOut(tween(EXIT_DURATION_MS)) +
            scaleOut(
                animationSpec = spring(dampingRatio = 0.90f, stiffness = 400f),
                targetScale = 0.98f,
            )
    }
}
