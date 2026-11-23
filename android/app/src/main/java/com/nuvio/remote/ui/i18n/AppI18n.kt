package com.nuvio.remote.ui.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.nuvio.remote.data.AppPrefs
import java.util.Locale

/**
 * User-selectable display language persisted in [AppPrefs].
 */
enum class AppLanguage(val wire: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    PORTUGUESE_BR("pt-BR"),
    ;

    companion object {
        fun fromWire(value: String?): AppLanguage =
            entries.find { it.wire.equals(value?.trim(), ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * Concrete display language resolved from [AppLanguage] and device locale.
 * English is the default for all non-Portuguese system locales.
 */
enum class ResolvedLanguage {
    EN,
    PT_BR,
}

/**
 * Process-wide holder for the currently active display language so non-Compose components
 * (connection errors, bridge feedback, background services) match the in-app selector.
 */
object AppLocaleState {
    @Volatile
    var current: ResolvedLanguage = ResolvedLanguage.EN
}

/**
 * Resolves the effective display language from [preference] and [locale].
 */
fun resolveAppLanguage(preference: AppLanguage, locale: Locale = Locale.getDefault()): ResolvedLanguage =
    when (preference) {
        AppLanguage.ENGLISH -> ResolvedLanguage.EN
        AppLanguage.PORTUGUESE_BR -> ResolvedLanguage.PT_BR
        AppLanguage.SYSTEM -> {
            if (locale.language.equals("pt", ignoreCase = true)) {
                ResolvedLanguage.PT_BR
            } else {
                ResolvedLanguage.EN
            }
        }
    }

/**
 * Resolves the active display language for non-Compose Android components.
 */
fun resolveContextLanguage(context: Context): ResolvedLanguage {
    val pref = AppLanguage.fromWire(AppPrefs(context).appLanguage)
    return resolveAppLanguage(pref)
}

/**
 * Active language exposed across the Compose hierarchy for instant in-app switching without
 * recreating the Activity or interrupting active WebSocket/WebRTC sessions.
 */
val LocalAppLanguage = staticCompositionLocalOf { ResolvedLanguage.EN }

/**
 * Returns the localized string for the active [LocalAppLanguage] inside Compose.
 */
@Composable
@ReadOnlyComposable
fun l10n(en: String, pt: String): String = l10n(LocalAppLanguage.current, en, pt)

/**
 * Returns the localized string for an explicit [lang] outside Compose.
 */
fun l10n(lang: ResolvedLanguage, en: String, pt: String): String = when (lang) {
    ResolvedLanguage.EN -> en
    ResolvedLanguage.PT_BR -> pt
}

/**
 * Returns the localized string using [AppLocaleState.current] for non-UI layers.
 */
fun l10nCurrent(en: String, pt: String): String = l10n(AppLocaleState.current, en, pt)
