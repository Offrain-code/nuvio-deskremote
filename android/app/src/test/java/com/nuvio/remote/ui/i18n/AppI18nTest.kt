package com.nuvio.remote.ui.i18n

import com.nuvio.remote.connection.AppsSubTab
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppI18nTest {
    @Test
    fun `system preference resolves Portuguese locales to PT_BR and others to EN`() {
        assertEquals(ResolvedLanguage.PT_BR, resolveAppLanguage(AppLanguage.SYSTEM, Locale("pt", "BR")))
        assertEquals(ResolvedLanguage.PT_BR, resolveAppLanguage(AppLanguage.SYSTEM, Locale("pt", "PT")))
        assertEquals(ResolvedLanguage.EN, resolveAppLanguage(AppLanguage.SYSTEM, Locale.US))
        assertEquals(ResolvedLanguage.EN, resolveAppLanguage(AppLanguage.SYSTEM, Locale("es", "ES")))
    }

    @Test
    fun `explicit preferences override device locale`() {
        assertEquals(ResolvedLanguage.EN, resolveAppLanguage(AppLanguage.ENGLISH, Locale("pt", "BR")))
        assertEquals(ResolvedLanguage.PT_BR, resolveAppLanguage(AppLanguage.PORTUGUESE_BR, Locale.US))
    }

    @Test
    fun `fromWire parses known values and falls back to SYSTEM`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromWire("en"))
        assertEquals(AppLanguage.PORTUGUESE_BR, AppLanguage.fromWire("pt-br"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromWire("system"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromWire("unknown"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromWire(null))
    }

    @Test
    fun `l10n and AppsSubTab return matching localized labels`() {
        assertEquals("Screen", l10n(ResolvedLanguage.EN, "Screen", "Tela"))
        assertEquals("Tela", l10n(ResolvedLanguage.PT_BR, "Screen", "Tela"))
        assertEquals(
            listOf("Watch", "Music", "Modules"),
            AppsSubTab.entries.map { it.label(ResolvedLanguage.EN) },
        )
        assertEquals(
            listOf("Assistir", "Música", "Módulos"),
            AppsSubTab.entries.map { it.label(ResolvedLanguage.PT_BR) },
        )
    }
}
