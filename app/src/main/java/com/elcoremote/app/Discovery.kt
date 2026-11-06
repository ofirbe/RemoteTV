package com.elcoremote.app

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds TVs on the local Wi-Fi in two ways:
 *  1. SSDP/UPnP multicast (M-SEARCH) to collect friendly names of media devices.
 *  2. A quick TCP probe of every host on the /24 subnet for ADB (5555) and Roku (8060) ports.
 */
class Discovery(private val context: Context) {

    fun scan(): List<TvDevice> {
        val names = ssdpNames()
        val subnet = localSubnetPrefix() ?: return emptyList()
        val found = ConcurrentHashMap<String, TvDevice>()
        val pool = Executors.newFixedThreadPool(64)
        for (host in 1..254) {
            val ip = "$subnet.$host"
            for (proto in Protocol.values()) {
                pool.execute {
                    if (isPortOpen(ip, proto.port)) {
                        val key = "$ip:${proto.name}"
                        found[key] = TvDevice(ip, proto, names[ip] ?: ip)
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
        return found.values.sortedWith(compareBy({ it.protocol.ordinal }, { it.ip }))
    }

    private fun isPortOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 350); true }
    } catch (_: Exception) {
        false
    }

    /** Returns e.g. "192.168.1" for the phone's Wi-Fi IPv4 address. */
    private fun localSubnetPrefix(): String? {
        val addr = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?: return null
        return addr.hostAddress?.substringBeforeLast('.')
    }

    /** SSDP M-SEARCH; maps IP -> friendly name (best effort). */
    private fun ssdpNames(): Map<String, String> {
        val result = HashMap<String, String>()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("elco-ssdp").apply { setReferenceCounted(false); acquire() }
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 1500
                val req = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 1\r\n" +
                        "ST: ssdp:all\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { sock.send(DatagramPacket(req, req.size, group, 1900)) }
                val locations = HashMap<String, String>()
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 2500
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (_: SocketTimeoutException) { break }
                    val ip = pkt.address.hostAddress ?: continue
                    val text = String(pkt.data, 0, pkt.length)
                    val loc = text.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()
                    if (loc != null && ip !in locations) locations[ip] = loc
                }
                for ((ip, loc) in locations) {
                    friendlyName(loc)?.let { result[ip] = it }
                }
            }
        } catch (_: Exception) {
        } finally {
            lock.release()
        }
        return result
    }

    private fun friendlyName(location: String): String? = try {
        val conn = URL(location).openConnection().apply { connectTimeout = 800; readTimeout = 800 }
        val xml = conn.getInputStream().bufferedReader().use { it.readText() }
        Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()
    } catch (_: Exception) {
        null
    }
}
