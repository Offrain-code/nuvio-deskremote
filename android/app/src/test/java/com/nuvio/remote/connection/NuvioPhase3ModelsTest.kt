package com.nuvio.remote.connection

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioPhase3ModelsTest {

    @Test
    fun parseLyrics_syncedAndActiveLineIndex() {
        val obj = JSONObject().apply {
            put("kind", "synced")
            put("title", "Instant Crush - Daft Punk")
            put(
                "lines",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("timeMs", 110_000L)
                            put("text", "And we will never be alone again")
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("timeMs", 134_000L)
                            put("text", "A little time with you is all that I get")
                        },
                    )
                },
            )
        }

        val sheet = NuvioPhase3Parser.parseLyrics(obj)
        assertNotNull(sheet)
        assertTrue(sheet!!.isSynced)
        assertEquals(2, sheet.lines.size)
        assertEquals(-1, sheet.activeLineIndex(50_000L))
        assertEquals(0, sheet.activeLineIndex(120_000L))
        assertEquals(1, sheet.activeLineIndex(134_000L))
    }

    @Test
    fun parseLiveTv_stagesItemsAndButtons() {
        val obj = JSONObject().apply {
            put("stage", "channels")
            put("heading", "Canais ao Vivo")
            put("sub", "Esportes")
            put(
                "stages",
                JSONArray().apply {
                    put(JSONObject().put("name", "all").put("label", "Todos"))
                    put(JSONObject().put("name", "sports").put("label", "Esportes"))
                },
            )
            put(
                "items",
                JSONArray().apply {
                    put(
                        JSONObject()
                            .put("group", "ch")
                            .put("index", 0)
                            .put("slot", "1")
                            .put("title", "ESPN HD")
                            .put("sub", "Champions League")
                            .put("live", true)
                            .put("progress", 68)
                            .put("action", "tune"),
                    )
                },
            )
            put(
                "buttons",
                JSONArray().apply {
                    put(JSONObject().put("group", "nav").put("index", 0).put("label", "Guia / EPG").put("accent", true))
                },
            )
        }

        val screen = NuvioPhase3Parser.parseLiveTv(obj)
        assertNotNull(screen)
        assertEquals("Canais ao Vivo", screen!!.heading)
        assertEquals(2, screen.stages.size)
        assertEquals(1, screen.items.size)
        assertTrue(screen.items[0].live)
        assertEquals(68, screen.items[0].progress)
        assertEquals(1, screen.buttons.size)
    }

    @Test
    fun normalizeLiveTvProgress_convertsPermilleToPercent() {
        assertEquals(0, NuvioPhase3Parser.normalizeLiveTvProgress(-5))
        assertEquals(42, NuvioPhase3Parser.normalizeLiveTvProgress(42))
        assertEquals(45, NuvioPhase3Parser.normalizeLiveTvProgress(450))
        assertEquals(100, NuvioPhase3Parser.normalizeLiveTvProgress(1000))
        assertEquals(100, NuvioPhase3Parser.normalizeLiveTvProgress(1500))
    }

    @Test
    fun parseTonight_promptAnswersAndCards() {
        val obj = JSONObject().apply {
            put("view", "quiz")
            put("mode", "solo")
            put("number", 2)
            put("total", 5)
            put("reshuffleLeft", true)
            put("prompt", "Qual é o clima para assistir agora?")
            put("answers", JSONArray().put("Suspense & Sci-Fi").put("Comédia Leve"))
            put(
                "cards",
                JSONArray().apply {
                    put(
                        JSONObject()
                            .put("slot", "0")
                            .put("name", "Severance")
                            .put("why", "Combina com Andor")
                            .put("meta", "Série • 4K Dolby Vision")
                            .put("flag", true),
                    )
                },
            )
        }

        val screen = NuvioPhase3Parser.parseTonight(obj)
        assertNotNull(screen)
        assertEquals(2, screen!!.number)
        assertEquals(5, screen.total)
        assertEquals(2, screen.answers.size)
        assertEquals("Severance", screen.cards[0].name)
        assertTrue(screen.reshuffleLeft)
    }

    @Test
    fun qrPairParser_supportsUrlJsonPipeAndPlainPin() {
        val fromUrl = QrPairParser.parse("http://192.0.2.10:8765/?pin=319804")
        assertEquals("192.0.2.10", fromUrl?.host)
        assertEquals(8765, fromUrl?.port)
        assertEquals("319804", fromUrl?.pin)

        val fromJson = QrPairParser.parse("""{"host":"192.0.2.10","port":8787,"pin":"123456"}""")
        assertEquals("192.0.2.10", fromJson?.host)
        assertEquals(8787, fromJson?.port)
        assertEquals("123456", fromJson?.pin)

        val fromPipe = QrPairParser.parse("192.0.2.10:8787|654321")
        assertEquals("192.0.2.10", fromPipe?.host)
        assertEquals(8787, fromPipe?.port)
        assertEquals("654321", fromPipe?.pin)

        val fromPin = QrPairParser.parse("319 804")
        assertNull(fromPin?.host)
        assertEquals("319804", fromPin?.pin)

        assertNull(QrPairParser.parse("texto invalido"))
    }
}
