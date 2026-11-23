package com.nuvio.remote.connection

import androidx.compose.runtime.Immutable
import com.nuvio.remote.ui.i18n.ResolvedLanguage
import com.nuvio.remote.ui.i18n.l10n

/**
 * Screen states tracked by the Companion server (PROTOCOL.md section 2 "state").
 * Server sends screen: "home|detail|player|search|unknown".
 */
enum class NuvioScreenState { HOME, DETAIL, PLAYER, SEARCH, UNKNOWN }

/** Primary bottom-dock tabs in approved order (Nuvio Mode + PC Mode + Settings). */
enum class NuvioTab(val labelEn: String, val labelPt: String) {
    REMOTE("Remote", "Remote"),
    TRACKPAD("Trackpad", "Trackpad"),
    SCREEN("Screen", "Tela"),
    APPS("Apps", "Apps"),
    SETTINGS("Settings", "Ajustes"),
    ;

    val label: String
        get() = labelEn

    fun label(lang: ResolvedLanguage): String = l10n(lang, labelEn, labelPt)
}

/** Type-safe routes used by the application's navigation back stack. */
@Immutable
sealed interface NuvioRoute {
    val tab: NuvioTab

    @Immutable
    data object Remote : NuvioRoute {
        override val tab: NuvioTab = NuvioTab.REMOTE
    }

    @Immutable
    data object Trackpad : NuvioRoute {
        override val tab: NuvioTab = NuvioTab.TRACKPAD
    }

    @Immutable
    data object Screen : NuvioRoute {
        override val tab: NuvioTab = NuvioTab.SCREEN
    }

    @Immutable
    data object Apps : NuvioRoute {
        override val tab: NuvioTab = NuvioTab.APPS
    }

    @Immutable
    data object Settings : NuvioRoute {
        override val tab: NuvioTab = NuvioTab.SETTINGS
    }

    companion object {
        fun fromTab(tab: NuvioTab): NuvioRoute = when (tab) {
            NuvioTab.REMOTE -> Remote
            NuvioTab.TRACKPAD -> Trackpad
            NuvioTab.SCREEN -> Screen
            NuvioTab.APPS -> Apps
            NuvioTab.SETTINGS -> Settings
        }
    }
}

/** Pushes a route onto the back stack, keeping Remote at the base and avoiding duplicates. */
fun pushNuvioRoute(backStack: List<NuvioRoute>, target: NuvioRoute): List<NuvioRoute> {
    if (backStack.lastOrNull() == target) return backStack
    if (target == NuvioRoute.Remote) return listOf(NuvioRoute.Remote)
    val base = if (backStack.isEmpty()) listOf(NuvioRoute.Remote) else backStack.filterNot { it == target }
    return base + target
}

/** Pops the top route returning to the previous one while always keeping Remote at the base. */
fun popNuvioRoute(backStack: List<NuvioRoute>): List<NuvioRoute> {
    if (backStack.size <= 1) return listOf(NuvioRoute.Remote)
    return backStack.dropLast(1)
}

enum class MouseButtons { LEFT, RIGHT, MIDDLE }

enum class ScreenProfile(val wire: String, val shortLabel: String) {
    P720_30("720p30", "720p 30"),
    P720_60("720p60", "720p 60"),
    P1080_30("1080p30", "1080p 30"),
    P1080_60("1080p60", "1080p 60"),
}
