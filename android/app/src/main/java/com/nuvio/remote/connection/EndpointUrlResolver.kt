package com.nuvio.remote.connection

import java.util.Locale

/**
 * Centralized endpoint validation and URL builder shared across connection, pairing,
 * discovery, and ShareActivity so cleartext traffic stays restricted to private LAN hosts
 * and remote access requires verified HTTPS/WSS tunnel endpoints.
 */
object EndpointUrlResolver {
    private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val HOSTNAME_CHARS_REGEX = Regex("""^[a-z0-9.-]+$""")

    fun stripSchemeAndSlash(raw: String): String = raw.trim()
        .removePrefix("wss://")
        .removePrefix("ws://")
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')

    fun extractHostOnly(raw: String): String {
        val cleaned = stripSchemeAndSlash(raw).substringBefore('/').substringBefore('?')
        if (cleaned.startsWith("[")) {
            return cleaned.substringAfter('[').substringBefore(']')
        }
        return cleaned.substringBefore(':').trim()
    }

    fun isRemoteTunnelHost(raw: String): Boolean {
        val trimmed = raw.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return false
        return trimmed.startsWith("wss://") ||
            trimmed.startsWith("https://") ||
            isKnownTunnelDomain(extractHostOnly(trimmed))
    }

    fun isKnownTunnelDomain(host: String): Boolean {
        val h = host.trim().lowercase(Locale.ROOT)
        return h.endsWith(".trycloudflare.com") ||
            h.endsWith(".ngrok-free.app") ||
            h.endsWith(".ngrok.io") ||
            h.endsWith(".ngrok.app")
    }

    /** Validates a tunnel URL sent by the Companion server before saving or connecting. */
    fun isAllowedTunnelUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() } || trimmed.contains('@')) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        val hasSecureScheme = lower.startsWith("https://") || lower.startsWith("wss://")
        if (!hasSecureScheme) return false
        val host = extractHostOnly(trimmed)
        return host.isNotEmpty() && HOSTNAME_CHARS_REGEX.matches(host) && isKnownTunnelDomain(host)
    }

    /** Returns true only for RFC 1918 IPv4, link-local, loopback, or `.local` LAN hosts. */
    fun isPrivateLanHost(raw: String): Boolean {
        val host = extractHostOnly(raw).lowercase(Locale.ROOT)
        val hasInvalidChars = host.isEmpty() || host.contains('@') || host.any { it.isWhitespace() }
        if (hasInvalidChars) return false
        val isLocalName = host == "localhost" || host == "127.0.0.1" || host == "::1" || isMdnsLocalHost(host)
        return isLocalName || isPrivateIpv4(host)
    }

    private fun isMdnsLocalHost(host: String): Boolean =
        host.endsWith(".local") && HOSTNAME_CHARS_REGEX.matches(host) && host.length > ".local".length

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = parseIpv4Octets(host) ?: return false
        val a = octets[0]
        val b = octets[1]
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
    }

    private fun parseIpv4Octets(host: String): IntArray? {
        val match = IPV4_REGEX.matchEntire(host) ?: return null
        val values = IntArray(4)
        for (i in 0 until 4) {
            val octet = match.groupValues[i + 1].toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            values[i] = octet
        }
        return values
    }

    /**
     * Builds a safe WebSocket URL without putting credentials in the query string.
     * Returns `null` when the target is empty, malformed, or a non-LAN cleartext host.
     */
    fun buildWsUrl(rawHost: String, port: Int): String? {
        val trimmed = rawHost.trim()
        val hasInvalidChars = trimmed.isEmpty() || trimmed.any { it.isWhitespace() } || trimmed.contains('@')
        if (hasInvalidChars) return null
        val hostOnly = extractHostOnly(trimmed)
        return if (isRemoteTunnelHost(trimmed)) {
            if (hostOnly.isNotEmpty() && HOSTNAME_CHARS_REGEX.matches(hostOnly)) "wss://$hostOnly" else null
        } else if (isPrivateLanHost(hostOnly) && port in 1..65535) {
            "ws://$hostOnly:$port"
        } else {
            null
        }
    }

    /**
     * Builds a strictly LAN-only HTTP URL for pairing endpoints.
     * Returns `null` for empty, malformed, remote, or public hosts.
     */
    fun buildLanHttpUrl(rawHost: String, port: Int, path: String): String? {
        val trimmed = rawHost.trim()
        if (trimmed.isEmpty() || isRemoteTunnelHost(trimmed)) return null
        val lanHost = extractHostOnly(trimmed)
        if (!isPrivateLanHost(lanHost) || port !in 1..65535) return null
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "http://$lanHost:$port$cleanPath"
    }
}
