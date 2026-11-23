package com.nuvio.remote.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends Wake-on-LAN magic packets (UDP ports 9 and 7) over local Wi-Fi
 * using MAC addresses and broadcast targets learned from the native Nuvio bridge.
 */
object NuvioWakeOnLan {
    private val WOL_PORTS = intArrayOf(9, 7)
    private const val MAGIC_PREFIX_BYTES = 6
    private const val MAC_BYTES = 6
    private const val MAC_REPETITIONS = 16
    private const val PACKET_SIZE = MAGIC_PREFIX_BYTES + MAC_BYTES * MAC_REPETITIONS

    fun buildMagicPacket(macHex: String): ByteArray? {
        val clean = macHex.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (clean.length != 12) return null
        val mac = ByteArray(MAC_BYTES) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val packet = ByteArray(PACKET_SIZE)
        for (i in 0 until MAGIC_PREFIX_BYTES) {
            packet[i] = 0xFF.toByte()
        }
        for (rep in 0 until MAC_REPETITIONS) {
            mac.copyInto(packet, destinationOffset = MAGIC_PREFIX_BYTES + rep * MAC_BYTES)
        }
        return packet
    }

    suspend fun sendWake(info: NuvioWakeInfo): Boolean = withContext(Dispatchers.IO) {
        if (info.macs.isEmpty()) return@withContext false
        val targets = (info.broadcasts + "255.255.255.255").distinct()
        var sentAny = false
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (mac in info.macs) {
                    val payload = buildMagicPacket(mac) ?: continue
                    for (host in targets) {
                        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: continue
                        for (port in WOL_PORTS) {
                            runCatching {
                                socket.send(DatagramPacket(payload, payload.size, addr, port))
                                sentAny = true
                            }
                        }
                    }
                }
            }
        } catch (_: IOException) {
            return@withContext sentAny
        }
        sentAny
    }
}
