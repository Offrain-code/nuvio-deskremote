package com.nuvio.remote.connection

import com.nuvio.remote.MainActivity
import com.nuvio.remote.discovery.PcService
import com.nuvio.remote.discovery.parseDiscoveryPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityHardeningTest {
    @Test
    fun `buildWsUrl enforces LAN or WSS tunnel and never embeds auth token in URL`() {
        assertEquals("ws://192.168.1.25:8787", EndpointUrlResolver.buildWsUrl("192.168.1.25", 8787))
        assertEquals("ws://10.0.0.5:8787", EndpointUrlResolver.buildWsUrl("http://10.0.0.5:8765/", 8787))
        assertEquals(
            "wss://demo-host.trycloudflare.com",
            EndpointUrlResolver.buildWsUrl("https://demo-host.trycloudflare.com/", 8787),
        )
        assertNull(EndpointUrlResolver.buildWsUrl("", 8787))
        assertNull(EndpointUrlResolver.buildWsUrl("Remote • abcdef", 8787))
        assertNull(EndpointUrlResolver.buildWsUrl("8.8.8.8", 8787))
        assertNull(EndpointUrlResolver.buildWsUrl("evil.example.com", 8787))
        assertNull(EndpointUrlResolver.buildWsUrl("192.168.1.999", 8787))
    }

    @Test
    fun `buildLanHttpUrl restricts pairing strictly to private LAN hosts`() {
        assertEquals(
            "http://192.168.1.25:8765/api/v1/pair",
            EndpointUrlResolver.buildLanHttpUrl("192.168.1.25", 8765, "/api/v1/pair"),
        )
        assertNull(EndpointUrlResolver.buildLanHttpUrl("", 8765, "/api/v1/pair"))
        assertNull(EndpointUrlResolver.buildLanHttpUrl("https://demo.trycloudflare.com", 8765, "/api/v1/pair"))
        assertNull(EndpointUrlResolver.buildLanHttpUrl("203.0.113.5", 8765, "/api/v1/pair"))
    }

    @Test
    fun `isAllowedTunnelUrl requires HTTPS or WSS and known tunnel domain`() {
        assertTrue(EndpointUrlResolver.isAllowedTunnelUrl("https://abc-123.trycloudflare.com"))
        assertTrue(EndpointUrlResolver.isAllowedTunnelUrl("wss://abc-123.trycloudflare.com"))
        assertFalse(EndpointUrlResolver.isAllowedTunnelUrl("http://abc-123.trycloudflare.com"))
        assertFalse(EndpointUrlResolver.isAllowedTunnelUrl("https://evil.example.com"))
        assertFalse(EndpointUrlResolver.isAllowedTunnelUrl("https://user@abc-123.trycloudflare.com"))
    }

    @Test
    fun `parseDiscoveryPacket ignores spoofed JSON ip and rejects non-LAN senders`() {
        val spoofedJson =
            """{"status":"FOUND","server_id":"srv-pc","server_name":"HTPC","ip":"10.0.0.99","port":8787}"""
        val parsed = parseDiscoveryPacket(spoofedJson, senderIp = "192.168.1.40")
        assertNotNull(parsed)
        assertEquals("192.168.1.40", parsed!!.ip)
        assertEquals("srv-pc", parsed.serverId)
        assertEquals("HTPC", parsed.hostname)
        assertEquals(8787, parsed.port)

        assertNull(parseDiscoveryPacket(spoofedJson, senderIp = "203.0.113.9"))
        assertNull(parseDiscoveryPacket("""{"status":"FOUND","port":99999}""", senderIp = "192.168.1.40"))
    }

    @Test
    fun `shouldAutoConnectDiscovered blocks zero-click hijacking on untrusted Wi-Fi`() {
        val roguePc = PcService(hostname = "Rogue", ip = "192.168.1.88", port = 8787, serverId = "srv-rogue")
        val samePcNewIp = PcService(hostname = "Home", ip = "192.168.1.77", port = 8787, serverId = "srv-home")
        val sameIpNoId = PcService(hostname = "Home", ip = "192.168.1.50", port = 8787, serverId = "")

        assertFalse(MainActivity.shouldAutoConnectDiscovered(false, "", null, samePcNewIp))
        assertFalse(MainActivity.shouldAutoConnectDiscovered(true, "192.168.1.50", "srv-home", roguePc))
        assertTrue(MainActivity.shouldAutoConnectDiscovered(true, "192.168.1.50", "srv-home", samePcNewIp))
        assertTrue(MainActivity.shouldAutoConnectDiscovered(true, "192.168.1.50", "srv-home", sameIpNoId))
    }
}
