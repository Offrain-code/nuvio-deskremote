package com.nuvio.remote.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioBridgeModelsTest {

    @Test
    fun `formatMediaTime formats minutes and hours correctly`() {
        assertEquals("00:00", formatMediaTime(0L))
        assertEquals("02:14", formatMediaTime(134_000L))
        assertEquals("1:05:09", formatMediaTime(3_909_000L))
    }

    @Test
    fun `computeFilmEndsAtEpochMs respects speed and duration`() {
        assertNull(computeFilmEndsAtEpochMs(10_000L, 0L, 100, 1_000_000L))
        // 60 min remaining at 2x -> finishes in 30 min (1_800_000 ms)
        val ends = computeFilmEndsAtEpochMs(
            positionMs = 0L,
            durationMs = 3_600_000L,
            speedPercent = 200,
            nowEpochMs = 1_000_000L,
        )
        assertEquals(2_800_000L, ends)
    }

    @Test
    fun `NuvioNowPlaying positionAt advances only while playing`() {
        val playing = NuvioNowPlaying(
            kind = "film",
            isPlaying = true,
            positionMs = 10_000L,
            durationMs = 60_000L,
            volume = 80,
            volumeMax = 100,
            canSkip = true,
            title = "Andor",
            subtitle = "S01 E03",
            imageUrl = null,
            receivedAtMs = 1_000L,
        )
        assertEquals(15_000L, playing.positionAt(6_000L))
        assertEquals(60_000L, playing.positionAt(99_000L))

        val paused = playing.copy(isPlaying = false)
        assertEquals(10_000L, paused.positionAt(50_000L))
    }

    @Test
    fun `SourceBadgeParser detects 4K DV HEVC Atmos Remux and filters accurately`() {
        val facts = SourceBadgeParser.parse(
            name = "Torrentio 2160p BluRay REMUX",
            detail = "Andor.S01E03.DV.HDR10+.x265.Atmos",
        )
        assertEquals("4K", facts.resolution?.label)
        assertEquals("DV", facts.hdr?.label)
        assertEquals("HEVC", facts.codec?.label)
        assertTrue(facts.extras.any { it.label == "Atmos" })
        assertTrue(facts.extras.any { it.label == "Remux" })

        assertTrue(SourceBadgeParser.matches(facts, SourceBadgeParser.FILTER_4K))
        assertTrue(SourceBadgeParser.matches(facts, SourceBadgeParser.FILTER_HDR))
        assertTrue(SourceBadgeParser.matches(facts, SourceBadgeParser.FILTER_DV))

        val fhd = SourceBadgeParser.parse("MediaFusion 1080p AV1", null)
        assertEquals("1080p", fhd.resolution?.label)
        assertEquals("AV1", fhd.codec?.label)
        assertFalse(SourceBadgeParser.matches(fhd, SourceBadgeParser.FILTER_4K))
        assertFalse(SourceBadgeParser.matches(fhd, SourceBadgeParser.FILTER_HDR))
    }

    @Test
    fun `formatFileSizeBr formats MB and GB values`() {
        assertNull(formatFileSizeBr(null))
        assertNull(formatFileSizeBr(0L))
        assertNotNull(formatFileSizeBr(18_400_000_000L))
        assertTrue(formatFileSizeBr(524_288_000L)?.endsWith("MB") == true)
    }
}
