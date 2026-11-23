package com.nuvio.remote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.nuvio.remote.connection.EndpointUrlResolver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * LAN PC Companion discovery (SECURITY.md section 2):
 * 1. mDNS/DNS-SD: _nuvio-remote._tcp.local;
 * 2. UDP broadcast fallback: probes 255.255.255.255 and local subnet broadcasts on port 8766,
 *    receiving unicast reply {"status":"FOUND","port":8787}.
 */
fun discoverNsd(context: Context, serviceType: String = "_nuvio-remote._tcp."): Flow<PcService> = callbackFlow {
    val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!serviceInfo.serviceType.startsWith(serviceType.trimEnd('.'))) return
            manager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host
                        val ip = host?.hostAddress ?: return
                        trySend(PcService(host.hostName ?: ip, ip, info.port))
                    }
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                },
            )
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            close(null)
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
    }
    manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    awaitClose {
        try {
            manager.stopServiceDiscovery(listener)
        } catch (_: Exception) {}
    }
}

/**
 * Parses and validates a UDP discovery response against the packet's real source IP.
 * Rejects non-LAN sources and ignores spoofed `ip` values inside the JSON body.
 */
internal fun parseDiscoveryPacket(text: String, senderIp: String?): PcService? {
    val safeSenderIp = senderIp?.trim().orEmpty()
    val obj = if (EndpointUrlResolver.isPrivateLanHost(safeSenderIp)) {
        runCatching { JSONObject(text) }.getOrNull()
    } else {
        null
    }
    val port = obj?.optInt("port", 8787) ?: -1
    val isValid = obj != null && obj.optString("status").equals("FOUND", ignoreCase = true) && port in 1..65535
    if (!isValid) return null
    val hostname = obj.optString("server_name", "").trim().ifEmpty { safeSenderIp }.take(64)
    val serverId = obj.optString("server_id", "").trim().take(80)
    return PcService(
        hostname = hostname,
        ip = safeSenderIp,
        port = port,
        serverId = serverId,
    )
}

/** UDP broadcast fallback (SECURITY section 2.2). Response port is ephemeral. */
fun discoverUdp(port: Int = 8766, timeoutMs: Int = 1800): List<PcService> {
    val found = LinkedHashMap<String, PcService>()
    val probe = """{"action":"DISCOVER","client":"NuvioAndroid","version":"1.0.0"}"""
    try {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val data = probe.toByteArray(Charsets.UTF_8)

            val targets = LinkedHashSet<InetAddress>()
            runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
            runCatching {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces != null && ifaces.hasMoreElements()) {
                    val nif = ifaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ia in nif.interfaceAddresses) {
                        val bcast = ia.broadcast
                        if (bcast is Inet4Address) targets.add(bcast)
                    }
                }
            }

            for (dest in targets) {
                runCatching { socket.send(DatagramPacket(data, data.size, dest, port)) }
            }

            val deadlineNs = System.nanoTime() + timeoutMs.toLong() * 1_000_000L
            val buf = ByteArray(1024)
            while (true) {
                val remainingMs = ((deadlineNs - System.nanoTime()) / 1_000_000L).toInt()
                if (remainingMs <= 0) break
                socket.soTimeout = remainingMs
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val service = parseDiscoveryPacket(text, packet.address?.hostAddress) ?: continue
                    found.putIfAbsent(service.ip, service)
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
        }
    } catch (_: Exception) {
        // Broadcast unavailable (router isolation or no network): Settings keeps manual IP fallback
    }
    return found.values.toList()
}
