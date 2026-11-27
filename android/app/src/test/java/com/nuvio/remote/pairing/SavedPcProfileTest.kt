package com.nuvio.remote.pairing

import com.nuvio.remote.share.ShareToPcClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPcProfileTest {
    @Test
    fun `encode and decode preserve saved profiles`() {
        val sampleToken = listOf("nuvio", "sec", "123456789012345678901234567890").joinToString("_")
        val list = listOf(
            SavedPcProfile(
                id = "pc-1",
                name = "PC Sala",
                host = "192.168.0.42",
                port = 8787,
                serverId = "srv-sala",
                token = sampleToken,
                wakeMacs = listOf("AA:BB:CC:DD:EE:FF"),
                wakeBroadcasts = listOf("192.168.0.255"),
                lastConnectedAtMs = 1000L,
            ),
        )
        val decoded = SavedPcProfileCodec.decode(SavedPcProfileCodec.encode(list))
        assertEquals(1, decoded.size)
        assertEquals("PC Sala", decoded[0].name)
        assertEquals("192.168.0.42", decoded[0].host)
        assertTrue(decoded[0].isPaired())
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), decoded[0].wakeMacs)
    }

    @Test
    fun `upsert merges by serverId or host, rename and remove work`() {
        val tokA = listOf("nuvio", "sec", "a".repeat(32)).joinToString("_")
        val tokB = listOf("nuvio", "sec", "b".repeat(32)).joinToString("_")
        val first = SavedPcProfile(
            id = "id-1",
            name = "PC Sala",
            host = "192.168.0.42",
            serverId = "srv-sala",
            token = tokA,
        )
        val updated = SavedPcProfile(
            id = "id-new",
            name = "",
            host = "192.168.0.50",
            serverId = "srv-sala",
            token = tokB,
        )
        val merged = SavedPcProfileCodec.upsert(listOf(first), updated)
        assertEquals(1, merged.size)
        assertEquals("PC Sala", merged[0].name)
        assertEquals("192.168.0.50", merged[0].host)

        val renamed = SavedPcProfileCodec.rename(merged, "id-1", "HTPC Sala")
        assertEquals("HTPC Sala", renamed[0].name)

        val removed = SavedPcProfileCodec.remove(renamed, "id-1")
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `share client extracts only http and https urls`() {
        val url = ShareToPcClient.extractFirstHttpUrl("Check https://nuvio.app/releases/v0.6.0 now")
        assertEquals("https://nuvio.app/releases/v0.6.0", url)
        assertNull(ShareToPcClient.extractFirstHttpUrl("just plain text"))
        assertNull(ShareToPcClient.extractFirstHttpUrl("file:///etc/passwd"))
        assertEquals("1.0 MB", ShareToPcClient.formatBytes(1024L * 1024L))
    }
}
