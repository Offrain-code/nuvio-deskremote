package com.nuvio.remote.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NuvioCatalogModelsTest {

    @Test
    fun `NuvioWakeOnLan buildMagicPacket generates 102 valid bytes and rejects invalid MAC`() {
        val packet = NuvioWakeOnLan.buildMagicPacket("AA:BB:CC:DD:EE:FF")
        assertNotNull(packet)
        assertEquals(102, packet!!.size)
        for (i in 0 until 6) {
            assertEquals(0xFF.toByte(), packet[i])
        }
        assertEquals(0xAA.toByte(), packet[6])
        assertEquals(0xFF.toByte(), packet[11])
        assertEquals(0xAA.toByte(), packet[96])
        assertEquals(0xFF.toByte(), packet[101])

        assertNull(NuvioWakeOnLan.buildMagicPacket("INVALID"))
        assertNull(NuvioWakeOnLan.buildMagicPacket("AABBCC"))
    }

    @Test
    fun `NuvioContinueItem remainingMinutes calculates remaining time accurately`() {
        val item = NuvioContinueItem(
            contentType = "series",
            parentId = "tt0096697",
            videoId = "tt0096697:22:2",
            title = "The Simpsons",
            episode = "S22 E2",
            poster = null,
            still = null,
            positionMs = 300_000L,
            durationMs = 1_440_000L,
            lastSource = "Torrentio",
            progressPermille = 208,
        )
        assertEquals(19, item.remainingMinutes)

        val unknown = item.copy(durationMs = 0L)
        assertNull(unknown.remainingMinutes)
    }
}
