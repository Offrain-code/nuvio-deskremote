package com.nuvio.remote.connection

import com.nuvio.remote.ui.i18n.ResolvedLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class NuvioModelsTest {
    @Test
    fun `tabs keep unified naming in English and Portuguese`() {
        val enLabels = NuvioTab.entries.map { it.label(ResolvedLanguage.EN) }
        val ptLabels = NuvioTab.entries.map { it.label(ResolvedLanguage.PT_BR) }
        assertEquals(listOf("Remote", "Trackpad", "Screen", "Apps", "Settings"), enLabels)
        assertEquals(listOf("Remote", "Trackpad", "Tela", "Apps", "Ajustes"), ptLabels)
    }

    @Test
    fun `route backstack navigates cleanly and pops back to Remote`() {
        var stack = listOf<NuvioRoute>(NuvioRoute.Remote)
        stack = pushNuvioRoute(stack, NuvioRoute.Trackpad)
        stack = pushNuvioRoute(stack, NuvioRoute.Settings)
        assertEquals(listOf(NuvioRoute.Remote, NuvioRoute.Trackpad, NuvioRoute.Settings), stack)

        stack = popNuvioRoute(stack)
        assertEquals(listOf(NuvioRoute.Remote, NuvioRoute.Trackpad), stack)

        stack = pushNuvioRoute(stack, NuvioRoute.Remote)
        assertEquals(listOf(NuvioRoute.Remote), stack)
    }
}
